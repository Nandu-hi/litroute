package com.litroute.ingestion.extract;

import com.litroute.ingestion.dedup.Deduplicator;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.EnrichmentCacheStore.Contents;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run a batch of alert emails through M2 (extract), M3 (enrich) and M4 (deduplicate), then persist the
 * outcome exactly once:
 *
 * <ol>
 *   <li>extract + enrich every email - nothing is written to disk in this phase;
 *   <li>merge the batch into the papers already held, deduplicated, and save the result to M7c in one
 *       write, together with the lookup cache the enrichment filled;
 *   <li>save the processed email IDs to M7a in one write, last.
 * </ol>
 *
 * M7c is the only candidate store: a paper reaches disk only once it is enriched and deduplicated, so
 * there is no half-finished snapshot that a later stage would have to clean up.
 *
 * The write order is the design doc's crash-consistency rule: the email cache is updated last, so a
 * crash at any point leaves the emails unmarked and the whole batch is simply processed again on the
 * next run (PDFs already downloaded are reused from disk, not fetched twice) instead of being silently
 * skipped. Re-running a batch replaces that batch's earlier entries rather than duplicating them.
 */
public final class AlertBatchProcessor {

    /**
     * Build the extractor around M7c's live lookup cache, which only exists once the store has been
     * read. Wiring it this way is what lets a lookup made during the run be written back with the rest
     * of the batch: pass {@code lookups -> new PaperExtractor(new CachingPaperEnricher(online, lookups))}.
     */
    @FunctionalInterface
    public interface ExtractorFactory {
        /**
         * Create the extractor to use for one batch.
         *
         * @param lookupCache the live, mutable lookup cache loaded from M7c, keyed by lookup key; must
         *        not be {@code null}. Entries the extractor adds are persisted with the batch.
         * @return the extractor for this batch; never {@code null}
         */
        PaperExtractor create(Map<String, PaperCandidate> lookupCache);
    }

    private final ExtractorFactory extractorFactory;
    private final EnrichmentCacheStore enrichmentCache;
    private final EmailCacheStore processedEmails;
    private final Deduplicator deduplicator = new Deduplicator();

    /**
     * Create a processor whose extractor is built from M7c's lookup cache at the start of each run.
     *
     * @param extractorFactory builds the extractor once M7c's lookup cache has been loaded; must not
     *        be {@code null}
     * @param enrichmentCache M7c, holding the enriched papers and the lookup cache; must not be
     *        {@code null}
     * @param processedEmails M7a, holding the IDs of emails already handled; must not be {@code null}
     */
    public AlertBatchProcessor(ExtractorFactory extractorFactory, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this.extractorFactory = extractorFactory;
        this.enrichmentCache = enrichmentCache;
        this.processedEmails = processedEmails;
    }

    /**
     * Create a processor around a fixed extractor that ignores the lookup cache (offline runs, tests).
     *
     * @param extractor the extractor used for every run; must not be {@code null}
     * @param enrichmentCache M7c, holding the enriched papers and the lookup cache; must not be
     *        {@code null}
     * @param processedEmails M7a, holding the IDs of emails already handled; must not be {@code null}
     */
    public AlertBatchProcessor(PaperExtractor extractor, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this(lookups -> extractor, enrichmentCache, processedEmails);
    }

    /**
     * Extract, enrich and deduplicate a batch of alert emails, then persist the result to M7c and
     * mark the emails as processed in M7a, in that order.
     *
     * @param emails the alert emails of this batch; must not be {@code null} or contain
     *        {@code null} elements, and each email needs a message ID. May be empty, in which case
     *        the held papers are re-deduplicated and re-saved. Re-processing an email already
     *        stored replaces its earlier papers rather than duplicating them.
     * @return every paper now held in M7c, enriched and deduplicated, in first-seen order
     * @throws IOException if a store can't be read or written; if M7c can't be saved the emails are
     *         left unmarked so the batch is retried
     * @throws NullPointerException if {@code emails} or one of its elements is {@code null}
     */
    public List<PaperCandidate> process(List<RawEmail> emails) throws IOException {
        // Load first so a corrupt store fails before any lookups or downloads are spent on this batch.
        Contents cache = enrichmentCache.load();
        Set<String> processedIds = processedEmails.load();
        PaperExtractor extractor = extractorFactory.create(cache.lookups());

        List<PaperCandidate> extracted = new ArrayList<>();
        Set<String> batchIds = new HashSet<>();
        for (RawEmail email : emails) {
            extracted.addAll(extractor.extract(email));
            batchIds.add(email.messageId());
        }

        // A batch being re-run after a crash re-extracts papers already stored under the same message
        // IDs. Dropping them keeps the re-run's fresher versions rather than merging them into stale ones.
        List<PaperCandidate> held = new ArrayList<>(cache.enriched());
        held.removeIf(candidate -> batchIds.contains(candidate.sourceMessageId()));
        held.addAll(extracted);

        List<PaperCandidate> deduplicated = deduplicator.deduplicate(held);
        enrichmentCache.save(new Contents(deduplicated, cache.lookups()));

        processedIds.addAll(batchIds);
        processedEmails.save(processedIds);
        return deduplicated;
    }

    /**
     * Forgets every batch processed so far: M7c's papers and lookup cache, then M7a's record of which
     * emails were processed, in that order, so a crash in between leaves the emails recorded (skipped,
     * not lost) rather than papers held for emails no longer recorded. Run again, the same emails are
     * extracted and enriched afresh.
     *
     * @throws IOException if a store can't be written
     */
    public void forgetAll() throws IOException {
        enrichmentCache.save(Contents.empty());
        processedEmails.save(Set.of());
    }
}
