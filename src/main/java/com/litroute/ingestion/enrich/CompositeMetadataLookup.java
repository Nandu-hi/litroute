package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks every source (Crossref, then OpenAlex) and merges what they return: earlier sources win
 * field by field, later ones fill gaps and add PDF locations.
 *
 * A source that is offline or failing never fails the lookup - the others still answer. Once a
 * source has failed {@value #MAX_CONSECUTIVE_FAILURES} times in a row it is skipped for the rest of this
 * object's life (one pipeline run), so an outage costs a few timeouts instead of one per paper.
 *
 * If an earlier source discovers the paper's DOI from its title, later sources are queried by that
 * DOI, which is exact, instead of by title.
 */
public final class CompositeMetadataLookup {

    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final Logger LOG = Logger.getLogger(CompositeMetadataLookup.class.getName());

    private final List<MetadataSource> sources;
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();

    /**
     * Input: the sources in priority order.
     * Output: a lookup asking each in turn (failure counts start at 0).
     * Used by: OnlinePaperEnricher, with [CrossrefSource, OpenAlexSource].
     * Example: {@code new CompositeMetadataLookup(List.of(crossref, openAlex))}
     */
    public CompositeMetadataLookup(List<MetadataSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * Input: what is known about the paper.
     * Output: every source's answer merged (earlier wins per field); empty if none found it or all were unreachable.
     * Used by: OnlinePaperEnricher.enrich (M3).
     * Example: Crossref finds DOI 10.1/x by title, OpenAlex (queried by that DOI) adds an OA PDF -> merged metadata with both
     */
    public Optional<PaperMetadata> find(PaperQuery query) {
        Optional<PaperMetadata> merged = Optional.empty();
        PaperQuery current = query;
        for (MetadataSource source : sources) {
            // Circuit breaker: a source that keeps failing is skipped for the rest of the run.
            if (consecutiveFailures.getOrDefault(source.name(), 0) >= MAX_CONSECUTIVE_FAILURES) {
                continue;
            }
            try {
                Optional<PaperMetadata> found = source.lookup(current);
                consecutiveFailures.remove(source.name());
                if (found.isPresent()) {
                    merged = Optional.of(merged.map(m -> m.mergedWith(found.get())).orElse(found.get()));
                    if (current.doi().isEmpty() && merged.get().doi().isPresent()) {
                        current = current.withDoi(merged.get().doi().get());
                    }
                }
            } catch (IOException | RuntimeException e) {
                int failures = consecutiveFailures.merge(source.name(), 1, Integer::sum);
                LOG.log(Level.WARNING, source.name() + " lookup failed (" + failures + " in a row)"
                        + (failures >= MAX_CONSECUTIVE_FAILURES ? "; skipping it for the rest of this run" : "")
                        + ": " + e);
            }
        }
        return merged;
    }
}
