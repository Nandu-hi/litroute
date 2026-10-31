package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.ingestion.enrich.HttpReply;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The Ollama seam, offline: requests are checked as they would be sent, and replies are in the form Ollama sends them. */
public class OllamaClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpReply reply(int status, String body) {
        return new HttpReply(status, "application/json", body.getBytes(StandardCharsets.UTF_8),
                URI.create(OllamaClient.DEFAULT_URL), false);
    }

    private static JsonNode schema() throws IOException {
        return JSON.readTree("{\"type\":\"object\"}");
    }

    @Test
    public void theRequestAsksForTheSchemaWithoutRandomness() throws IOException {
        List<URI> uris = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        OllamaClient client = RecordedOllama.client((uri, body) -> {
            uris.add(uri);
            bodies.add(body);
            return reply(200, "{\"message\":{\"content\":\"{}\"},\"done_reason\":\"stop\"}");
        });

        client.generateJson("Name a colour.", schema(), 64, 42);

        assertEquals("http://localhost:11434/api/chat", uris.get(0).toString());
        JsonNode request = JSON.readTree(bodies.get(0));
        assertEquals(OllamaClient.DEFAULT_MODEL, request.path("model").asText());
        assertFalse(request.path("stream").asBoolean(true));
        assertEquals(schema(), request.path("format"));
        assertEquals(0, request.at("/options/temperature").asInt(-1));
        assertEquals(42, request.at("/options/seed").asInt());
        assertEquals(OllamaClient.CONTEXT_TOKENS, request.at("/options/num_ctx").asInt());
        assertEquals(64, request.at("/options/num_predict").asInt());
        assertEquals("Name a colour.", request.at("/messages/0/content").asText());
    }

    @Test
    public void anAnswerStoppedAtTheBudgetIsMarkedIncomplete() throws IOException {
        OllamaClient finished = RecordedOllama.client((uri, body) ->
                reply(200, "{\"message\":{\"content\":\"{\\\"titles\\\":[]}\"},\"done_reason\":\"stop\"}"));
        OllamaClient cut = RecordedOllama.client((uri, body) ->
                reply(200, "{\"message\":{\"content\":\"{\\\"titles\\\":[\\\"Wave\"},\"done_reason\":\"length\"}"));

        assertEquals(new OllamaClient.Answer("{\"titles\":[]}", true), finished.generateJson("p", schema(), 8, 1));
        assertFalse(cut.generateJson("p", schema(), 8, 1).complete());
    }

    @Test
    public void aMissingModelSaysHowToGetIt() {
        OllamaClient client = RecordedOllama.client((uri, body) ->
                reply(404, "{\"error\":\"model 'llama3.2:3b' not found\"}"));

        IOException e = assertThrows(IOException.class, () -> client.generateJson("p", schema(), 8, 1));

        assertTrue(e.getMessage(), e.getMessage().contains("ollama pull llama3.2:3b"));
    }

    @Test
    public void aStoppedServerSaysHowToStartIt() {
        OllamaClient client = RecordedOllama.client((uri, body) -> {
            throw new ConnectException();
        });

        IOException e = assertThrows(IOException.class, () -> client.generateJson("p", schema(), 8, 1));

        assertTrue(e.getMessage(), e.getMessage().contains("ollama serve"));
    }

    @Test
    public void anUnreadableOrEmptyReplyIsAnError() {
        assertThrows(IOException.class, () -> RecordedOllama.client((uri, body) -> reply(200, "<html>"))
                .generateJson("p", schema(), 8, 1));
        assertThrows(IOException.class, () -> RecordedOllama.client((uri, body) -> reply(200, "{\"done\":true}"))
                .generateJson("p", schema(), 8, 1));
    }

    @Test
    public void onlyThisMachineIsAccepted() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaClient.fromEnvironment(Map.of(OllamaClient.URL_VARIABLE, "https://api.example.org")));
        OllamaClient.fromEnvironment(Map.of(OllamaClient.URL_VARIABLE, "http://127.0.0.1:11500/"));
    }

    @Test
    public void theEnvironmentPicksTheModel() {
        assertEquals(OllamaClient.DEFAULT_MODEL, OllamaClient.fromEnvironment(Map.of()).modelId());
        assertEquals(OllamaClient.DEFAULT_MODEL,
                OllamaClient.fromEnvironment(Map.of(OllamaClient.MODEL_VARIABLE, "  ")).modelId());
        assertEquals("llama3.2-14t",
                OllamaClient.fromEnvironment(Map.of(OllamaClient.MODEL_VARIABLE, "llama3.2-14t")).modelId());
    }
}
