package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.util.Optional;

/** One bibliographic API (Crossref, OpenAlex). */
public interface MetadataSource {

    /** Minimum title similarity (0-1) for an API hit to be accepted as the paper the alert named. */
    double ACCEPT_THRESHOLD = 0.88;

    /**
     * Input: none.
     * Output: the source's name, for logs and failure counting.
     * Used by: CompositeMetadataLookup.
     * Example: -> {@code "Crossref"}
     */
    String name();

    /**
     * Input: what is known about the paper (title, authors, maybe DOI and venue).
     * Output: its metadata; empty if reachable but no confident match; IOException if unreachable/failing (offline, 5xx, 429).
     * Used by: CompositeMetadataLookup.find (M3).
     * Example: title "Wave loads on piles" -> {@code Optional[PaperMetadata(doi 10.1/x, ...)]}; nonsense title -> empty
     */
    Optional<PaperMetadata> lookup(PaperQuery query) throws IOException;
}
