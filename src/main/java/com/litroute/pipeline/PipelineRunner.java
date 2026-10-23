package com.litroute.pipeline;

import com.litroute.embedding.EmbeddingProvider;
import com.litroute.ingestion.email.EmailSource;
import com.litroute.ingestion.email.GmailEmailSource;
import com.litroute.ingestion.enrich.CachingPaperEnricher;
import com.litroute.ingestion.enrich.OnlinePaperEnricher;
import com.litroute.ingestion.enrich.PdfDownloader;
import com.litroute.ingestion.extract.AlertBatchProcessor;
import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.RawEmail;
import com.litroute.model.ScoredPaper;
import com.litroute.profile.ProfileBuilder;
import com.litroute.profile.ProfileBuilder.BuildReport;
import com.litroute.profile.ProfileBuilder.ProfileReport;
import com.litroute.scoring.PaperMatcher;
import com.litroute.scoring.RelevanceScorer;
import com.litroute.scoring.ai.AiPaperMatcher;
import com.litroute.scoring.ai.GroqClient;
import com.litroute.security.GmailCredentials;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.ProfileStore;
import com.litroute.store.ScoredPaperLog;
import com.litroute.store.ScoredQueueStore;
import java.io.IOException;
import java.util.Collection;
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
 *   <li>match the papers no matcher has seen yet against the profiles (M6) and add the matches to the
 *       review queue (M7e), then record those papers as scored ({@link ScoredPaperLog}).
 * </ol>
 *
 * The batch M6 sees is every deduplicated paper not yet scored, not just this run's emails, so papers
 * left unscored by an earlier run that failed after saving M7c (no profiles yet, or the AI service down)
 * are scored by the next one. A paper already in the review queue counts as scored. Each step's stores
 * are written in the order that makes a crash repeat work rather than skip it.
 */
public final class PipelineRunner {

    /** Which M6 matcher scores the papers. */
    public enum MatcherKind {
        /** {@link RelevanceScorer}: the papers' words and SPECTER2, offline, one student per paper. */
        HYBRID("Words + SPECTER2 (offline)"),
        /** {@link AiPaperMatcher} on Groq; needs {@value GroqClient#KEY_VARIABLE}. */
        AI("Groq language model");

        private final String label;

        MatcherKind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Builds the M6 matcher for the profiles as they stand after this run's sync. */
    @FunctionalInterface
    public interface MatcherFactory {
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
     * @param matches      (paper, student) pairs added to or refreshed in the review queue
     * @param recommended  of those, how many were recommended
     * @param queueSize    entries in the review queue after the run
     */
    public record Report(BuildReport profiles, int newAlerts, int papersHeld, int papersScored, int unscored,
            int matches, int recommended, int queueSize) {
    }

    private final ProfileBuilder profileBuilder;
    private final ProfileStore profiles;
    private final EmailSource emails;
    private final EmailCacheStore processedEmails;
    private final AlertBatchProcessor batches;
    private final MatcherFactory matchers;
    private final ScoredQueueStore queue;
    private final ScoredPaperLog scored;
    private final boolean markRead;

    /**
     * @param profileBuilder  syncs the profile folders into {@code profiles}
     * @param profiles        M7d, read after the sync
     * @param emails          M1
     * @param processedEmails M7a, the same store {@code batches} marks processed emails in
     * @param batches         M2-M4, saving into M7c and M7a
     * @param matchers        M6
     * @param queue           M7e
     * @param scored          the papers M6 has matched already
     */
    public PipelineRunner(ProfileBuilder profileBuilder, ProfileStore profiles, EmailSource emails,
            EmailCacheStore processedEmails, AlertBatchProcessor batches, MatcherFactory matchers,
            ScoredQueueStore queue, ScoredPaperLog scored) {
        this(profileBuilder, profiles, emails, processedEmails, batches, matchers, queue, scored, true);
    }

    /**
     * As above.
     *
     * @param markRead whether processed alerts are marked read in Gmail; false leaves them unread, for testing
     */
    public PipelineRunner(ProfileBuilder profileBuilder, ProfileStore profiles, EmailSource emails,
            EmailCacheStore processedEmails, AlertBatchProcessor batches, MatcherFactory matchers,
            ScoredQueueStore queue, ScoredPaperLog scored, boolean markRead) {
        this.profileBuilder = profileBuilder;
        this.profiles = profiles;
        this.emails = emails;
        this.processedEmails = processedEmails;
        this.batches = batches;
        this.matchers = matchers;
        this.queue = queue;
        this.scored = scored;
        this.markRead = markRead;
    }

