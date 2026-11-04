package com.litroute.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.model.AuthorName;
import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.store.DataCaches.Cache;
import com.litroute.store.DataCaches.Report;
import com.litroute.store.DataCaches.Usage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class DataCachesTest {

    private static final byte[] QUEUED_PDF = "%PDF-1.4 the paper in the queue".getBytes();
    private static final byte[] SENT_PDF = "%PDF-1.4 the paper already sent, somewhat longer".getBytes();

    private Path data;
    private DataCaches caches;
    private ScoredQueueStore queue;
    private SentPaperStore sent;
    private EnrichmentCacheStore extracted;
    private EmailCacheStore emails;
    private ProfileStore profiles;
    private PriorityAuthorStore authors;
    private Path queuedCopy;
    private Path sentCopy;

    /** A little of everything: one paper queued and one sent, each with a PDF copy, and what M9 keeps of them. */
    @Before
    public void setUp() throws IOException {
        data = Files.createTempDirectory("litroute-caches");
        caches = new DataCaches(data);
        queue = new ScoredQueueStore(data.resolve("scored_queue.json"), data.resolve("matched"));
        sent = new SentPaperStore(data.resolve("sent_papers.json"));
        extracted = new EnrichmentCacheStore(data.resolve("enrichment_cache.json"));
        emails = new EmailCacheStore(data.resolve("email_cache.json"));
        profiles = new ProfileStore(data.resolve("profiles.json"));
        authors = new PriorityAuthorStore(data.resolve("priority_authors.json"));

        Files.createDirectories(data.resolve("pdfs"));
        Path queuedDownload = Files.write(data.resolve("pdfs/queued.pdf"), QUEUED_PDF);
        Path sentDownload = Files.write(data.resolve("pdfs/sent.pdf"), SENT_PDF);
        PaperCandidate facades = Candidates.full();
        ScoredPaper queued = new ScoredPaper(facades.withPdfPath(Optional.of(queuedDownload)), "RP1-mh-risk", 0.8, "...",
                ReviewStatus.PENDING);
        List<ScoredPaper> inQueue = queue.addAll(List.of(queued));
        queuedCopy = inQueue.get(0).candidate().pdfPath().orElseThrow();

        ScoredPaper toSend = new ScoredPaper(Candidates.ssiReview(sentDownload), "RP1-mh-risk", 0.9, "...", ReviewStatus.APPROVED);
        ScoredPaper filed = queue.addAll(List.of(toSend)).get(1);
        sentCopy = filed.candidate().pdfPath().orElseThrow();
        sent.addAll(List.of(filed), Instant.parse("2026-10-25T10:00:00Z"));
        queue.remove(List.of(filed));

        extracted.save(new EnrichmentCacheStore.Contents(List.of(facades), Map.of("facades", facades)));
        emails.save(Set.of("<m1@mail.gmail.com>"));
        Files.writeString(data.resolve("profiles.json"), "{}");
        authors.save(List.of(new AuthorName("Alice", "Carter")));
        Files.writeString(data.resolve("sent_papers.json.bak"), "a backup someone made");
    }

    private Usage usage(Report report, Cache cache) {
        return report.caches().stream().filter(entry -> entry.cache() == cache).findFirst().orElseThrow();
    }

    @Test
    public void anEmptyFolderHoldsNothing() throws IOException {
        Report report = new DataCaches(Files.createTempDirectory("litroute-caches").resolve("not-yet")).report();

        assertEquals(Cache.values().length, report.caches().size());
        assertEquals(0, report.totalBytes());
        assertEquals(0, report.otherBytes());
    }

    @Test
    public void eachCacheReportsItsFilesAndWhatItHolds() throws IOException {
        Report report = caches.report();

        assertEquals(Files.size(data.resolve("scored_queue.json")) + QUEUED_PDF.length, usage(report, Cache.REVIEW_QUEUE).bytes());
        assertEquals("1 paper(s): 1 pending, 0 approved, 0 dropped, 0 not matched",
                usage(report, Cache.REVIEW_QUEUE).contents());
        assertEquals(Files.size(data.resolve("sent_papers.json")) + SENT_PDF.length, usage(report, Cache.SENT_PAPERS).bytes());
        assertEquals("1 paper(s) sent to 1 student(s)", usage(report, Cache.SENT_PAPERS).contents());
        assertEquals("1 paper(s), 1 lookup(s)", usage(report, Cache.EXTRACTED_PAPERS).contents());
        assertEquals("1 email(s)", usage(report, Cache.PROCESSED_EMAILS).contents());
        assertEquals(QUEUED_PDF.length + SENT_PDF.length, usage(report, Cache.DOWNLOADED_PDFS).bytes());
        assertEquals("2 PDF(s)", usage(report, Cache.DOWNLOADED_PDFS).contents());
        assertEquals("0 profile(s), 0 paper(s)", usage(report, Cache.PROFILES).contents());
    }

    @Test
    public void everyByteInTheFolderIsCountedOnce() throws IOException {
        Report report = caches.report();

        assertEquals(Files.size(data.resolve("priority_authors.json")) + Files.size(data.resolve("sent_papers.json.bak")),
                report.otherBytes());
    }

    /** A corrupt store is something to show, not a reason the tab can't open. */
    @Test
    public void aCorruptStoreStillShowsItsSize() throws IOException {
        Files.writeString(data.resolve("sent_papers.json"), "not json");

        Usage usage = usage(caches.report(), Cache.SENT_PAPERS);

        assertEquals("not json".length(), usage.bytes());
        assertTrue(usage.contents(), usage.contents().contains("can't be read"));
    }

    @Test
    public void clearingTheSentPapersDeletesOnlyTheirRecordAndCopy() throws IOException {
        caches.clear(Set.of(Cache.SENT_PAPERS));

        assertTrue(sent.load().isEmpty());
        assertFalse(Files.exists(sentCopy));
        assertTrue("the queue's copy is the queue's", Files.exists(queuedCopy));
        assertEquals(1, queue.load().size());
        assertEquals(1, extracted.load().enriched().size());
        assertEquals(1, emails.load().size());
    }

    /** The same PDF may be left in both, by a crash between recording a paper as sent and taking it out of the queue. */
    @Test
    public void aCopyTheQueueStillPointsToSurvivesClearingTheSentPapers() throws IOException {
        ScoredPaper stillQueued = queue.load().get(0);
        sent.addAll(List.of(stillQueued), Instant.parse("2026-10-25T11:00:00Z"));

        caches.clear(Set.of(Cache.SENT_PAPERS));

        assertTrue(Files.exists(queuedCopy));
        assertFalse(Files.exists(sentCopy));
    }

    @Test
    public void clearingTheQueueLeavesTheSentPapers() throws IOException {
        caches.clear(Set.of(Cache.REVIEW_QUEUE));

        assertTrue(queue.load().isEmpty());
        assertFalse(Files.exists(queuedCopy));
        assertEquals(1, sent.load().size());
        assertTrue(Files.exists(sentCopy));
    }

    @Test
    public void clearingTheDownloadsLeavesTheStudentsCopies() throws IOException {
        caches.clear(Set.of(Cache.DOWNLOADED_PDFS));

        assertEquals(0, usage(caches.report(), Cache.DOWNLOADED_PDFS).bytes());
        assertTrue(Files.isDirectory(data.resolve("pdfs")));
        assertTrue(Files.exists(queuedCopy));
        assertTrue(Files.exists(sentCopy));
    }

    @Test
    public void clearingEverythingLeavesOnlyWhatIsNotACache() throws IOException {
        caches.clear(EnumSet.allOf(Cache.class));

        assertTrue(queue.load().isEmpty());
        assertTrue(sent.load().isEmpty());
        assertTrue(extracted.load().enriched().isEmpty());
        assertTrue(extracted.load().lookups().isEmpty());
        assertTrue(emails.load().isEmpty());
        assertTrue(profiles.loadAll().isEmpty());
        assertFalse(Files.exists(data.resolve("matched/RP1-mh-risk")));
        assertEquals("the authors list is a setting", List.of(new AuthorName("Alice", "Carter")), authors.load());
        assertTrue(Files.exists(data.resolve("sent_papers.json.bak")));
    }

    @Test
    public void clearingNothingChangesNothing() throws IOException {
        caches.clear(Set.of());

        assertEquals(1, queue.load().size());
        assertEquals(1, sent.load().size());
    }
}
