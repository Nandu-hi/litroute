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
 * Runs a whole batch of alert emails through M2 (extract), M3 (enrich) and M4 (deduplicate), then
 * persists the outcome exactly once:
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
     * Builds the extractor around M7c's live lookup cache, which only exists once the store has been
     * read. Wiring it this way is what lets a lookup made during the run be written back with the rest
     * of the batch: pass {@code lookups -> new PaperExtractor(new CachingPaperEnricher(online, lookups))}.
     */
    @FunctionalInterface
    public interface ExtractorFactory {
        PaperExtractor create(Map<String, PaperCandidate> lookupCache);
    }

    private final ExtractorFactory extractorFactory;
    private final EnrichmentCacheStore enrichmentCache;
    private final EmailCacheStore processedEmails;
    private final Deduplicator deduplicator = new Deduplicator();

    /**
     * @param extractorFactory builds the extractor once M7c's lookup cache has been loaded
     * @param enrichmentCache M7c, holding the enriched papers and the lookup cache
     * @param processedEmails M7a, holding the IDs of emails already handled
     */
    public AlertBatchProcessor(ExtractorFactory extractorFactory, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this.extractorFactory = extractorFactory;
        this.enrichmentCache = enrichmentCache;
        this.processedEmails = processedEmails;
    }

    /** For an extractor that has no use for the lookup cache (offline runs, tests). */
    public AlertBatchProcessor(PaperExtractor extractor, EnrichmentCacheStore enrichmentCache,
            EmailCacheStore processedEmails) {
        this(lookups -> extractor, enrichmentCache, processedEmails);
    }

    /**
     * @return every paper now held in M7c, enriched and deduplicated, in first-seen order
     * @throws IOException if a store can't be read or written; if M7c can't be saved the emails are
     *         left unmarked so the batch is retried
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
}
