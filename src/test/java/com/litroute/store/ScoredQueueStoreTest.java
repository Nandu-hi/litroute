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
import java.util.stream.Stream;
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

    /** The recorded PDF of {@link Candidates#full()}'s paper, saved where M2 would: pdfs/<doi>.pdf. */
    private static Path downloadedPdf(Path data) throws IOException {
        return copyTo(data.resolve("pdfs").resolve("10.1186_s43065-024-00115-x.pdf"), "e502fdcc276fe9dbf272.pdf");
    }

    private static Path copyTo(Path target, String recordedPdf) throws IOException {
        Files.createDirectories(target.getParent());
        return Files.copy(Path.of("src/test/resources/http", recordedPdf), target);
    }

    @Test
    public void aMatchedPapersPdfIsCopiedIntoItsStudentsFolder() throws IOException {
        Path file = newFile();
        Path pdf = downloadedPdf(file.getParent());
        ScoredQueueStore store = new ScoredQueueStore(file);
        ScoredPaper matched = scored(Candidates.full().withPdfPath(Optional.of(pdf)), "RP1-mh-risk", 0.8);

        List<ScoredPaper> queue = store.addAll(Map.of("RP1-mh-risk", List.of(matched)));

        Path copy = file.resolveSibling("matched").resolve("RP1-mh-risk").resolve(pdf.getFileName());
        assertEquals(copy, store.pdfFolder("RP1-mh-risk").resolve(pdf.getFileName()));
        assertEquals(-1, Files.mismatch(pdf, copy));
        assertEquals(Optional.of(copy), queue.get(0).candidate().pdfPath());
        assertEquals(queue, new ScoredQueueStore(file).load());
        assertTrue("the download is left where M2 saved it", Files.exists(pdf));
    }

    /** A paper an AI matcher gives to two students is in both students' folders. */
    @Test
    public void aPaperMatchedToTwoStudentsIsInBothFolders() throws IOException {
        Path file = newFile();
        Path pdf = downloadedPdf(file.getParent());
        PaperCandidate paper = Candidates.full().withPdfPath(Optional.of(pdf));
        ScoredQueueStore store = new ScoredQueueStore(file);

        store.addAll(Map.of("RP1-mh-risk", List.of(scored(paper, "RP1-mh-risk", 0.8)),
                "RP4-mh-char", List.of(scored(paper, "RP4-mh-char", 0.6))));

        for (String student : List.of("RP1-mh-risk", "RP4-mh-char")) {
            assertEquals(-1, Files.mismatch(pdf, store.pdfFolder(student).resolve(pdf.getFileName())));
        }
    }

    @Test
    public void queuingAPaperAgainDoesNotCopyItsPdfTwice() throws IOException {
        Path file = newFile();
        Path pdf = downloadedPdf(file.getParent());
        ScoredQueueStore store = new ScoredQueueStore(file);
        PaperCandidate paper = Candidates.full().withPdfPath(Optional.of(pdf));
        store.addAll(List.of(scored(paper, "RP1-mh-risk", 0.6)));

        List<ScoredPaper> queue = store.addAll(List.of(scored(paper, "RP1-mh-risk", 0.9)));
        store.addAll(queue); // entries read back from the queue point at the copy already

        try (Stream<Path> files = Files.list(store.pdfFolder("RP1-mh-risk"))) {
            assertEquals(List.of(pdf.getFileName().toString()), files.map(f -> f.getFileName().toString()).toList());
        }
        assertEquals(1, store.load().size());
    }

    /** Two papers whose PDFs share a file name (local PDFs, say) are both kept. */
    @Test
    public void anotherPapersPdfWithTheSameNameGetsANumberedName() throws IOException {
        Path file = newFile();
        Path first = copyTo(file.resolveSibling("a").resolve("paper.pdf"), "e502fdcc276fe9dbf272.pdf");
        Path second = copyTo(file.resolveSibling("b").resolve("paper.pdf"), "8d0d08f9b152568722f5.pdf");
        ScoredQueueStore store = new ScoredQueueStore(file);

        List<ScoredPaper> queue = store.addAll(List.of(
                scored(Candidates.full().withPdfPath(Optional.of(first)), "RP1-mh-risk", 0.8),
                scored(Candidates.ssiReview(second), "RP1-mh-risk", 0.7)));

        Path folder = store.pdfFolder("RP1-mh-risk");
        assertEquals(Optional.of(folder.resolve("paper.pdf")), queue.get(0).candidate().pdfPath());
        assertEquals(Optional.of(folder.resolve("paper-2.pdf")), queue.get(1).candidate().pdfPath());
        assertEquals(-1, Files.mismatch(first, folder.resolve("paper.pdf")));
        assertEquals(-1, Files.mismatch(second, folder.resolve("paper-2.pdf")));
    }

    /** A paywalled paper, or one whose download has since been deleted, is still queued. */
    @Test
    public void aPaperWithoutAPdfIsQueuedWithoutAFile() throws IOException {
        Path file = newFile();
        ScoredQueueStore store = new ScoredQueueStore(file);
        ScoredPaper noPdf = scored(Candidates.sparse(), "RP1-mh-risk", 0.5);
        ScoredPaper gone = scored(Candidates.full(), "RP1-mh-risk", 0.7); // its pdfPath does not exist here

        assertEquals(List.of(noPdf, gone), store.addAll(List.of(noPdf, gone)));
        assertFalse(Files.exists(store.pdfFolder("RP1-mh-risk")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aProfileNameCannotPointOutsideTheMatchedFolder() throws IOException {
        new ScoredQueueStore(newFile()).pdfFolder("../elsewhere");
    }

    @Test(expected = IOException.class)
    public void corruptFileIsReportedNotTreatedAsEmpty() throws IOException {
        Path file = Files.createTempDirectory("litroute-test").resolve("scored_queue.json");
        Files.writeString(file, "[{\"profileName\": \"RP1-mh-risk\", \"score\": 0.9");

        new ScoredQueueStore(file).load();
    }
}
