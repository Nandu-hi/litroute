package com.litroute.ingestion.extract;

import com.litroute.ingestion.dedup.Deduplicator;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.EnrichmentCacheStore.Contents;
import java.io.IOException;
import java.io.UncheckedIOException;
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
         * {@return the extractor for this batch, never null}
         *
         * @param lookupCache the live lookup cache loaded from M7c; entries added to it are saved with the batch
         */
        PaperExtractor create(Map<String, PaperCandidate> lookupCache);
    }

    private final ExtractorFactory extractorFactory;
    private final EnrichmentCacheStore enrichmentCache;
    private final EmailCacheStore processedEmails;
    private final Deduplicator deduplicator = new Deduplicator();

    /**
     * A processor whose extractor is built fresh at the start of each batch.
     *
     * @param extractorFactory builds the extractor from M7c's lookup cache
     * @param enrichmentCache  M7c
     * @param processedEmails  M7a
     */
    public AlertBatchProcessor(ExtractorFactory extractorFactory, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this.extractorFactory = extractorFactory;
        this.enrichmentCache = enrichmentCache;
        this.processedEmails = processedEmails;
    }

    /**
     * A processor using one fixed extractor, which ignores the lookup cache, for every batch.
     *
     * @param extractor       the extractor
     * @param enrichmentCache M7c
     * @param processedEmails M7a
     */
    public AlertBatchProcessor(PaperExtractor extractor, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this(lookups -> extractor, enrichmentCache, processedEmails);
    }

    /**
     * Extracts, enriches and deduplicates a batch, then saves M7c and only then M7a. Re-processing an email
     * replaces its earlier papers.
     * Example: M7c holds [A]; batch with papers [A', B] -> returns [A (merged with A'), B]; email ID added to M7a
     *
     * @param emails this batch's alert emails
     * @return every paper now in M7c, in first-seen order
     * @throws IOException if a store fails or the local model can't be reached; the emails then stay unrecorded
     */
    public List<PaperCandidate> process(List<RawEmail> emails) throws IOException {
        // Load first so a corrupt store fails before any lookups or downloads are spent on this batch.
        Contents cache = enrichmentCache.load();
        Set<String> processedIds = processedEmails.load();
        PaperExtractor extractor = extractorFactory.create(cache.lookups());

        List<PaperCandidate> extracted = new ArrayList<>();
        Set<String> batchIds = new HashSet<>();
        try {
            for (RawEmail email : emails) {
                extracted.addAll(extractor.extract(email));
                batchIds.add(email.messageId());
            }
        } catch (UncheckedIOException modelUnreachable) {
            // Nothing is saved yet, so the whole batch is simply read again on the next run.
            throw modelUnreachable.getCause();
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
     * Forgets every batch: M7c's papers and lookups, then M7a, so a crash in between leaves the emails recorded
     * (skipped, not lost). Used by Clear and re-run, after the emails are marked unread.
     *
     * @throws IOException if a store can't be written
     */
    public void forgetAll() throws IOException {
        enrichmentCache.save(Contents.empty());
        processedEmails.save(Set.of());
    }
}
