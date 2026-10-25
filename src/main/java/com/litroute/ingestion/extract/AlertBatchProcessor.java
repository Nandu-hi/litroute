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
         * Input: the live, mutable lookup cache loaded from M7c (entries added to it are saved with the batch).
         * Output: the extractor for this batch, never null.
         * Used by: {@link AlertBatchProcessor#process}, once per batch.
         * Example: {@code lookups -> new PaperExtractor(new CachingPaperEnricher(online, lookups))}
         */
        PaperExtractor create(Map<String, PaperCandidate> lookupCache);
    }

    private final ExtractorFactory extractorFactory;
    private final EnrichmentCacheStore enrichmentCache;
    private final EmailCacheStore processedEmails;
    private final Deduplicator deduplicator = new Deduplicator();

    /**
     * Input: a factory building the extractor from M7c's lookup cache, the M7c store and the M7a store (all non-null).
     * Output: a processor whose extractor is built fresh at the start of each batch.
     * Used by: PipelineRunner.atDefaultLocations (M9).
     * Example: {@code new AlertBatchProcessor(lookups -> ..., EnrichmentCacheStore.atDefaultLocation(), EmailCacheStore.atDefaultLocation())}
     */
    public AlertBatchProcessor(ExtractorFactory extractorFactory, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this.extractorFactory = extractorFactory;
        this.enrichmentCache = enrichmentCache;
        this.processedEmails = processedEmails;
    }

    /**
     * Input: a fixed extractor (ignores the lookup cache), the M7c store and the M7a store (all non-null).
     * Output: a processor using that extractor for every batch.
     * Used by: tests and offline runs.
     * Example: {@code new AlertBatchProcessor(PaperExtractor.offline(), m7c, m7a)}
     */
    public AlertBatchProcessor(PaperExtractor extractor, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this(lookups -> extractor, enrichmentCache, processedEmails);
    }

    /**
     * Extracts, enriches and deduplicates a batch, then saves M7c and only then M7a. Re-processing an email
     * replaces its earlier papers; an empty batch just re-deduplicates and re-saves what is held.
     * Input: this batch's alert emails (non-null, no null elements).
     * Output: every paper now in M7c, enriched and deduplicated, first-seen order; IOException if a store fails (emails then stay unmarked).
     * Used by: PipelineRunner.run (M9), step 3.
     * Example: M7c holds [A]; batch of 1 email with papers [A', B] -> saves and returns [A (merged with A'), B]; email ID added to M7a
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

        // M7c first, M7a last: a crash in between re-runs the batch instead of skipping it.
        List<PaperCandidate> deduplicated = deduplicator.deduplicate(held);
        enrichmentCache.save(new Contents(deduplicated, cache.lookups()));

        processedIds.addAll(batchIds);
        processedEmails.save(processedIds);
        return deduplicated;
    }

    /**
     * Forgets every batch: M7c's papers and lookups, then M7a, in that order, so a crash in between leaves
     * the emails recorded (skipped, not lost) rather than papers held for emails no longer recorded.
     * Input: none.
     * Output: none; M7c and M7a are empty; IOException if a store can't be written.
     * Used by: PipelineRunner.reset (M9, Clear and re-run), after the emails are marked unread.
     * Example: {@code forgetAll(); process(sameEmails)} -> the same emails are extracted and enriched afresh
     */
    public void forgetAll() throws IOException {
        enrichmentCache.save(Contents.empty());
        processedEmails.save(Set.of());
    }
}
