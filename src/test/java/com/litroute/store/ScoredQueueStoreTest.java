package com.litroute.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.Test;

public class ScoredQueueStoreTest {

    private static ScoredPaper scored(PaperCandidate candidate, String profile, double score) {
        return new ScoredPaper(candidate, profile, score, "Closest in " + profile + ": ...", ReviewStatus.PENDING);
    }

    /** Many distinct queue keys for the concurrency test: bare titles with no DOI, not real papers. */
    private static PaperCandidate queueKeyOnly(int n) {
        return new PaperCandidate("<queue-test@mail>", "Queue entry " + n, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Path newFile() throws IOException {
        return Files.createTempDirectory("litroute-test").resolve("nested").resolve("scored_queue.json");
    }

    @Test
    public void loadReturnsEmptyQueueWhenNothingQueuedYet() throws IOException {
        assertTrue(new ScoredQueueStore(newFile()).load().isEmpty());
    }

    /** Design-doc check: statuses changed to APPROVED and DROPPED survive a save and reload. */
    @Test
    public void reviewStatusesLoadBackAccurately() throws IOException {
        Path file = newFile();
        ScoredQueueStore store = new ScoredQueueStore(file);
        ScoredPaper approved = scored(Candidates.full(), "RP1-mh-risk", 0.97);
        ScoredPaper dropped = scored(Candidates.sparse(), "RP4-mh-char", 0.12);
        ScoredPaper pending = scored(Candidates.full(), "RP4-mh-char", 0.81);
        store.addAll(List.of(approved, dropped, pending));

        assertTrue(store.setStatus(approved, ReviewStatus.APPROVED));
        assertTrue(store.setStatus(dropped, ReviewStatus.DROPPED));

        List<ScoredPaper> reloaded = new ScoredQueueStore(file).load();
        assertEquals(List.of(approved.withStatus(ReviewStatus.APPROVED), dropped.withStatus(ReviewStatus.DROPPED), pending),
                reloaded);
    }

    @Test
    public void rescoringAQueuedPaperKeepsTheProfessorsDecision() throws IOException {
        ScoredQueueStore store = new ScoredQueueStore(newFile());
        ScoredPaper first = scored(Candidates.full(), "RP1-mh-risk", 0.60);
        store.addAll(List.of(first, scored(Candidates.sparse(), "RP1-mh-risk", 0.55)));
        store.setStatus(first, ReviewStatus.APPROVED);

        ScoredPaper rescored = scored(Candidates.full(), "RP1-mh-risk", 0.92);
        List<ScoredPaper> queue = store.addAll(List.of(rescored));

        assertEquals(2, queue.size());
        assertEquals(rescored.withStatus(ReviewStatus.APPROVED), queue.get(0));
        assertEquals(queue, store.load());
    }

    @Test
    public void samePaperForAnotherStudentIsQueuedSeparately() throws IOException {
        ScoredQueueStore store = new ScoredQueueStore(newFile());

        List<ScoredPaper> queue = store.addAll(List.of(scored(Candidates.full(), "RP1-mh-risk", 0.9),
                scored(Candidates.full(), "RP4-mh-char", 0.8)));

        assertEquals(2, queue.size());
    }

    @Test
    public void unknownPaperCannotChangeStatus() throws IOException {
        ScoredQueueStore store = new ScoredQueueStore(newFile());
        store.addAll(List.of(scored(Candidates.full(), "RP1-mh-risk", 0.9)));

        assertFalse(store.setStatus(scored(Candidates.sparse(), "RP1-mh-risk", 0.9), ReviewStatus.APPROVED));
    }

    /**
     * Design-doc check: the GUI changing statuses while the pipeline queues new papers neither
     * corrupts the file nor loses either side's changes.
     */
    @Test
    public void concurrentStatusChangesAndAdditionsAreAllKept() throws Exception {
        Path file = newFile();
        ScoredQueueStore store = new ScoredQueueStore(file);
        List<ScoredPaper> reviewed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reviewed.add(scored(queueKeyOnly(i), "RP1-mh-risk", 0.5 + i / 100.0));
        }
        store.addAll(reviewed);

        ExecutorService threads = Executors.newFixedThreadPool(8);
        List<Future<?>> work = new ArrayList<>();
        for (int i = 0; i < reviewed.size(); i++) {
            ScoredPaper paper = reviewed.get(i);
            ReviewStatus decision = i % 2 == 0 ? ReviewStatus.APPROVED : ReviewStatus.DROPPED;
            // Another store object on the same file, as the GUI and the pipeline would each hold.
            ScoredQueueStore gui = new ScoredQueueStore(file);
            work.add(threads.submit(() -> gui.setStatus(paper, decision)));
            int added = 100 + i;
            work.add(threads.submit(() -> store.addAll(List.of(scored(queueKeyOnly(added), "RP4-mh-char", 0.7)))));
        }
        for (Future<?> task : work) {
            task.get();
        }
        threads.shutdown();

        Map<String, ScoredPaper> queue = new ScoredQueueStore(file).load().stream()
                .collect(Collectors.toMap(ScoredPaper::queueKey, p -> p));
        assertEquals(40, queue.size());
        for (int i = 0; i < reviewed.size(); i++) {
            ReviewStatus expected = i % 2 == 0 ? ReviewStatus.APPROVED : ReviewStatus.DROPPED;
            assertEquals(expected, queue.get(reviewed.get(i).queueKey()).status());
        }
    }

    @Test
    public void recommendationIsKeptAcrossReloads() throws IOException {
        Path file = newFile();
        ScoredPaper recommended = scored(Candidates.full(), "RP1-mh-risk", 0.68).withRecommended(true);
        ScoredPaper notRecommended = scored(Candidates.sparse(), "RP1-mh-risk", 0.21);
        new ScoredQueueStore(file).addAll(List.of(recommended, notRecommended));

        assertEquals(List.of(recommended, notRecommended), new ScoredQueueStore(file).load());
    }

    /** A queue written before recommendations existed loads, with nothing recommended. */
    @Test
    public void queueSavedBeforeRecommendationsLoadsAsNotRecommended() throws IOException {
        Path file = newFile();
        ScoredPaper paper = scored(Candidates.full(), "RP1-mh-risk", 0.9).withRecommended(true);
        new ScoredQueueStore(file).addAll(List.of(paper));
        ObjectMapper json = new ObjectMapper();
        ArrayNode entries = (ArrayNode) json.readTree(file.toFile());
        entries.forEach(entry -> ((ObjectNode) entry).remove("recommended"));
        Files.write(file, json.writeValueAsBytes(entries));

        assertEquals(List.of(paper.withRecommended(false)), new ScoredQueueStore(file).load());
    }

    @Test(expected = IOException.class)
    public void corruptFileIsReportedNotTreatedAsEmpty() throws IOException {
        Path file = Files.createTempDirectory("litroute-test").resolve("scored_queue.json");
        Files.writeString(file, "[{\"profileName\": \"RP1-mh-risk\", \"score\": 0.9");

        new ScoredQueueStore(file).load();
    }
}
