package com.litroute.scoring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litroute.ingestion.enrich.HttpReply;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@link LlmClient} on Groq's chat completions API (REST, OpenAI-compatible), which has a free tier. The
 * answer is constrained to the schema with Structured Outputs in strict mode, so it parses as JSON of that
 * shape.
 *
 * <p>Strict mode wants every object closed ({@code additionalProperties: false}) with all its properties
 * required, so {@link #strictSchema} rewrites the matcher's schema that way: a property the schema leaves
 * optional becomes required but nullable. Numeric bounds are left out, since the matcher checks them itself.
 *
 * <p>The API key comes from the environment ({@value #KEY_VARIABLE}); LitRoute never writes it to disk.
 * {@value #MODEL_VARIABLE} picks another model than {@value #DEFAULT_MODEL}, which must be one Groq offers
 * strict Structured Outputs for. The model reasons briefly ({@code reasoning_effort: low}) and its reasoning
 * is not sent back, which keeps a request within the free tier's 8,000 tokens a minute. That limit is
 * also the token window: a prompt may take what the answer leaves of it ({@link #maxPromptTokens}), and
 * {@value #TOKENS_PER_MINUTE_VARIABLE} sets a paid account's larger one.
 *
 * <p>Throttled or overloaded requests are retried as {@link ApiCalls#post} describes, after the wait Groq
 * names ("Please try again in 7.66s") if it names one; a used-up daily limit names hours and is reported
 * at once. That is also how a batch sent in several requests keeps to the tokens a minute: the next
 * request waits as long as Groq asks.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Input:  GroqClient.fromEnvironment().generateJson("Name a colour.", schema of {"colour": string})
 *         -> POST https://api.groq.com/openai/v1/chat/completions
 *            {"model":"openai/gpt-oss-120b","messages":[{"role":"user","content":"Name a colour."}],
 *             "response_format":{"type":"json_schema","json_schema":{"name":"answer","strict":true,"schema":{...}}},
 *             "reasoning_effort":"low","include_reasoning":false,"max_completion_tokens":2500}
 *         <- HTTP 200 {"choices":[{"message":{"role":"assistant","content":"{\"colour\":\"blue\"}"},
 *                                  "finish_reason":"stop"}], ...}
 * Output: {"colour":"blue"}
 * }</pre>
 */
public final class GroqClient implements LlmClient {

    public static final String KEY_VARIABLE = "GROQ_API_KEY";
    public static final String MODEL_VARIABLE = "GROQ_MODEL";
    public static final String TOKENS_PER_MINUTE_VARIABLE = "GROQ_TOKENS_PER_MINUTE";
    /**
     * The free tier's tokens a minute, prompt and answer together: a request larger than this is refused
     * outright, so it is the token window every request must fit.
     */
    public static final int FREE_TOKENS_PER_MINUTE = 8000;
    /** The largest model Groq offers strict Structured Outputs for. */
    public static final String DEFAULT_MODEL = "openai/gpt-oss-120b";
    static final URI ENDPOINT = URI.create("https://api.groq.com/openai/v1/chat/completions");
    /**
     * Room for the answer: a score per matched paper and a reason per student come to well under this. The
     * free tier counts it, with the prompt, against 8,000 tokens a minute.
     */
    static final int MAX_ANSWER_TOKENS = 2500;

    private static final Logger LOG = Logger.getLogger(GroqClient.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_REPLY_BYTES = 8L * 1024 * 1024;

    private final JsonPoster poster;
    private final String apiKey;
    private final String model;
    private final ApiCalls.RetryPolicy retries;
    private final int tokensPerMinute;

    /**
     * @param poster the HTTP transport
     * @param apiKey a Groq API key
     * @param model  a Groq model id, e.g. {@value #DEFAULT_MODEL}
     */
    public GroqClient(JsonPoster poster, String apiKey, String model) {
        this(poster, apiKey, model, ApiCalls.RetryPolicy.DEFAULT);
    }

    /** As {@link #GroqClient(JsonPoster, String, String)}, retrying as given. */
    GroqClient(JsonPoster poster, String apiKey, String model, ApiCalls.RetryPolicy retries) {
        this(poster, apiKey, model, retries, FREE_TOKENS_PER_MINUTE);
    }

    /**
     * As {@link #GroqClient(JsonPoster, String, String, ApiCalls.RetryPolicy)}, for an account allowed
     * {@code tokensPerMinute}.
     */
    GroqClient(JsonPoster poster, String apiKey, String model, ApiCalls.RetryPolicy retries, int tokensPerMinute) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("A Groq API key is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (tokensPerMinute <= MAX_ANSWER_TOKENS) {
            throw new IllegalArgumentException("tokensPerMinute must leave room for a prompt besides the "
                    + MAX_ANSWER_TOKENS + "-token answer: " + tokensPerMinute);
        }
        this.poster = poster;
        this.apiKey = apiKey.strip();
        this.model = model.strip();
        this.retries = retries;
        this.tokensPerMinute = tokensPerMinute;
    }

    /**
     * A client with the key in {@value #KEY_VARIABLE}, the model in {@value #MODEL_VARIABLE} (default
     * {@value #DEFAULT_MODEL}), and the account's tokens a minute in {@value #TOKENS_PER_MINUTE_VARIABLE}
     * (default the free tier's {@value #FREE_TOKENS_PER_MINUTE}).
     *
     * @throws IllegalStateException if no key is set, or the tokens a minute are not a number
     */
    public static GroqClient fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static GroqClient fromEnvironment(Map<String, String> environment) {
        String key = environment.get(KEY_VARIABLE);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Set " + KEY_VARIABLE + " to a Groq API key "
                    + "(https://console.groq.com/keys) to match papers with Groq");
        }
        String chosen = environment.getOrDefault(MODEL_VARIABLE, "");
        String perMinute = environment.getOrDefault(TOKENS_PER_MINUTE_VARIABLE, "").strip();
        int tokensPerMinute;
        try {
            tokensPerMinute = perMinute.isEmpty() ? FREE_TOKENS_PER_MINUTE : Integer.parseInt(perMinute);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(TOKENS_PER_MINUTE_VARIABLE + " must be a number of tokens: " + perMinute);
        }
        return new GroqClient(new JdkJsonPoster(), key, chosen.isBlank() ? DEFAULT_MODEL : chosen,
                ApiCalls.RetryPolicy.DEFAULT, tokensPerMinute);
    }

    @Override
    public String modelId() {
        return model;
    }

    /** The account's tokens a minute, less the room kept for the answer ({@value #MAX_ANSWER_TOKENS}). */
    @Override
    public int maxPromptTokens() {
        return tokensPerMinute - MAX_ANSWER_TOKENS;
    }

    @Override
    public JsonNode generateJson(String prompt, ObjectNode responseSchema) throws IOException {
        HttpReply reply = ApiCalls.post(poster, ENDPOINT, Map.of("Authorization", "Bearer " + apiKey),
                requestBody(model, prompt, responseSchema), MAX_REPLY_BYTES, retries, GroqClient::askedWait, "Groq");
        return answer(JSON.readTree(reply.body()));
    }

    static byte[] requestBody(String model, String prompt, ObjectNode responseSchema) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode schema = format.putObject("json_schema");
        schema.put("name", "answer");
        schema.put("strict", true);
        schema.set("schema", strictSchema(responseSchema));
        body.put("reasoning_effort", "low");
        body.put("include_reasoning", false);
        body.put("max_completion_tokens", MAX_ANSWER_TOKENS);
        return JSON.writeValueAsBytes(body);
    }

    /**
     * The schema as strict mode accepts it: every object closed and all its properties required, the
     * optional ones nullable; {@code minimum} and {@code maximum} dropped. The given schema is not changed.
     */
    static ObjectNode strictSchema(ObjectNode schema) {
        ObjectNode copy = schema.deepCopy();
        tighten(copy);
        return copy;
    }

    private static void tighten(ObjectNode node) {
        node.remove(List.of("minimum", "maximum"));
        String type = node.path("type").asText("");
        if (type.equals("object")) {
            Set<String> required = new HashSet<>();
            node.path("required").forEach(field -> required.add(field.asText()));
            List<String> fields = new ArrayList<>();
            node.path("properties").fieldNames().forEachRemaining(fields::add);
            ArrayNode all = node.putArray("required");
            for (String field : fields) {
                ObjectNode property = (ObjectNode) node.path("properties").path(field);
                tighten(property);
                if (!required.contains(field)) {
                    nullable(property);
                }
                all.add(field);
            }
            node.put("additionalProperties", false);
        } else if (type.equals("array") && node.path("items").isObject()) {
            tighten((ObjectNode) node.path("items"));
        }
    }

    private static void nullable(ObjectNode property) {
        property.set("type", JSON.createArrayNode().add(property.path("type").asText()).add("null"));
        if (property.path("enum").isArray()) {
            ((ArrayNode) property.path("enum")).addNull();
        }
    }

    /** The wait Groq names in its error ("Please try again in 7.66s"), if any. */
    static Optional<Duration> askedWait(HttpReply reply) {
        try {
            return ApiCalls.tryAgainIn(JSON.readTree(reply.body()).path("error").path("message").asText(""));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The JSON the model wrote: the content of the first choice's message.
     *
     * @throws IOException if the answer was cut short or is missing, or the text is not JSON
     */
    static JsonNode answer(JsonNode response) throws IOException {
        JsonNode choice = response.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new IOException("Groq returned no answer");
        }
        String finish = choice.path("finish_reason").asText("stop");
        if (!finish.equals("stop")) {
            throw new IOException("Groq's answer is unusable: finish_reason " + finish);
        }
        JsonNode usage = response.path("usage");
        if (!usage.isMissingNode()) {
            LOG.info("Groq " + response.path("model").asText("") + ": " + usage.path("prompt_tokens").asInt()
                    + " prompt tokens, " + usage.path("completion_tokens").asInt() + " answer tokens");
        }
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IOException("Groq's answer has no text");
        }
        return JSON.readTree(content);
    }
}
