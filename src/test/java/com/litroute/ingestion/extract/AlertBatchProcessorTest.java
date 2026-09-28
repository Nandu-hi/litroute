package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.EnrichmentCacheStore.Contents;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AlertBatchProcessorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path enrichmentCacheFile;
    private Path emailCacheFile;

    private AlertBatchProcessor processor(PaperEnricher enricher) {
        enrichmentCacheFile = tmp.getRoot().toPath().resolve("data").resolve("enrichment_cache.json");
        emailCacheFile = tmp.getRoot().toPath().resolve("data").resolve("email_cache.json");
        return new AlertBatchProcessor(new PaperExtractor(enricher),
                new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile));
    }

    private List<PaperCandidate> stored() throws IOException {
        return new EnrichmentCacheStore(enrichmentCacheFile).load().enriched();
    }

    private static RawEmail sample(String file, String messageId) {
        try (InputStream in = AlertBatchProcessorTest.class.getResourceAsStream("/samples/" + file)) {
            return new RawEmail(messageId, "Fwd: alert", "aprakashn@gmail.com", Instant.parse("2026-09-04T07:28:00Z"),
                    Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8)), Optional.empty());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PaperCandidate aCachedPaper() {
        return new PaperCandidate("<older@mail>", "A paper looked up on an earlier run", List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("10.9999/older"),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static final RawEmail SPRINGER = sample("springeropen_relevant.html", "<springer@mail>");
    private static final RawEmail SCHOLAR = sample("google_scholar_alert.html", "<scholar@mail>");

    @Test
    public void nothingIsStoredWhileEmailsAreBeingEnrichedAndEverythingIsStoredOnceAfterwards() throws IOException {
        List<String> filesSeenDuringEnrichment = new ArrayList<>();
        PaperEnricher watching = (paper, kind) -> {
            filesSeenDuringEnrichment.add((Files.exists(enrichmentCacheFile) ? "candidates " : "")
                    + (Files.exists(emailCacheFile) ? "cache" : ""));
            return paper;
        };
        AlertBatchProcessor processor = processor(watching);

        processor.process(List.of(SPRINGER, SCHOLAR));

        // Two papers were enriched (one per mail); at neither moment had anything been written yet -
        // in particular not after the first email, which a save-per-email design would have done.
        assertEquals(List.of("", ""), filesSeenDuringEnrichment);
        assertEquals(2, stored().size());
        assertEquals(Set.of("<springer@mail>", "<scholar@mail>"), new EmailCacheStore(emailCacheFile).load());
    }

    @Test
    public void m7cHoldsTheEnrichedPapersNotTheParsedOnes() throws IOException {
        PaperEnricher addsADoi = (paper, kind) -> new PaperCandidate(paper.sourceMessageId(), paper.title(), paper.authors(),
                paper.venue(), paper.year(), paper.articleType(), Optional.of("10.9999/enriched"), paper.url(),
                paper.abstractSnippet(), Optional.of(Path.of("/pdfs/enriched.pdf")));

        List<PaperCandidate> returned = processor(addsADoi).process(List.of(SPRINGER));

        List<PaperCandidate> stored = stored();
        assertEquals(returned, stored);
        assertEquals(Optional.of("10.9999/enriched"), stored.get(0).doi());
        assertEquals(Optional.of(Path.of("/pdfs/enriched.pdf")), stored.get(0).pdfPath());
    }

    /** M4 runs before the write, so the file never contains two entries for one paper. */
    @Test
    public void papersSharingADoiAreMergedBeforeTheyAreStored() throws IOException {
        PaperEnricher oneDoiForEverything = (paper, kind) -> new PaperCandidate(paper.sourceMessageId(),
                paper.title(), paper.authors(), paper.venue(), paper.year(), paper.articleType(),
                Optional.of("10.9999/same"), paper.url(), paper.abstractSnippet(), paper.pdfPath());

        List<PaperCandidate> returned = processor(oneDoiForEverything).process(List.of(SPRINGER, SCHOLAR));

        assertEquals(1, returned.size());
        assertEquals(1, stored().size());
        // Both emails are still marked done even though their papers collapsed into one record.
        assertEquals(Set.of("<springer@mail>", "<scholar@mail>"), new EmailCacheStore(emailCacheFile).load());
    }

    @Test
    public void aPaperAlreadyStoredFromAnEarlierRunIsMergedRatherThanStoredTwice() throws IOException {
        PaperEnricher oneDoiForEverything = (paper, kind) -> new PaperCandidate(paper.sourceMessageId(),
                paper.title(), paper.authors(), paper.venue(), paper.year(), paper.articleType(),
                Optional.of("10.9999/same"), paper.url(), paper.abstractSnippet(), paper.pdfPath());
        AlertBatchProcessor processor = processor(oneDoiForEverything);
        processor.process(List.of(SPRINGER));

        processor.process(List.of(SCHOLAR));

        assertEquals(1, stored().size());
    }

    @Test
    public void ifM7cCannotBeSavedTheEmailsStayUnmarkedSoTheBatchIsRetried() throws IOException {
        Path blocker = Files.createFile(tmp.getRoot().toPath().resolve("not-a-directory"));
        AlertBatchProcessor processor = new AlertBatchProcessor(PaperExtractor.offline(),
                new EnrichmentCacheStore(blocker.resolve("enrichment_cache.json")),
                new EmailCacheStore(tmp.getRoot().toPath().resolve("email_cache.json")));

        assertThrows(IOException.class, () -> processor.process(List.of(SPRINGER)));

        assertFalse("email cache is written last, so it must not exist", Files.exists(tmp.getRoot().toPath().resolve("email_cache.json")));
    }

    @Test
    public void anEmailWithNoPapersIsStillMarkedProcessed() throws IOException {
        RawEmail noPapers = new RawEmail("<note@mail>", "Fwd: hi", "aprakashn@gmail.com", Instant.parse("2026-09-04T07:28:00Z"),
                Optional.of("<div>Thanks, see you Monday.</div>"), Optional.empty());

        List<PaperCandidate> extracted = processor(PaperEnricher.NONE).process(List.of(noPapers));

        assertTrue(extracted.isEmpty());
        assertEquals(Set.of("<note@mail>"), new EmailCacheStore(emailCacheFile).load());
    }

    @Test
    public void earlierPapersAndProcessedIdsAreKept() throws IOException {
        AlertBatchProcessor processor = processor(PaperEnricher.NONE);
        processor.process(List.of(SPRINGER));

        processor.process(List.of(SCHOLAR));

        assertEquals(Set.of("<springer@mail>", "<scholar@mail>"), new EmailCacheStore(emailCacheFile).load());
        assertEquals(2, stored().size());
    }

    /** Crash after M7c was saved but before the IDs were: the retry must replace, not duplicate. */
    @Test
    public void reprocessingABatchAfterACrashReplacesItsEarlierEntries() throws IOException {
        AlertBatchProcessor processor = processor(PaperEnricher.NONE);
        processor.process(List.of(SPRINGER, SCHOLAR));
        Files.delete(emailCacheFile); // "crash": the candidates made it to disk, the email cache did not

        processor.process(List.of(SPRINGER, SCHOLAR));

        assertEquals("no duplicates", 2, stored().size());
        assertEquals(Set.of("<springer@mail>", "<scholar@mail>"), new EmailCacheStore(emailCacheFile).load());
    }

    /** The lookup cache the enrichment filled is written back with the batch, not lost at exit. */
    @Test
    public void lookupsCachedDuringTheRunArePersistedWithTheBatch() throws IOException {
        enrichmentCacheFile = tmp.getRoot().toPath().resolve("data").resolve("enrichment_cache.json");
        emailCacheFile = tmp.getRoot().toPath().resolve("data").resolve("email_cache.json");
        AlertBatchProcessor processor = new AlertBatchProcessor(
                lookups -> new PaperExtractor((paper, kind) -> {
                    lookups.put("cached-during-the-run", paper);
                    return paper;
                }),
                new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile));

        processor.process(List.of(SPRINGER));

        Contents reloaded = new EnrichmentCacheStore(enrichmentCacheFile).load();
        assertEquals(Set.of("cached-during-the-run"), reloaded.lookups().keySet());
    }

    @Test
    public void aPreviouslyCachedLookupIsHandedToTheExtractor() throws IOException {
        enrichmentCacheFile = tmp.getRoot().toPath().resolve("data").resolve("enrichment_cache.json");
        emailCacheFile = tmp.getRoot().toPath().resolve("data").resolve("email_cache.json");
        new EnrichmentCacheStore(enrichmentCacheFile)
                .save(new Contents(List.of(), Map.of("seen-before", aCachedPaper())));

        List<String> keysSeen = new ArrayList<>();
        new AlertBatchProcessor(lookups -> {
            keysSeen.addAll(lookups.keySet());
            return PaperExtractor.offline();
        }, new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile))
                .process(List.of(SPRINGER));

        assertEquals(List.of("seen-before"), keysSeen);
    }
}
