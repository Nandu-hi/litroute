package com.litroute.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.litroute.ingestion.email.EmailSource;
import com.litroute.ingestion.enrich.ArticlePageReader;
import com.litroute.ingestion.enrich.CompositeMetadataLookup;
import com.litroute.ingestion.enrich.CrossrefSource;
import com.litroute.ingestion.enrich.OnlinePaperEnricher;
import com.litroute.ingestion.enrich.OpenAlexSource;
import com.litroute.ingestion.enrich.PdfDownloader;
import com.litroute.ingestion.enrich.RecordedHttp;
import com.litroute.ingestion.extract.AlertBatchProcessor;
import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.RawEmail;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.pipeline.PipelineRunner.MatcherFactory;
import com.litroute.pipeline.PipelineRunner.MatcherKind;
import com.litroute.pipeline.PipelineRunner.Report;
import com.litroute.scoring.RelevanceScorer;
import com.litroute.security.GmailCredentials;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.ScoredPaperLog;
import com.litroute.store.ScoredQueueStore;
import com.litroute.testing.SampleMails;
import com.litroute.testing.SampleMails.Sample;
import com.litroute.testing.TestModel;
import com.litroute.testing.TestProfiles;
import com.litroute.testing.TestProfiles.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/**
 * M9 end to end on real data: the client's six real alert emails, enriched offline from the recorded
 * HTTP replies, matched by the real SPECTER2 model against the students' real profiles. The inbox hands
 * over those six emails as M1 would, skipping the ones already processed or read, and remembers which ones
 * the pipeline marks read; doing either over IMAP is {@code GmailEmailSourceTest}'s job.
 */
public class PipelineRunnerTest {

    /**
     * The six real alerts, all unread to begin with. Marking one read checks it is already recorded in M7a,
     * since M1 never fetches a read message again.
     */
    private final class SampleInbox implements EmailSource {

        final Set<String> read = new LinkedHashSet<>();

        @Override
        public List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> processed) {
            return SampleMails.ALL.stream().map(Sample::email)
                    .filter(email -> !processed.contains(email.messageId()) && !read.contains(email.messageId()))
                    .toList();
        }

        @Override
        public int markRead(GmailCredentials credentials, Collection<String> messageIds) {
            try {
                Set<String> recorded = new EmailCacheStore(data.resolve("email_cache.json")).load();
                assertTrue("an alert is marked read only once M7a holds it", recorded.containsAll(messageIds));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            int before = read.size();
            read.addAll(messageIds);
            return read.size() - before;
        }
    }

    private static final GmailCredentials CREDENTIALS = new GmailCredentials("cs24b027@smail.iitm.ac.in",
            "not-used-offline".toCharArray());

    private Path data;
    private ScoredQueueStore queue;
    private List<String> progress;
    private SampleInbox inbox;

    @Before
    public void setUp() throws IOException {
        TestProfiles.assumeAvailable();
        data = Files.createTempDirectory("litroute-pipeline");
        queue = new ScoredQueueStore(data.resolve("scored_queue.json"), data.resolve("matched"));
        progress = new ArrayList<>();
        inbox = new SampleInbox();
    }

    private PipelineRunner runner(Workspace profiles, MatcherFactory matchers) {
        return runner(profiles, matchers, true);
    }

