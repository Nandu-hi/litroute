package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.litroute.ingestion.enrich.ArticlePageReader;
import com.litroute.ingestion.enrich.CompositeMetadataLookup;
import com.litroute.ingestion.enrich.CrossrefSource;
import com.litroute.ingestion.enrich.OnlinePaperEnricher;
import com.litroute.ingestion.enrich.OpenAlexSource;
import com.litroute.ingestion.enrich.PdfDownloader;
import com.litroute.ingestion.enrich.RecordedHttp;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.EnrichmentCacheStore.Contents;
import com.litroute.testing.SampleMails;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The batch runner that ties M2-M4 to the M7a/M7c stores, driven by the real alert mails.
 *
 * What it has to get right is ordering rather than metadata: nothing may reach disk until the whole
 * batch is enriched and deduplicated, and the email cache is written last so a crash costs a re-run
 * rather than silently skipping mail.
 */
public class AlertBatchProcessorTest {

    private static final String SSI_DOI = "10.1186/s43065-025-00118-2";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path enrichmentCacheFile;
    private Path emailCacheFile;
    private final RecordedHttp http = new RecordedHttp();

    private void useStoresUnder(String folder) {
        enrichmentCacheFile = tmp.getRoot().toPath().resolve(folder).resolve("enrichment_cache.json");
        emailCacheFile = tmp.getRoot().toPath().resolve(folder).resolve("email_cache.json");
    }

