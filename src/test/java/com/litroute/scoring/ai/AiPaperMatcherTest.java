package com.litroute.scoring.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.store.ProfileStore;
import com.litroute.testing.ClientAlerts;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * M6 by a language model, offline, on real data only: the students' stored profiles (M7d), the client's
 * sample alert papers run through M2-M4, and the answer Groq really gave to them ({@link AiRecorder}).
 */
public class AiPaperMatcherTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Collection<Profile> profiles;
    private static List<PaperCandidate> batch;

    @BeforeClass
    public static void loadRealData() throws IOException {
        profiles = ProfileStore.atDefaultLocation().loadAll().values();
        batch = ClientAlerts.deduplicated(Files.createTempDirectory("litroute-ai-test"));
    }

    private static String recordedModel() throws IOException {
        RecordedJsonPoster.matchReply(); // fails with instructions if nothing was recorded
        return JSON.readTree(RecordedJsonPoster.MATCH_REQUEST.toFile()).path("model").asText();
    }

    private static Map<String, List<ScoredPaper>> matchReplaying(RecordedJsonPoster poster) throws IOException {
        return AiPaperMatcher.of(new GroqClient(poster, "test-key", recordedModel()), profiles).match(batch);
    }

    private static Map<String, List<ScoredPaper>> matchReplaying(byte[] reply) throws IOException {
        return matchReplaying(new RecordedJsonPoster(200, reply));
    }

    private static Map<String, List<ScoredPaper>> recordedMatches() throws IOException {
        return matchReplaying(RecordedJsonPoster.matchReply());
    }

    /** The recorded reply with the model's answer (the JSON text inside its envelope) changed. */
    private static byte[] recordedAnswerWith(Consumer<ObjectNode> change) throws IOException {
        ObjectNode reply = (ObjectNode) JSON.readTree(RecordedJsonPoster.matchReply());
        ObjectNode message = (ObjectNode) reply.at("/choices/0/message");
        ObjectNode answer = (ObjectNode) JSON.readTree(message.path("content").asText());
        change.accept(answer);
        message.put("content", JSON.writeValueAsString(answer));
        return JSON.writeValueAsBytes(reply);
    }

    @Test
    public void everyStudentIsListedInProfileOrderWithTheirPapersBestFirst() throws IOException {
        Map<String, List<ScoredPaper>> matches = recordedMatches();

        assertEquals(profiles.stream().map(Profile::name).toList(), new ArrayList<>(matches.keySet()));
        Set<PaperCandidate> sent = new HashSet<>(batch);
        matches.forEach((student, papers) -> {
            Set<PaperCandidate> distinct = new HashSet<>();
            for (int i = 0; i < papers.size(); i++) {
                ScoredPaper paper = papers.get(i);
                assertEquals(student, paper.profileName());
                assertTrue(paper.candidate().title(), sent.contains(paper.candidate()));
                assertTrue("listed once", distinct.add(paper.candidate()));
                assertTrue(paper.score() >= 0 && paper.score() <= 1);
                assertEquals(ReviewStatus.PENDING, paper.status());
                if (i > 0) {
                    assertTrue(student + ": best first", papers.get(i - 1).score() >= paper.score());
                }
            }
        });
        assertTrue("the model matched something", matches.values().stream().anyMatch(papers -> !papers.isEmpty()));
    }

    /** What the professor asked of the AI: for each student, the one paper to focus on, and why in one line. */
    @Test
    public void eachStudentWithMatchesHasOneFocusPaperWithAOneLineReason() throws IOException {
        recordedMatches().forEach((student, papers) -> {
            List<ScoredPaper> focus = papers.stream().filter(ScoredPaper::recommended).toList();
            assertEquals(student, papers.isEmpty() ? 0 : 1, focus.size());
            for (ScoredPaper paper : focus) {
                assertFalse(student, paper.rationale().isBlank());
                assertFalse(paper.rationale(), paper.rationale().contains("\n"));
            }
        });
    }

    @Test
    public void thePromptNamesEveryStudentAndEveryPaper() throws IOException {
        RecordedJsonPoster poster = new RecordedJsonPoster(200, RecordedJsonPoster.matchReply());
        matchReplaying(poster);

        JsonNode body = JSON.readTree(poster.requests.get(0).body());
        String prompt = body.at("/messages/0/content").asText();
        JsonNode schema = body.at("/response_format/json_schema/schema");
        for (Profile profile : profiles) {
            assertTrue(profile.name(), prompt.contains("\n## " + profile.name() + "\n"));
            assertFalse(profile.name() + " has keywords", profile.keywords().isEmpty());
            assertTrue(profile.name(), prompt.contains("\n## " + profile.name() + "\nKeywords: "
                    + String.join("; ", profile.keywords()) + "\n"));
        }
        for (int i = 0; i < batch.size(); i++) {
            assertTrue(prompt.contains("\n## P" + (i + 1) + "\nTitle: " + MatchPrompt.oneLine(batch.get(i).title())));
        }
        JsonNode paperIds = schema.at("/properties/profiles/items/properties/matches/items/properties/paper_id/enum");
        assertEquals(batch.size(), paperIds.size());
    }

    /** A paper the prompt never named, or a student who doesn't exist, cannot end up in the queue. */
    @Test
    public void unknownStudentsAndPaperIdsInTheAnswerAreDropped() throws IOException {
        byte[] invented = recordedAnswerWith(answer -> {
            ArrayNode students = (ArrayNode) answer.path("profiles");
            ((ArrayNode) students.path(0).path("matches")).addObject().put("paper_id", "P" + (batch.size() + 1))
                    .put("score", 99);
            ObjectNode nobody = students.addObject().put("profile", "RP9-nobody");
            nobody.putArray("matches").addObject().put("paper_id", "P1").put("score", 90);
        });

        assertEquals(recordedMatches(), matchReplaying(invented));
    }

    @Test
    public void aFocusPaperTheStudentWasNotMatchedWithGivesWayToTheirBestMatch() throws IOException {
        Map<String, List<ScoredPaper>> recorded = recordedMatches();
        String student = recorded.entrySet().stream().filter(entry -> entry.getValue().size() < batch.size()
                && !entry.getValue().isEmpty()).map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new AssertionError("no student with some but not all papers matched"));
        Set<PaperCandidate> matched = recorded.get(student).stream().map(ScoredPaper::candidate).collect(Collectors.toSet());
        int unmatched = 0;
        while (matched.contains(batch.get(unmatched))) {
            unmatched++;
        }
        String unmatchedId = MatchPrompt.paperId(unmatched);
        byte[] stray = recordedAnswerWith(answer -> {
            for (JsonNode entry : answer.path("profiles")) {
                if (entry.path("profile").asText().equals(student)) {
                    ((ObjectNode) entry).putObject("focus").put("paper_id", unmatchedId).put("reason", "Read it first.");
                }
            }
        });

        List<ScoredPaper> papers = matchReplaying(stray).get(student);

        assertTrue(papers.get(0).recommended());
        assertEquals(1, papers.stream().filter(ScoredPaper::recommended).count());
        assertTrue(papers.get(0).rationale(), papers.get(0).rationale().contains("highest of its matches"));
    }

    /** The client's sample batch fits Groq's free token window, so it goes in one request. */
    @Test
    public void aBatchThatFitsTheTokenWindowIsSentInOneRequest() throws IOException {
        RecordedJsonPoster poster = new RecordedJsonPoster(200, RecordedJsonPoster.matchReply());
        matchReplaying(poster);

        assertEquals(1, poster.requests.size());
        assertTrue(MatchPrompt.estimateTokens(prompt(poster.requests.get(0))) <= new GroqClient(poster, "test-key",
                recordedModel()).maxPromptTokens());
    }

    /**
     * A batch too long for the token window goes in as few requests as fit, each within it, between them
     * naming every paper once and in batch order; each student still has one focus paper.
     */
    @Test
    public void aBatchTooLongForTheTokenWindowIsSplitIntoRequestsThatFit() throws IOException {
        List<ProfileBrief> briefs = profiles.stream().map(ProfileBrief::from).toList();
        int students = MatchPrompt.estimateTokens(MatchPrompt.text(briefs, List.of()));
        int paperTokens = MatchPrompt.estimateTokens(MatchPrompt.text(briefs, batch)) - students;
        // An account whose token window holds, besides the answer, every student and 60% of the papers.
        RecordedJsonPoster poster = new RecordedJsonPoster(200, RecordedJsonPoster.matchReply());
        GroqClient small = new GroqClient(poster, "test-key", recordedModel(), ApiCalls.RetryPolicy.DEFAULT,
                students + paperTokens * 6 / 10 + GroqClient.MAX_ANSWER_TOKENS);

        Map<String, List<ScoredPaper>> matches = AiPaperMatcher.of(small, profiles).match(batch);

        assertEquals(2, poster.requests.size());
        List<String> sent = new ArrayList<>();
        for (RecordedJsonPoster.Request request : poster.requests) {
            String prompt = prompt(request);
            assertTrue(MatchPrompt.estimateTokens(prompt) + " tokens", MatchPrompt.estimateTokens(prompt) <= small.maxPromptTokens());
            for (Profile profile : profiles) {
                assertTrue(profile.name(), prompt.contains("\n## " + profile.name() + "\n"));
            }
            int named = JSON.readTree(request.body())
                    .at("/response_format/json_schema/schema/properties/profiles/items/properties/matches/items/properties/paper_id/enum")
                    .size();
            for (int i = 0; i < named; i++) {
                int at = prompt.indexOf("\n## P" + (i + 1) + "\nTitle: ");
                assertTrue(at >= 0);
                sent.add(prompt.substring(at).split("\n")[2].substring("Title: ".length()));
            }
        }
        assertEquals(batch.stream().map(paper -> MatchPrompt.oneLine(paper.title())).toList(), sent);
        Set<PaperCandidate> inBatch = new HashSet<>(batch);
        matches.forEach((student, papers) -> {
            assertTrue(papers.stream().allMatch(paper -> inBatch.contains(paper.candidate())));
            assertEquals(student, papers.isEmpty() ? 0 : 1, papers.stream().filter(ScoredPaper::recommended).count());
        });
    }

    private static String prompt(RecordedJsonPoster.Request request) throws IOException {
        return JSON.readTree(request.body()).at("/messages/0/content").asText();
    }

    @Test
    public void anEmptyBatchAsksTheModelNothing() throws IOException {
        RecordedJsonPoster poster = new RecordedJsonPoster(200, new byte[0]);
        Map<String, List<ScoredPaper>> matches = AiPaperMatcher.of(new GroqClient(poster, "test-key", recordedModel()),
                profiles).match(List.of());

        assertTrue(poster.requests.isEmpty());
        assertEquals(profiles.size(), matches.size());
        assertTrue(matches.values().stream().allMatch(List::isEmpty));
    }

    /**
     * The prompt stays short: each student is told about their few most typical papers, not all of them,
     * and the keywords M5 kept for them.
     */
    @Test
    public void eachStudentIsDescribedByTheirMostTypicalPapers() {
        for (ProfileBrief brief : AiPaperMatcher.of(new GroqClient(new RecordedJsonPoster(200, new byte[0]), "test-key",
                GroqClient.DEFAULT_MODEL), profiles).profiles()) {
            assertFalse(brief.name(), brief.paperTitles().isEmpty());
            assertFalse(brief.name(), brief.keywords().isEmpty());
            assertTrue(brief.name(), brief.paperTitles().size() <= ProfileBrief.MAX_TITLES);
        }
    }
}
