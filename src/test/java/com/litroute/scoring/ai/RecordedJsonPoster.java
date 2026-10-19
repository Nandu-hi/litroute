package com.litroute.scoring.ai;

import com.litroute.ingestion.enrich.HttpReply;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replays a reply a language model API really gave ({@link AiRecorder} saved it under src/test/resources/ai/),
 * so the tests exercise a genuine answer - its real envelope, its real choices - while staying offline
 * and deterministic. It can also answer with an error status, as the APIs do when they throttle us.
 */
final class RecordedJsonPoster implements JsonPoster {

    static final Path RECORDINGS = Path.of("src/test/resources/ai");
    static final Path MATCH_REQUEST = RECORDINGS.resolve("groq-match-request.json");
    static final Path MATCH_REPLY = RECORDINGS.resolve("groq-match-reply.json");

    record Request(URI uri, Map<String, String> headers, byte[] body) {
    }

    /** Every request posted, in order. */
    final List<Request> requests = new ArrayList<>();

    private record Reply(int status, byte[] body) {
    }

    private final List<Reply> replies = new ArrayList<>();

    RecordedJsonPoster(int status, byte[] reply) {
        replies.add(new Reply(status, reply));
    }

    /**
     * Answers the next request with this status and body; the last reply set answers every request after it.
     * Real outages come in bursts: a 503 or two, then the answer.
     */
    RecordedJsonPoster thenReplying(int status, byte[] reply) {
        replies.add(new Reply(status, reply));
        return this;
    }

    /** Groq's recorded answer to the client's sample alerts, byte for byte. */
    static byte[] matchReply() throws IOException {
        if (!Files.exists(MATCH_REPLY)) {
            throw new AssertionError("No recorded Groq reply in " + MATCH_REPLY + ": run AiRecorder once with "
                    + GroqClient.KEY_VARIABLE + " set (see src/main/java/com/litroute/scoring/readme.md)");
        }
        return Files.readAllBytes(MATCH_REPLY);
    }

    @Override
    public HttpReply post(URI uri, Map<String, String> headers, byte[] body, long maxBytes) {
        requests.add(new Request(uri, Map.copyOf(headers), body.clone()));
        Reply reply = replies.get(Math.min(requests.size(), replies.size()) - 1);
        return new HttpReply(reply.status(), "application/json; charset=UTF-8", reply.body(), uri, false);
    }
}