    private AlertBatchProcessor processor(PaperEnricher enricher) {
        useStoresUnder("data");
        return new AlertBatchProcessor(new PaperExtractor(enricher),
                new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile));
    }

    /** The real M3: publisher page, both APIs and the PDF download, replayed from the recording. */
    private PaperEnricher realEnrichment() {
        return new OnlinePaperEnricher(
                new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(new CrossrefSource(http), new OpenAlexSource(http))),
                new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs")));
    }

    private List<PaperCandidate> stored() throws IOException {
        return new EnrichmentCacheStore(enrichmentCacheFile).load().enriched();
    }

    private static RawEmail springer(String messageId) {
        RawEmail mail = SampleMails.SPRINGER_RELEVANT.email();
        return new RawEmail(messageId, mail.subject(), mail.fromAddress(), mail.receivedAt(),
                mail.htmlBody(), mail.plainTextBody());
    }

    private static RawEmail scholar(String messageId) {
        RawEmail mail = SampleMails.GOOGLE_SCHOLAR.email();
        return new RawEmail(messageId, mail.subject(), mail.fromAddress(), mail.receivedAt(),
                mail.htmlBody(), mail.plainTextBody());
    }

    private static final RawEmail SPRINGER = springer("<springer@mail>");
    private static final RawEmail SCHOLAR = scholar("<scholar@mail>");

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

    /** What lands in M7c is the enriched paper - the real DOI and the real downloaded PDF, not the parse. */
    @Test
    public void m7cHoldsTheEnrichedPapersNotTheParsedOnes() throws IOException {
        List<PaperCandidate> returned = processor(realEnrichment()).process(List.of(SPRINGER));

        List<PaperCandidate> stored = stored();
        assertEquals(returned, stored);
        assertEquals(Optional.of(SSI_DOI), stored.get(0).doi());
        assertEquals("10.1186_s43065-025-00118-2.pdf", stored.get(0).pdfPath().orElseThrow().getFileName().toString());
        assertTrue(Files.isRegularFile(stored.get(0).pdfPath().orElseThrow()));
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), stored.get(0).venue());
    }

    /**
     * The same alert forwarded by two different people is two emails carrying one paper. M4 runs
     * before the write, so M7c never ends up with two entries for it.
     */
    @Test
    public void papersSharingADoiAreMergedBeforeTheyAreStored() throws IOException {
        List<PaperCandidate> returned = processor(realEnrichment())
                .process(List.of(springer("<from-advisor@mail>"), springer("<from-labmate@mail>")));

        assertEquals(1, returned.size());
        assertEquals(Optional.of(SSI_DOI), returned.get(0).doi());
        assertEquals(1, stored().size());
        // Both emails are still marked done even though their papers collapsed into one record.
        assertEquals(Set.of("<from-advisor@mail>", "<from-labmate@mail>"),
                new EmailCacheStore(emailCacheFile).load());
    }

    /** The same paper arriving in a later batch merges into the record already held. */
    @Test
    public void aPaperAlreadyStoredFromAnEarlierRunIsMergedRatherThanStoredTwice() throws IOException {
        AlertBatchProcessor processor = processor(realEnrichment());
        processor.process(List.of(springer("<first-run@mail>")));

        processor.process(List.of(springer("<second-run@mail>")));

        assertEquals(1, stored().size());
        assertEquals("the first copy keeps the record", "<first-run@mail>", stored().get(0).sourceMessageId());
    }

    @Test
    public void ifM7cCannotBeSavedTheEmailsStayUnmarkedSoTheBatchIsRetried() throws IOException {
        Path blocker = Files.createFile(tmp.getRoot().toPath().resolve("not-a-directory"));
        AlertBatchProcessor processor = new AlertBatchProcessor(PaperExtractor.offline(),
                new EnrichmentCacheStore(blocker.resolve("enrichment_cache.json")),
                new EmailCacheStore(tmp.getRoot().toPath().resolve("email_cache.json")));

        assertThrows(IOException.class, () -> processor.process(List.of(SPRINGER)));

        assertFalse("email cache is written last, so it must not exist",
                Files.exists(tmp.getRoot().toPath().resolve("email_cache.json")));
    }

    /**
     * Prof. Badal's covering note with no alert under it still has to be marked done, or every run
     * would process it again for ever.
     */
    @Test
    public void anEmailWithNoPapersIsStillMarkedProcessed() throws IOException {
        RawEmail justANote = new RawEmail("<note@mail>", "Fwd: research profiles",
                SampleMails.FORWARDER, SampleMails.SPRINGER_RELEVANT.receivedAt(),
                Optional.of("<div>I need to send you different research profiles and papers for each of "
                        + "them too. I will do that later today in a separate email.</div>"), Optional.empty());

        List<PaperCandidate> extracted = processor(PaperEnricher.NONE).process(List.of(justANote));

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

    /**
     * A lookup made during the run is written back with the batch rather than lost at exit - that is
     * the whole point of handing the extractor M7c's live cache map.
     */
    @Test
    public void lookupsCachedDuringTheRunArePersistedWithTheBatch() throws IOException {
        useStoresUnder("data");
        AlertBatchProcessor processor = new AlertBatchProcessor(
                lookups -> new PaperExtractor(new com.litroute.ingestion.enrich.CachingPaperEnricher(
                        realEnrichment(), lookups)),
                new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile));

        processor.process(List.of(SPRINGER));

        Contents reloaded = new EnrichmentCacheStore(enrichmentCacheFile).load();
        assertEquals(1, reloaded.lookups().size());
        assertEquals("the cached entry is the enriched paper",
                Optional.of(SSI_DOI), reloaded.lookups().values().iterator().next().doi());
    }

    /** What a previous run cached is handed back to the extractor, so the paper is not looked up again. */
    @Test
    public void aPreviouslyCachedLookupIsHandedToTheExtractor() throws IOException {
        useStoresUnder("data");
        AlertBatchProcessor first = new AlertBatchProcessor(
                lookups -> new PaperExtractor(new com.litroute.ingestion.enrich.CachingPaperEnricher(
                        realEnrichment(), lookups)),
                new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile));
        first.process(List.of(springer("<first-run@mail>")));
        http.requests.clear();

        List<String> keysSeen = new ArrayList<>();
        new AlertBatchProcessor(lookups -> {
            keysSeen.addAll(lookups.keySet());
            return new PaperExtractor(new com.litroute.ingestion.enrich.CachingPaperEnricher(
                    realEnrichment(), lookups));
        }, new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile))
                .process(List.of(springer("<second-run@mail>")));

        assertEquals("the run started from what the previous one had cached", 1, keysSeen.size());
        assertEquals("and the paper was answered from it, with no lookup at all", List.of(), http.requests);
    }

    /** A cache written by an earlier run is loaded and offered to the extractor as it stands. */
    @Test
    public void aCacheLeftByAnEarlierRunIsLoadedFromDisk() throws IOException {
        useStoresUnder("data");
        PaperCandidate alreadyLookedUp = new PaperCandidate("<an-earlier-run@mail>",
                "Hygrothermal analysis of ceramic coating durability under thermal fluctuations "
                        + "on building facades in Brasília",
                List.of("Gilberto Gomes"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("10.1186/s43065-024-00115-x"), Optional.empty(), Optional.empty(), Optional.empty());
        new EnrichmentCacheStore(enrichmentCacheFile)
                .save(new Contents(List.of(), Map.of("hygrothermalanalysis", alreadyLookedUp)));

        List<String> keysSeen = new ArrayList<>();
        new AlertBatchProcessor(lookups -> {
            keysSeen.addAll(lookups.keySet());
            return PaperExtractor.offline();
        }, new EnrichmentCacheStore(enrichmentCacheFile), new EmailCacheStore(emailCacheFile))
                .process(List.of(SPRINGER));

        assertEquals(List.of("hygrothermalanalysis"), keysSeen);
    }
}
