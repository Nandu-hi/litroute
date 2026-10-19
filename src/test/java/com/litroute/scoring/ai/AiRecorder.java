package com.litroute.scoring.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litroute.ingestion.enrich.HttpReply;
import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.ScoredPaper;
import com.litroute.store.ProfileStore;
import com.litroute.testing.ClientAlerts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records the Groq reply the AI tests replay: the students' stored profiles (M7d) and the client's sample
 * alert papers, sent once for real. Run by hand, from the project folder, whenever the prompt or the
 * profiles change:
 *
 * <pre>
 * GROQ_API_KEY=... mvn -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.litroute.scoring.ai.AiRecorder
 * </pre>
 *
 * With {@code -Dexec.args=--dry-run} it prints the prompt and sends nothing. Otherwise it saves the request (without the key) to {@code groq-match-request.json} and the reply, byte for byte, to
 * {@code groq-match-reply.json} under src/test/resources/ai/, then prints what the matcher made of it.
 * Only a successful reply is saved: a throttled request is retried by {@link GroqClient}, and an error
 * leaves an earlier recording as it was.
 */
public final class AiRecorder {

    private AiRecorder() {
    }

    public static void main(String[] args) throws Exception {
        Collection<Profile> profiles = ProfileStore.atDefaultLocation().loadAll().values();
        if (profiles.isEmpty()) {
            System.out.println("No profiles in M7d: build them first with ProfileBuilderCli.");
            return;
        }
        List<PaperCandidate> batch = ClientAlerts.deduplicated(Files.createTempDirectory("litroute-ai"));
        String prompt = MatchPrompt.text(profiles.stream().map(ProfileBrief::from).toList(), batch);
        System.out.printf("%d papers for %d profiles; the prompt is %,d characters%n", batch.size(), profiles.size(),
                prompt.length());
        if (List.of(args).contains("--dry-run")) {
            System.out.println(prompt);
            return; // the prompt only: nothing is sent
        }
        String model = GroqClient.fromEnvironment().modelId();
        ObjectMapper json = new ObjectMapper();
        JdkJsonPoster http = new JdkJsonPoster();
        JsonPoster recording = (uri, headers, body, maxBytes) -> {
            HttpReply reply = http.post(uri, headers, body, maxBytes);
            if (!reply.ok()) {
                System.out.printf("HTTP %d: %s%n", reply.status(), new String(reply.body(), StandardCharsets.UTF_8).strip());
                return reply;
            }
            ObjectNode request = json.createObjectNode();
            request.put("model", model);
            request.put("recordedAt", Instant.now().toString());
            request.set("body", json.readTree(body));
            Files.createDirectories(RecordedJsonPoster.RECORDINGS);
            Files.write(RecordedJsonPoster.MATCH_REQUEST, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(request));
            Files.write(RecordedJsonPoster.MATCH_REPLY, reply.body());
            System.out.printf("HTTP %d; saved %s and %s%n", reply.status(), RecordedJsonPoster.MATCH_REQUEST,
                    RecordedJsonPoster.MATCH_REPLY);
            return reply;
        };
        GroqClient groq = new GroqClient(recording, System.getenv(GroqClient.KEY_VARIABLE), model);
        System.out.printf("Asking %s...%n", model);

        Map<String, List<ScoredPaper>> matches = AiPaperMatcher.of(groq, profiles).match(batch);

        matches.forEach((student, papers) -> {
            System.out.printf("%n%s: %d matched%n", student, papers.size());
            for (ScoredPaper paper : papers) {
                System.out.printf(Locale.ROOT, "  %s %.2f  %s%n", paper.recommended() ? "*" : " ", paper.score(),
                        paper.candidate().title());
                if (paper.recommended()) {
                    System.out.println("           " + paper.rationale());
                }
            }
        });
    }
}
