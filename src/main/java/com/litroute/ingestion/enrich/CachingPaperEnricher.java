package com.litroute.ingestion.enrich;

import com.litroute.ingestion.extract.LinkKind;
import com.litroute.ingestion.extract.PaperEnricher;
import com.litroute.model.PaperCandidate;
import com.litroute.util.Titles;
import java.nio.file.Files;
import java.util.Map;

/**
 * Puts M7c's lookup cache in front of another {@link PaperEnricher}: a paper whose title is already in
 * the cache is answered from it with no network access at all; anything else goes to the delegate.
 *
 * Only results with a DOI are remembered. A lookup that found nothing - typically because the APIs were
 * down or throttling - must not be cached, or the outage would stick and the paper would never be
 * looked up again. On a cache hit the email still wins: the cached data only fills fields the new email
 * left empty, exactly as a fresh lookup would.
 *
 * The map is the live cache from {@code EnrichmentCacheStore.Contents.lookups()}; the caller saves the
 * store after the run.
 */
public final class CachingPaperEnricher implements PaperEnricher {

    private final PaperEnricher delegate;
    private final Map<String, PaperCandidate> cache;

    /**
     * Input: the enricher to call on a cache miss, and M7c's live lookups map (written to in place).
     * Output: a caching enricher.
     * Used by: PipelineRunner.atDefaultLocations (M9), via AlertBatchProcessor's ExtractorFactory.
     * Example: {@code new CachingPaperEnricher(new OnlinePaperEnricher(..), contents.lookups())}
     */
    public CachingPaperEnricher(PaperEnricher delegate, Map<String, PaperCandidate> cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    /**
     * Input: a paper as parsed from an email, and its link kind.
     * Output: the paper filled from the cache (by compacted title) if present and its PDF still exists, else from the delegate.
     * Used by: PaperExtractor.extract (M2).
     * Example: "Wave Loads: A Review" cached with DOI -> answered offline; a miss with a DOI found -> cached for next time
     */
    @Override
    public PaperCandidate enrich(PaperCandidate paper, LinkKind linkKind) {
        // A title with no Latin letters or digits normalizes to "" and would collide with every other one.
        String key = Titles.compact(paper.title());
        if (key.isEmpty()) {
            return delegate.enrich(paper, linkKind);
        }

        PaperCandidate cached = cache.get(key);
        if (cached != null && pdfStillOnDisk(cached)) {
            return reuse(paper, cached);
        }

        PaperCandidate enriched = delegate.enrich(paper, linkKind);
        // Only cache real hits: a miss is often an API outage and must be retried next run.
        if (enriched.doi().isPresent()) {
            cache.put(key, enriched);
        }
        return enriched;
    }

    /**
     * A cached PDF that has since been deleted makes the entry stale, so the paper is looked up again.
     * Input: a cached candidate.
     * Output: false only if it names a PDF that is no longer on disk.
     * Used by: {@link #enrich}.
     * Example: pdfPath pdfs/x.pdf deleted -> false; no pdfPath -> true
     */
    private static boolean pdfStillOnDisk(PaperCandidate cached) {
        return cached.pdfPath().map(Files::isRegularFile).orElse(true);
    }

    /**
     * Input: the paper from the new email and the cached enriched copy.
     * Output: the new paper, with empty fields filled from the cache (articleType always from the new email).
     * Used by: {@link #enrich} on a cache hit.
     * Example: new(venue empty, msg id M2) + cached(venue "Ocean Eng.", msg id M1) -> venue "Ocean Eng.", msg id M2
     */
    private static PaperCandidate reuse(PaperCandidate paper, PaperCandidate cached) {
        return new PaperCandidate(
                paper.sourceMessageId(),
                paper.title(),
                paper.authors().isEmpty() ? cached.authors() : paper.authors(),
                paper.venue().or(cached::venue),
                paper.year().or(cached::year),
                paper.articleType(),
                paper.doi().or(cached::doi),
                paper.url().or(cached::url),
                paper.abstractSnippet().or(cached::abstractSnippet),
                paper.pdfPath().or(cached::pdfPath));
    }
}
