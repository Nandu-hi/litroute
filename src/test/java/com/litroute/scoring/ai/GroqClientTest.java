package com.litroute.scoring.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * The Groq API seam, offline: requests are checked as they would be sent, and the replies are the one Groq
 * really gave to the client's sample alerts ({@link AiRecorder}), or that reply as the API returns it when
 * an answer is cut short, and rate-limit errors in the form Groq sends them.
 */
public class GroqClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "test-key-not-a-real-one";
    /** The pauses the client asked for, in order; recorded instead of slept, so the tests don't wait. */
    private final List<Duration> pauses = new ArrayList<>();

    private static ObjectNode schema() {
        return MatchPrompt.schema(List.of(new ProfileBrief("RP1-mh-risk", List.of(), List.of())), 2);
    }

    /** A client retrying as it does for real, except that its pauses are recorded instead of slept. */
    private GroqClient client(RecordedJsonPoster poster) {
        ApiCalls.RetryPolicy real = ApiCalls.RetryPolicy.DEFAULT;
        return new GroqClient(poster, KEY, GroqClient.DEFAULT_MODEL,
                new ApiCalls.RetryPolicy(real.delays(), real.longestWait(), pauses::add));
    }

    /** A rate-limit error as Groq words it, for the given limit and wait. */
    private static byte[] rateLimited(String limit, String wait) {
        return ("{\"error\": {\"message\": \"Rate limit reached for model `openai/gpt-oss-120b` in organization "
                + "`org_x` service tier `on_demand` on " + limit + ". Please try again in " + wait + ". Need more "
                + "tokens? Upgrade to Dev Tier today at https://console.groq.com/settings/billing\", \"type\": \"tokens\", "
                + "\"code\": \"rate_limit_exceeded\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] recordedReplyWith(Consumer<ObjectNode> change) throws IOException {
        ObjectNode reply = (ObjectNode) JSON.readTree(RecordedJsonPoster.matchReply());
        change.accept(reply);
        return JSON.writeValueAsBytes(reply);
    }

    @Test
    public void theRequestAsksForStrictJsonWithTheKeyOnlyInAHeader() throws IOException {
        RecordedJsonPoster poster = new RecordedJsonPoster(200, RecordedJsonPoster.matchReply());

        client(poster).generateJson("Which papers suit which student?", schema());

        RecordedJsonPoster.Request request = poster.requests.get(0);
        assertEquals("https://api.groq.com/openai/v1/chat/completions", request.uri().toString());
        assertEquals(Map.of("Authorization", "Bearer " + KEY), request.headers());
        JsonNode body = JSON.readTree(request.body());
        assertEquals(GroqClient.DEFAULT_MODEL, body.path("model").asText());
        assertEquals("user", body.at("/messages/0/role").asText());
        assertEquals("Which papers suit which student?", body.at("/messages/0/content").asText());
        assertEquals("json_schema", body.at("/response_format/type").asText());
        assertTrue(body.at("/response_format/json_schema/strict").asBoolean());
        assertEquals(GroqClient.strictSchema(schema()), body.at("/response_format/json_schema/schema"));
        assertEquals("low", body.path("reasoning_effort").asText());
        assertFalse(body.path("include_reasoning").asBoolean(true));
        assertEquals(GroqClient.MAX_ANSWER_TOKENS, body.path("max_completion_tokens").asInt());
    }

    /** Strict mode's rules: every object closed, every property required, the optional ones nullable. */
    @Test
    public void theSchemaIsRewrittenForStrictMode() {
        ObjectNode original = schema();
        ObjectNode strict = GroqClient.strictSchema(original);

        assertEquals("the matcher's schema is left as it was", schema(), original);
        List<JsonNode> objects = new ArrayList<>();
        collectObjects(strict, objects);
        assertEquals("root, student, match and focus", 4, objects.size());
        for (JsonNode object : objects) {
            assertFalse(object.path("additionalProperties").asBoolean(true));
            List<String> fields = new ArrayList<>();
            object.path("properties").fieldNames().forEachRemaining(fields::add);
            List<String> required = new ArrayList<>();
            object.path("required").forEach(field -> required.add(field.asText()));
            assertEquals(fields, required);
        }
        JsonNode focus = strict.at("/properties/profiles/items/properties/focus");
        assertEquals("[\"object\",\"null\"]", focus.path("type").toString());
        assertTrue(strict.at("/properties/profiles/items/properties/matches/items/properties/score/minimum").isMissingNode());
    }

    private static void collectObjects(JsonNode node, List<JsonNode> objects) {
        if (node.path("properties").isObject()) {
            objects.add(node);
            node.path("properties").forEach(child -> collectObjects(child, objects));
        }
        if (node.path("items").isObject()) {
            collectObjects(node.path("items"), objects);
        }
    }

    @Test
    public void theRecordedAnswerComesBackAsJson() throws IOException {
        JsonNode answer = client(new RecordedJsonPoster(200, RecordedJsonPoster.matchReply())).generateJson("...", schema());

        assertTrue(answer.toString(), answer.path("profiles").isArray());
        assertFalse(answer.path("profiles").isEmpty());
    }

    /** The per-minute limit is waited out: the same request is sent again and its answer used. */
    @Test
    public void aRateLimitIsWaitedOut() throws IOException {
        RecordedJsonPoster poster = new RecordedJsonPoster(429, rateLimited("tokens per minute (TPM): Limit 8000, "
                + "Used 6100, Requested 5200", "25.5s")).thenReplying(503, new byte[0])
                .thenReplying(200, RecordedJsonPoster.matchReply());

        assertTrue(client(poster).generateJson("...", schema()).path("profiles").isArray());
        assertEquals(3, poster.requests.size());
        assertEquals("Groq's wait, then the second pause", List.of(Duration.ofMillis(25500), Duration.ofSeconds(30)),
                pauses);
    }

    /** A used-up daily limit names hours: waiting here won't help, so it is reported at once. */
    @Test
    public void aUsedUpDailyLimitIsReportedAtOnce() {
        RecordedJsonPoster poster = new RecordedJsonPoster(429, rateLimited("tokens per day (TPD): Limit 200000, "
                + "Used 199000, Requested 5200", "2h13m5.5s"));
        try {
            client(poster).generateJson("...", schema());
            fail("a used-up daily limit was taken for an answer");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("tokens per day"));
            assertTrue(e.getMessage(), e.getMessage().contains("not retried"));
            assertEquals(1, poster.requests.size());
            assertTrue("nothing waited", pauses.isEmpty());
        }
    }

    @Test
    public void theWaitIsReadFromGroqsMessage() {
        assertEquals(Optional.of(Duration.ofMillis(7660)), ApiCalls.tryAgainIn("Please try again in 7.66s. Need more"));
        assertEquals(Optional.of(Duration.ofMinutes(2).plusMillis(59500)), ApiCalls.tryAgainIn("try again in 2m59.5s."));
        assertEquals(Optional.of(Duration.ofHours(2).plusMinutes(13).plusMillis(5500)),
                ApiCalls.tryAgainIn("Please try again in 2h13m5.5s."));
        assertEquals(Optional.of(Duration.ofMillis(450)), ApiCalls.tryAgainIn("Please try again in 450ms."));
        assertEquals(Optional.empty(), ApiCalls.tryAgainIn("Service unavailable"));
    }

    @Test
    public void aLastingOutageIsAnErrorNotAnEmptyAnswer() {
        RecordedJsonPoster poster = new RecordedJsonPoster(503, new byte[0]);
        try {
            client(poster).generateJson("...", schema());
            fail("HTTP 503 was taken for an answer");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Groq unavailable: HTTP 503"));
            assertEquals(1 + ApiCalls.RetryPolicy.DEFAULT.delays().size(), poster.requests.size());
            assertEquals(ApiCalls.RetryPolicy.DEFAULT.delays(), pauses);
        }
    }

    /** A bad or too large request won't get better by asking again. */
    @Test
    public void aRefusedRequestIsAnErrorAndNotRetried() {
        for (int status : List.of(400, 413)) {
            RecordedJsonPoster poster = new RecordedJsonPoster(status, new byte[0]);
            try {
                client(poster).generateJson("...", schema());
                fail("HTTP " + status + " was taken for an answer");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("refused the request: HTTP " + status));
                assertEquals(1, poster.requests.size());
            }
        }
    }

    @Test
    public void anAnswerCutShortIsAnError() throws IOException {
        byte[] cut = recordedReplyWith(reply -> ((ObjectNode) reply.at("/choices/0")).put("finish_reason", "length"));
        try {
            client(new RecordedJsonPoster(200, cut)).generateJson("...", schema());
            fail("a truncated answer was taken for an answer");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("length"));
        }
    }

    @Test
    public void keyAndModelComeFromTheEnvironment() {
        try {
            GroqClient.fromEnvironment(Map.of());
            fail("a client was made without a key");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(GroqClient.KEY_VARIABLE));
        }
        assertEquals(GroqClient.DEFAULT_MODEL, GroqClient.fromEnvironment(Map.of(GroqClient.KEY_VARIABLE, KEY)).modelId());
        assertEquals("openai/gpt-oss-20b", GroqClient.fromEnvironment(Map.of(GroqClient.KEY_VARIABLE, KEY,
                GroqClient.MODEL_VARIABLE, "openai/gpt-oss-20b")).modelId());
    }

    /** A prompt may take what the answer leaves of the tokens a minute: the free tier's, or the account's. */
    @Test
    public void thePromptBudgetIsTheTokensAMinuteLessTheAnswer() {
        assertEquals(GroqClient.FREE_TOKENS_PER_MINUTE - GroqClient.MAX_ANSWER_TOKENS,
                GroqClient.fromEnvironment(Map.of(GroqClient.KEY_VARIABLE, KEY)).maxPromptTokens());
        assertEquals(30000 - GroqClient.MAX_ANSWER_TOKENS, GroqClient.fromEnvironment(Map.of(GroqClient.KEY_VARIABLE, KEY,
                GroqClient.TOKENS_PER_MINUTE_VARIABLE, "30000")).maxPromptTokens());
        try {
            GroqClient.fromEnvironment(Map.of(GroqClient.KEY_VARIABLE, KEY, GroqClient.TOKENS_PER_MINUTE_VARIABLE, "lots"));
            fail("a client was made with no number of tokens");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(GroqClient.TOKENS_PER_MINUTE_VARIABLE));
        }
    }
}