    /**
     * The real pipeline over the default locations under {@code ~/.litroute}: Gmail, the live enrichment
     * services, and the stores in {@code ~/.litroute/data}.
     *
     * @param provider  the SPECTER2 model, for the profile sync (and the hybrid matcher); the caller closes it
     * @param maxUnread how many unread, not yet processed Gmail messages a run examines, at least 1
     * @param markRead  whether processed alerts are marked read in Gmail; false leaves them unread, for testing
     */
    public static PipelineRunner atDefaultLocations(EmbeddingProvider provider, MatcherKind kind, int maxUnread,
            boolean markRead) {
        ProfileStore profiles = ProfileStore.atDefaultLocation();
        EmailCacheStore processedEmails = EmailCacheStore.atDefaultLocation();
        OnlinePaperEnricher online = OnlinePaperEnricher.create(PdfDownloader.defaultDirectory());
        AlertBatchProcessor batches = new AlertBatchProcessor(
                lookups -> new PaperExtractor(new CachingPaperEnricher(online, lookups)),
                EnrichmentCacheStore.atDefaultLocation(), processedEmails);
        return new PipelineRunner(new ProfileBuilder(ProfileBuilder.defaultProfilesRoot(), profiles, provider),
                profiles, new GmailEmailSource(maxUnread), processedEmails, batches, matcher(kind, provider),
                ScoredQueueStore.atDefaultLocation(), ScoredPaperLog.atDefaultLocation(), markRead);
    }

    /** The factory for the chosen matcher; the AI one reads its key from the environment when used. */
    public static MatcherFactory matcher(MatcherKind kind, EmbeddingProvider provider) {
        return switch (kind) {
            case HYBRID -> students -> new RelevanceScorer(provider, students);
            case AI -> students -> AiPaperMatcher.of(GroqClient.fromEnvironment(), students);
        };
    }

    /**
     * Runs the pipeline once.
     *
     * @param credentials the Gmail account to read; not cleared
     * @param progress    told what each step is doing and what it found, as it happens
     * @throws IOException if a store cannot be read or written, or the matcher's service could not answer;
     *         the steps already finished keep their results, and the next run picks up from there
     */
    public Report run(GmailCredentials credentials, Consumer<String> progress) throws IOException {
        progress.accept("Syncing profiles with their folders...");
        BuildReport built = profileBuilder.syncAll();
        Map<String, Profile> students = profiles.loadAll();
        progress.accept(describe(built, students.size()));

        progress.accept("Fetching new alert emails from Gmail (" + credentials.emailAddress() + ")...");
        List<RawEmail> alerts = emails.fetchNewAlerts(credentials, processedEmails.load());
        progress.accept(alerts.size() + " new alert email(s).");
        if (!alerts.isEmpty()) {
            progress.accept("Extracting, enriching and deduplicating their papers...");
        }
        List<PaperCandidate> held = batches.process(alerts);
        if (!alerts.isEmpty() && !markRead) {
            progress.accept("Left " + alerts.size() + " alert email(s) unread in Gmail.");
        } else if (!alerts.isEmpty()) {
            int marked = emails.markRead(credentials, alerts.stream().map(RawEmail::messageId).toList());
            progress.accept("Marked " + marked + " of " + alerts.size() + " alert email(s) as read in Gmail.");
        }

        Set<String> done = scored.load();
        for (ScoredPaper queued : queue.load()) {
            done.add(ScoredPaper.paperKey(queued.candidate()));
        }
        List<PaperCandidate> fresh = held.stream().filter(paper -> !done.contains(ScoredPaper.paperKey(paper))).toList();
        progress.accept(held.size() + " deduplicated paper(s) held, " + fresh.size() + " not scored yet.");
        if (fresh.isEmpty()) {
            return new Report(built, alerts.size(), held.size(), 0, 0, 0, 0, queue.load().size());
        }
        if (students.isEmpty()) {
            progress.accept("No profiles yet, so nothing was scored: add a folder of PDFs per student under "
                    + ProfileBuilder.defaultProfilesRoot() + " and run again.");
            return new Report(built, alerts.size(), held.size(), 0, fresh.size(), 0, 0, queue.load().size());
        }

        progress.accept("Matching " + fresh.size() + " paper(s) against " + students.size() + " profile(s)...");
        Map<String, List<ScoredPaper>> matches = matchers.create(students.values()).match(fresh);
        List<ScoredPaper> matched = matches.values().stream().flatMap(List::stream).toList();
        List<ScoredPaper> updated = queue.addAll(matched);
        scored.addAll(fresh.stream().map(ScoredPaper::paperKey).toList());

        int recommended = (int) matched.stream().filter(ScoredPaper::recommended).count();
        for (Map.Entry<String, List<ScoredPaper>> entry : matches.entrySet()) {
            long picked = entry.getValue().stream().filter(ScoredPaper::recommended).count();
            progress.accept("  " + entry.getKey() + ": " + entry.getValue().size() + " matched, " + picked + " recommended");
        }
        progress.accept(matched.size() + " match(es) queued for review; the queue holds " + updated.size() + ".");
        return new Report(built, alerts.size(), held.size(), fresh.size(), 0, matched.size(), recommended, updated.size());
    }

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