    private PipelineRunner runner(Workspace profiles, MatcherFactory matchers, boolean markRead) {
        RecordedHttp http = new RecordedHttp();
        OnlinePaperEnricher enrichment = new OnlinePaperEnricher(new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(new CrossrefSource(http), new OpenAlexSource(http))),
                new PdfDownloader(http, data.resolve("pdfs")));
        EmailCacheStore processed = new EmailCacheStore(data.resolve("email_cache.json"));
        AlertBatchProcessor batches = new AlertBatchProcessor(new PaperExtractor(enrichment),
                new EnrichmentCacheStore(data.resolve("enrichment_cache.json")), processed);
        return new PipelineRunner(profiles.builder(), profiles.store(), inbox, processed, batches, matchers,
                queue, new ScoredPaperLog(data.resolve("scored_papers.json")), markRead);
    }

    private PipelineRunner runner() {
        return runner(TestProfiles.freshWorkspace(), PipelineRunner.matcher(MatcherKind.HYBRID, TestModel.provider()));
    }

    private Set<String> heldPaperKeys() throws IOException {
        return new EnrichmentCacheStore(data.resolve("enrichment_cache.json")).load().enriched().stream()
                .map(ScoredPaper::paperKey).collect(Collectors.toSet());
    }

    @Test
    public void firstRunQueuesEveryAlertPaperForExactlyOneStudent() throws IOException {
        Report report = runner().run(CREDENTIALS, progress::add);

        List<ScoredPaper> queued = queue.load();
        assertEquals(SampleMails.ALL.size(), report.newAlerts());
        assertTrue(report.papersHeld() > 0);
        assertEquals(report.papersHeld(), report.papersScored());
        assertEquals(0, report.unscored());
        assertEquals(report.papersHeld(), report.matches());
        assertEquals(queued.size(), report.queueSize());
        // RelevanceScorer gives each paper to one student, so the queue holds each held paper once.
        assertEquals(report.papersHeld(), queued.size());
        assertEquals(heldPaperKeys(), queued.stream().map(p -> ScoredPaper.paperKey(p.candidate())).collect(Collectors.toSet()));
        assertTrue(queued.stream().allMatch(p -> p.status() == ReviewStatus.PENDING));
        long students = queued.stream().map(ScoredPaper::profileName).distinct().count();
        assertTrue(report.recommended() <= students * RelevanceScorer.RECOMMENDED_PER_STUDENT);
        assertEquals(report.recommended(), queued.stream().filter(ScoredPaper::recommended).count());
        assertTrue(progress.stream().anyMatch(line -> line.startsWith(SampleMails.ALL.size() + " new alert")));
    }

    @Test
    public void theAlertsARunReadsAreMarkedReadOnceProcessed() throws IOException {
        runner().run(CREDENTIALS, progress::add);

        assertEquals(SampleMails.ALL.stream().map(sample -> sample.email().messageId()).collect(Collectors.toSet()),
                inbox.read);
        assertTrue(progress.contains("Marked " + SampleMails.ALL.size() + " of " + SampleMails.ALL.size()
                + " alert email(s) as read in Gmail."));

        progress.clear();
        runner().run(CREDENTIALS, progress::add);

        assertTrue("nothing new, nothing marked", progress.stream().noneMatch(line -> line.startsWith("Marked")));
    }

    @Test
    public void aRunToldToKeepMailUnreadMarksNothingButStillSkipsProcessedAlerts() throws IOException {
        MatcherFactory hybrid = PipelineRunner.matcher(MatcherKind.HYBRID, TestModel.provider());
        Report first = runner(TestProfiles.freshWorkspace(), hybrid, false).run(CREDENTIALS, progress::add);

        assertEquals(SampleMails.ALL.size(), first.newAlerts());
        assertTrue(inbox.read.isEmpty());
        assertTrue(progress.contains("Left " + SampleMails.ALL.size() + " alert email(s) unread in Gmail."));

        Report again = runner(TestProfiles.freshWorkspace(), hybrid, false).run(CREDENTIALS, progress::add);

        assertEquals("M7a still keeps them from being processed twice", 0, again.newAlerts());
    }

    @Test
    public void aSecondRunFindsNothingNewAndKeepsTheProfessorsDecisions() throws IOException {
        runner().run(CREDENTIALS, progress::add);
        ScoredPaper approved = queue.load().get(0);
        assertTrue(queue.setStatus(approved, ReviewStatus.APPROVED));
        List<ScoredPaper> before = queue.load();

        Report again = runner().run(CREDENTIALS, progress::add);

        assertEquals(0, again.newAlerts());
        assertEquals(0, again.papersScored());
        assertEquals(before, queue.load());
        assertEquals(ReviewStatus.APPROVED, queue.load().get(0).status());
    }

    @Test
    public void papersFetchedBeforeAnyProfileExistsAreScoredOnceThereIsOne() throws IOException {
        Report withoutProfiles = runner(TestProfiles.emptyWorkspace(),
                PipelineRunner.matcher(MatcherKind.HYBRID, TestModel.provider())).run(CREDENTIALS, progress::add);

        assertEquals(SampleMails.ALL.size(), withoutProfiles.newAlerts());
        assertEquals(0, withoutProfiles.papersScored());
        assertEquals(withoutProfiles.papersHeld(), withoutProfiles.unscored());
        assertTrue(queue.load().isEmpty());

        Report withProfiles = runner().run(CREDENTIALS, progress::add);

        assertEquals(0, withProfiles.newAlerts());
        assertEquals(withoutProfiles.papersHeld(), withProfiles.papersScored());
        assertEquals(heldPaperKeys(), queue.load().stream().map(p -> ScoredPaper.paperKey(p.candidate()))
                .collect(Collectors.toSet()));
    }

    @Test
    public void papersAreScoredAgainWhenTheMatcherFailedAfterTheirEmailsWereProcessed() throws IOException {
        MatcherFactory unavailable = students -> {
            throw new IOException("Groq answered 503");
        };
        try {
            runner(TestProfiles.freshWorkspace(), unavailable).run(CREDENTIALS, progress::add);
            fail("the matcher's failure should reach the caller");
        } catch (IOException expected) {
            assertEquals("Groq answered 503", expected.getMessage());
        }
        assertTrue(queue.load().isEmpty());
        assertEquals("the alerts were processed before the matcher failed, so they are read",
                SampleMails.ALL.size(), inbox.read.size());

        Report retried = runner().run(CREDENTIALS, progress::add);

        assertEquals(0, retried.newAlerts());
        assertEquals(retried.papersHeld(), retried.papersScored());
        assertEquals(retried.papersHeld(), queue.load().size());
    }
}
