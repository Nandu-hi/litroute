package com.litroute.pipeline;

import com.litroute.embedding.EmbeddingProvider;
import com.litroute.ingestion.email.EmailSource;
import com.litroute.ingestion.email.GmailEmailSource;
import com.litroute.ingestion.enrich.CachingPaperEnricher;
import com.litroute.ingestion.enrich.OnlinePaperEnricher;
import com.litroute.ingestion.enrich.PdfDownloader;
import com.litroute.ingestion.extract.AiTitleExtractor;
import com.litroute.ingestion.extract.AlertBatchProcessor;
import com.litroute.ingestion.extract.ExtractionMode;
import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.RawEmail;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.profile.ProfileBuilder;
import com.litroute.profile.ProfileBuilder.BuildReport;
import com.litroute.profile.ProfileBuilder.ProfileReport;
import com.litroute.scoring.PaperMatcher;
import com.litroute.scoring.RelevanceScorer;
import com.litroute.security.GmailCredentials;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.PriorityAuthorStore;
import com.litroute.store.ProfileStore;
import com.litroute.store.ScoredQueueStore;
import com.litroute.store.SentPaperStore;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * M9: one run of the whole pipeline, start to finish:
 *
 * <ol>
 *   <li>bring every profile up to date with its folder (M5, {@link ProfileBuilder#syncAll}) - only new or
 *       changed PDFs are embedded;
 *   <li>fetch the alert emails not yet processed (M1);
 *   <li>extract, enrich and deduplicate them into M7c, then mark them processed in M7a
 *       ({@link AlertBatchProcessor}), and only then read in Gmail ({@link EmailSource#markRead}): M1 looks
 *       at unread mail only, so an alert marked read before M7a held it would be lost to a crash. When
 *       testing, the runner can be told to leave the alerts unread; M7a still keeps them from being
 *       processed twice;
 *   <li>match the papers no matcher has seen yet against the profiles (M6) and add them all to the
 *       review queue (M7e): the matches as pending, the rest as {@link ReviewStatus#UNMATCHED}, listed
 *       with the dropped papers under the student each is closest to.
 * </ol>
 *
 * The batch M6 sees is every deduplicated paper not yet scored, not just this run's emails, so papers
 * left unscored by an earlier run that failed after saving M7c (no profiles yet, or the matcher failed)
 * are scored by the next one. A paper counts as scored once it is in the review queue or was sent to a
 * student: the queue holds every paper M6 judged, matched or not. Each step's stores
 * are written in the order that makes a crash repeat work rather than skip it. The matcher favours the papers
 * of the authors on the professor's list ({@link PriorityAuthors}), read afresh for each run.
 */
public final class PipelineRunner {

    /** Builds the M6 matcher for the profiles as they stand after this run's sync. */
    @FunctionalInterface
    public interface MatcherFactory {
        /**
         * {@return the M6 matcher for the profiles as they stand after this run's sync}
         * Used by: {@link PipelineRunner#run}, step 5.
         *
         * @param profiles the synced profiles
         * @throws IOException if the matcher can't be built
         */
        PaperMatcher create(Collection<Profile> profiles) throws IOException;
    }

    /**
     * What a run did.
     *
     * @param profiles     the profile sync's report
     * @param newAlerts    alert emails fetched and processed in this run
     * @param papersHeld   deduplicated papers in M7c after the run
     * @param papersScored papers matched in this run (0 when there were no profiles to match against)
     * @param unscored     papers held but still not scored: waiting for a profile to exist
     * @param matches      (paper, student) pairs added to or refreshed in the review queue as matches
     * @param recommended  of those, how many were recommended
     * @param queueSize    entries in the review queue after the run
     * @param unmatched    papers matched to no one, queued as {@link ReviewStatus#UNMATCHED} with the dropped ones
     */
    public record Report(BuildReport profiles, int newAlerts, int papersHeld, int papersScored, int unscored,
            int matches, int recommended, int queueSize, int unmatched) {
    }

    /**
     * What a reset undid.
     *
     * @param processedEmails alert emails M7a had recorded as processed
     * @param markedUnread    of those, how many were found in the inbox and are unread again; the rest
     *                        are no longer in the inbox and will not be fetched again
     * @param queued          review-queue entries discarded, with the professor's decisions on them
     */
    public record ResetReport(int processedEmails, int markedUnread, int queued) {
    }

    private final ProfileBuilder profileBuilder;
    private final ProfileStore profiles;
    private final EmailSource emails;
    private final EmailCacheStore processedEmails;
    private final AlertBatchProcessor batches;
    private final MatcherFactory matchers;
    private final ScoredQueueStore queue;
    private final SentPaperStore sent;
    private final boolean markRead;

    /**
     * Input: every stage and store (listed below).
     * Output: a runner that marks processed alerts read.
     * Used by: tests wiring their own stages; the other constructor.
     * Example: {@code new PipelineRunner(builder, profiles, fakeGmail, m7a, batches, matchers, queue, sent)}
     *
     * @param profileBuilder  syncs the profile folders into {@code profiles}
     * @param profiles        M7d, read after the sync
     * @param emails          M1
     * @param processedEmails M7a, the same store {@code batches} marks processed emails in
     * @param batches         M2-M4, saving into M7c and M7a
     * @param matchers        M6
     * @param queue           M7e, which also tells which papers M6 has judged already
     * @param sent            the papers already emailed to their students, never scored or queued again
     */
    public PipelineRunner(ProfileBuilder profileBuilder, ProfileStore profiles, EmailSource emails,
            EmailCacheStore processedEmails, AlertBatchProcessor batches, MatcherFactory matchers,
            ScoredQueueStore queue, SentPaperStore sent) {
        this(profileBuilder, profiles, emails, processedEmails, batches, matchers, queue, sent, true);
    }

    /**
     * A runner over these stages and stores.
     * Used by: {@link #atDefaultLocations}, the other constructor, PipelineRunnerTest.
     *
     * @param profileBuilder  M5, synced at the start of each run
     * @param profiles        M7d
     * @param emails          M1, the inbox
     * @param processedEmails M7a
     * @param batches         M2-M4 for a batch of emails
     * @param matchers        builds the M6 matcher for the synced profiles
     * @param queue           M7e
     * @param sent            the sent-paper record; sent papers are never queued again
     * @param markRead        whether processed alerts are marked read in Gmail (false leaves them unread, for testing)
     */
    public PipelineRunner(ProfileBuilder profileBuilder, ProfileStore profiles, EmailSource emails,
            EmailCacheStore processedEmails, AlertBatchProcessor batches, MatcherFactory matchers,
            ScoredQueueStore queue, SentPaperStore sent, boolean markRead) {
        this.profileBuilder = profileBuilder;
        this.profiles = profiles;
        this.emails = emails;
        this.processedEmails = processedEmails;
        this.batches = batches;
        this.matchers = matchers;
        this.queue = queue;
        this.sent = sent;
        this.markRead = markRead;
    }

    /**
     * {@return the real pipeline: Gmail, the live enrichment services and the stores in {@code ~/.litroute/data}}
     * Used by: PipelinePane (M8) Run / Clear and re-run, PipelineRunnerCli.
     *
     * @param provider  the SPECTER2 model; the caller closes it
     * @param mode      how to find the papers in an email
     * @param maxUnread the most unread messages to examine per run
     * @param markRead  whether to mark processed alerts read
     */
    public static PipelineRunner atDefaultLocations(EmbeddingProvider provider, ExtractionMode mode, int maxUnread,
            boolean markRead) {
        ProfileStore profiles = ProfileStore.atDefaultLocation();
        EmailCacheStore processedEmails = EmailCacheStore.atDefaultLocation();
        OnlinePaperEnricher online = OnlinePaperEnricher.create(PdfDownloader.defaultDirectory());
        AlertBatchProcessor.ExtractorFactory extractors = switch (mode) {
            case PATTERN -> lookups -> new PaperExtractor(new CachingPaperEnricher(online, lookups));
            case LLM -> {
                AiTitleExtractor aiTitles = AiTitleExtractor.fromEnvironment();
                yield lookups -> new PaperExtractor(new CachingPaperEnricher(online, lookups), aiTitles);
            }
        };
        AlertBatchProcessor batches = new AlertBatchProcessor(extractors,
                EnrichmentCacheStore.atDefaultLocation(), processedEmails);
        return new PipelineRunner(new ProfileBuilder(ProfileBuilder.defaultProfilesRoot(), profiles, provider),
                profiles, new GmailEmailSource(maxUnread), processedEmails, batches,
                students -> new RelevanceScorer(provider, students, PriorityAuthorStore.atDefaultLocation().load()),
                ScoredQueueStore.atDefaultLocation(), SentPaperStore.atDefaultLocation(), markRead);
    }

    /**
     * Undoes every run so far, so the next run processes the same alerts from scratch. First the emails M7a holds
     * are marked unread in Gmail; only if that worked are the review queue (decisions and PDF copies), M7c and
     * finally M7a cleared. A Gmail failure changes nothing; a crash part-way leaves M7a for last, so its emails
     * are skipped, never lost. Downloaded PDFs and the sent-paper record are kept.
     * Used by: PipelinePane (M8) Clear and re-run, before run().
     *
     * @param credentials the Gmail account (not cleared)
     * @param progress    receives progress lines
     * @return how many emails were processed and marked unread, and how many queue entries were discarded
     * @throws IOException if Gmail or a store failed; if Gmail failed, nothing is cleared
     */
    public ResetReport reset(GmailCredentials credentials, Consumer<String> progress) throws IOException {
        Set<String> processed = processedEmails.load();
        progress.accept("Marking the " + processed.size() + " processed alert email(s) unread in Gmail ("
                + credentials.emailAddress() + ")...");
        int unread = emails.markUnread(credentials, processed);
        progress.accept(unread + " of " + processed.size() + " are unread again"
                + (unread < processed.size() ? "; the rest are no longer in the inbox and won't be fetched again." : "."));
        // Only now that Gmail agreed: clear M7e, then M7c and M7a (last).
        int queued = queue.clear();
        batches.forgetAll();
        progress.accept("Cleared the review queue (" + queued + " paper(s)), the extracted papers and the processed "
                + "emails.");
        return new ResetReport(processed.size(), unread, queued);
    }

    /**
     * Runs the pipeline once, updating M7d, M7a, M7c and M7e.
     * Used by: PipelinePane (M8) Run, PipelineRunnerCli.
     *
     * @param credentials the Gmail account (not cleared)
     * @param progress    receives progress lines
     * @return what the run did
     * @throws IOException if a store, the local model or the matcher failed
     */
    public Report run(GmailCredentials credentials, Consumer<String> progress) throws IOException {
        // 1. M5: embed new or changed profile PDFs into M7d.
        progress.accept("Syncing profiles with their folders...");
        BuildReport built = profileBuilder.syncAll();
        Map<String, Profile> students = profiles.loadAll();
        progress.accept(describe(built, students.size()));

        // 2. M1: unread alerts not yet in M7a.
        progress.accept("Fetching new alert emails from Gmail (" + credentials.emailAddress() + ")...");
        List<RawEmail> alerts = emails.fetchNewAlerts(credentials, processedEmails.load());
        progress.accept(alerts.size() + " new alert email(s).");
        if (!alerts.isEmpty()) {
            progress.accept("Extracting, enriching and deduplicating their papers...");
        }
        // 3. M2-M4: extract, enrich, dedup -> M7c, then M7a. 4. M1: mark read only after M7a holds them.
        List<PaperCandidate> held = batches.process(alerts);
        if (!alerts.isEmpty() && !markRead) {
            progress.accept("Left " + alerts.size() + " alert email(s) unread in Gmail.");
        } else if (!alerts.isEmpty()) {
            int marked = emails.markRead(credentials, alerts.stream().map(RawEmail::messageId).toList());
            progress.accept("Marked " + marked + " of " + alerts.size() + " alert email(s) as read in Gmail.");
        }

        // 5. M6 on papers neither queued (matched or not) nor sent: the queue holds every paper M6 has judged.
        Set<String> done = new HashSet<>();
        for (ScoredPaper queued : queue.load()) {
            done.add(ScoredPaper.paperKey(queued.candidate()));
        }
        sent.load().forEach(entry -> done.add(ScoredPaper.paperKey(entry.paper().candidate())));
        List<PaperCandidate> fresh = held.stream().filter(paper -> !done.contains(ScoredPaper.paperKey(paper))).toList();
        progress.accept(held.size() + " deduplicated paper(s) held, " + fresh.size() + " not scored yet.");
        if (fresh.isEmpty()) {
            return new Report(built, alerts.size(), held.size(), 0, 0, 0, 0, queue.load().size(), 0);
        }
        if (students.isEmpty()) {
            progress.accept("No profiles yet, so nothing was scored: add a folder of PDFs per student under "
                    + ProfileBuilder.defaultProfilesRoot() + " and run again.");
            return new Report(built, alerts.size(), held.size(), 0, fresh.size(), 0, 0, queue.load().size(), 0);
        }

        progress.accept("Matching " + fresh.size() + " paper(s) against " + students.size() + " profile(s)...");
        List<ScoredPaper> judged = matchers.create(students.values()).matchAll(fresh);
        List<ScoredPaper> matched = judged.stream().filter(paper -> paper.status() != ReviewStatus.UNMATCHED).toList();
        List<ScoredPaper> unmatched = judged.stream().filter(paper -> paper.status() == ReviewStatus.UNMATCHED).toList();
        List<ScoredPaper> updated = queue.addAll(judged);

        int recommended = (int) matched.stream().filter(ScoredPaper::recommended).count();
        Map<String, List<ScoredPaper>> byStudent = new LinkedHashMap<>();
        students.keySet().forEach(name -> byStudent.put(name, matched.stream()
                .filter(paper -> paper.profileName().equals(name)).toList()));
        for (Map.Entry<String, List<ScoredPaper>> entry : byStudent.entrySet()) {
            long picked = entry.getValue().stream().filter(ScoredPaper::recommended).count();
            progress.accept("  " + entry.getKey() + ": " + entry.getValue().size() + " matched, " + picked + " recommended");
        }
        progress.accept(matched.size() + " match(es) queued for review; the queue holds " + updated.size() + ".");
        long byListedAuthors = matched.stream().filter(paper -> paper.listedAuthor().isPresent()).count();
        if (byListedAuthors > 0) {
            progress.accept(byListedAuthors + " of them by authors on your list.");
        }
        if (!unmatched.isEmpty()) {
            progress.accept(unmatched.size() + " paper(s) were not close enough to any student's work: listed under "
                    + "Dropped as \"not matched\", where you can approve one or move it to a student.");
        }
        return new Report(built, alerts.size(), held.size(), fresh.size(), 0, matched.size(), recommended,
                updated.size(), unmatched.size());
    }

    /**
     * Input: the profile sync's report and how many profiles are stored.
     * Output: a one-line summary for the progress log.
     * Used by: {@link #run}.
     * Example: -> "8 profile(s) ready, 2 new or changed PDF(s) embedded, removed bob."
     */
    private static String describe(BuildReport built, int stored) {
        int embedded = built.profiles().stream().mapToInt(ProfileReport::embedded).sum();
        int skipped = built.profiles().stream().mapToInt(report -> report.skipped().size()).sum();
        StringBuilder text = new StringBuilder(stored + " profile(s) ready");
        if (embedded > 0) {
            text.append(", ").append(embedded).append(" new or changed PDF(s) embedded");
        }
        if (skipped > 0) {
            text.append(", ").append(skipped).append(" unreadable PDF(s) skipped");
        }
        if (!built.removed().isEmpty()) {
            text.append(", removed ").append(String.join(", ", built.removed()));
        }
        return text.append('.').toString();
    }
}
