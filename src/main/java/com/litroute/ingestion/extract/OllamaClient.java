package com.litroute.ingestion.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litroute.ingestion.enrich.HttpReply;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The local language model, served by Ollama on this machine. A request carries a JSON Schema in Ollama's
 * {@code format} field, which constrains the model's sampling to that shape, with temperature 0 and a fixed
 * seed so the same email gets the same answer. Only a loopback address is accepted, so an email's text never
 * leaves the machine.
 *
 * {@value #MODEL_VARIABLE} picks another model than {@value #DEFAULT_MODEL}, and {@value #URL_VARIABLE} another
 * address than {@value #DEFAULT_URL}.
 *
 * Example:
 * <pre>{@code
 * Input:  fromEnvironment().generateJson("Name a colour.", schema of {"colour": string}, 64, 42)
 *         -> POST http://localhost:11434/api/chat
 *            {"model":"llama3.2:3b", "stream":false, "format":{...schema...},
 *             "options":{"temperature":0, "seed":42, "num_ctx":4096, "num_predict":64},
 *             "messages":[{"role":"user", "content":"Name a colour."}]}
 *         <- {"message":{"content":"{\"colour\":\"blue\"}"}, "done_reason":"stop", ...}
 * Output: Answer("{\"colour\":\"blue\"}", complete = true)
 * }</pre>
 */
public final class OllamaClient {

    public static final String URL_VARIABLE = "LITROUTE_OLLAMA_URL";
    public static final String MODEL_VARIABLE = "LITROUTE_OLLAMA_MODEL";
    public static final String DEFAULT_URL = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "llama3.2:3b";

    /** Room for prompts.txt (~260 tokens), a slice of an email (~1,500) and its answer (~1,300). */
    static final int CONTEXT_TOKENS = 4096;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** Long, since a small model on a CPU can take minutes to load and answer. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The client's only door to the network; tests replay what Ollama really answered. */
    interface Poster {
        /**
         * Input: the URI and a JSON body.
         * Output: the reply (an HTTP error status is a reply, not an exception); IOException if unreachable or timed out.
         * Used by: {@link OllamaClient#generateJson}.
         * Example: POST http://localhost:11434/api/chat -> HttpReply(200, "application/json", {...message...})
         */
        HttpReply post(URI uri, byte[] body) throws IOException;
    }

    /**
     * The model's answer.
     *
     * @param text     what the model wrote
     * @param complete false if it was stopped at the token budget, so the text is cut short and unusable
     */
    record Answer(String text, boolean complete) {
    }

    private final Poster poster;
    private final URI chatUri;
    private final String model;

    /**
     * Input: the transport, the server's address and the model's name.
     * Output: a client; throws if the address is not on this machine.
     * Used by: {@link #fromEnvironment}; tests with recorded replies.
     * Example: {@code new OllamaClient(recorded, "http://localhost:11434", "llama3.2:3b")}
     */
    OllamaClient(Poster poster, String baseUrl, String model) {
        this.poster = poster;
        this.chatUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
        this.model = model;
        if (!isLoopback(chatUri)) {
            throw new IllegalArgumentException(URL_VARIABLE + " must be an address on this machine, so that emails "
                    + "stay on it; it was " + baseUrl);
        }
    }

    /**
     * Input: none (reads the process environment).
     * Output: a client on the JDK's HttpClient for the chosen address and model.
     * Used by: AiTitleExtractor.fromEnvironment.
     * Example: nothing set -> llama3.2:3b at http://localhost:11434
     */
    public static OllamaClient fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Input: the environment to read.
     * Output: a client for {@value #URL_VARIABLE} and {@value #MODEL_VARIABLE}, or their defaults.
     * Used by: {@link #fromEnvironment()}; OllamaClientTest.
     * Example: {LITROUTE_OLLAMA_MODEL=llama3.2-14t} -> llama3.2-14t at http://localhost:11434
     */
    static OllamaClient fromEnvironment(Map<String, String> environment) {
        return new OllamaClient(jdkPoster(), orDefault(environment.get(URL_VARIABLE), DEFAULT_URL),
                orDefault(environment.get(MODEL_VARIABLE), DEFAULT_MODEL));
    }

    /**
     * Input: an environment value and its default.
     * Output: the value, or the default if it is unset or blank.
     * Used by: {@link #fromEnvironment(Map)}.
     * Example: ("  ", "llama3.2:3b") -> "llama3.2:3b"
     */
    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * Input: the server's URI.
     * Output: true if its host is this machine (no DNS lookup leaves it for a name like "localhost").
     * Used by: the constructor.
     * Example: http://localhost:11434 -> true; http://127.0.0.1:11434 -> true; https://example.org -> false
     */
    private static boolean isLoopback(URI uri) {
        try {
            return uri.getHost() != null && InetAddress.getByName(uri.getHost()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Input: none.
     * Output: a {@link Poster} on the JDK's HttpClient, HTTP/1.1, failing fast when nothing is listening.
     * Used by: {@link #fromEnvironment(Map)}.
     * Example: Ollama stopped -> post(...) throws ConnectException
     */
    private static Poster jdkPoster() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        return (uri, body) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                return new HttpReply(response.statusCode(), response.headers().firstValue("content-type").orElse(""),
                        response.body(), response.uri(), false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the local model", e);
            }
        };
    }

    /**
     * Input: none.
     * Output: the model's name.
     * Used by: AiTitleExtractor, in its log lines.
     * Example: -> "llama3.2:3b"
     */
    public String modelId() {
        return model;
    }

    /**
     * Input: the prompt, the JSON Schema the answer must follow, the most tokens it may use, and the seed.
     * Output: the answer; IOException if Ollama isn't running, doesn't have the model, or sends something unreadable.
     * Used by: AiTitleExtractor, once per slice of an email.
     * Example: a model that keeps writing past the budget -> Answer(its text so far, complete = false)
     */
    Answer generateJson(String prompt, JsonNode schema, int maxAnswerTokens, int seed) throws IOException {
        HttpReply reply;
        try {
            reply = poster.post(chatUri, requestBody(prompt, schema, maxAnswerTokens, seed));
        } catch (ConnectException e) {
            throw new IOException("Ollama is not running at " + chatUri.getHost() + ":" + chatUri.getPort()
                    + " (start it with: ollama serve)", e);
        } catch (IOException e) {
            throw new IOException("The local model did not answer: " + e, e);
        }
        JsonNode response;
        try {
            response = JSON.readTree(reply.body());
        } catch (IOException e) {
            throw new IOException("Ollama sent an unreadable reply (HTTP " + reply.status() + ")", e);
        }
        if (!reply.ok()) {
            throw new IOException("Ollama refused the request: HTTP " + reply.status() + " "
                    + response.path("error").asText("") + (reply.status() == 404 ? " (run: ollama pull " + model + ")" : ""));
        }
        JsonNode content = response.path("message").path("content");
        if (!content.isTextual()) {
            throw new IOException("Ollama's reply has no answer in it");
        }
        return new Answer(content.asText(), "stop".equals(response.path("done_reason").asText()));
    }

    /**
     * Input: as {@link #generateJson}.
     * Output: the JSON request for Ollama's chat endpoint, always the same bytes for the same input.
     * Used by: {@link #generateJson}.
     * Example: ("Hi", schema, 64, 42) -> {"model":"llama3.2:3b","stream":false,"format":{...},"options":{...},"messages":[...]}
     */
    private byte[] requestBody(String prompt, JsonNode schema, int maxAnswerTokens, int seed) throws IOException {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", model);
        request.put("stream", false);
        request.set("format", schema);
        ObjectNode options = request.putObject("options");
        options.put("temperature", 0);
        options.put("seed", seed);
        options.put("num_ctx", CONTEXT_TOKENS);
        options.put("num_predict", maxAnswerTokens);
        ObjectNode message = request.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return JSON.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
    }
}
