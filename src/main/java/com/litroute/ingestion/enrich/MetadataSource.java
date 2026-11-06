package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.util.Optional;

/** One bibliographic API (Crossref, OpenAlex). */
public interface MetadataSource {

    /** Minimum title similarity (0-1) for an API hit to be accepted as the paper the alert named. */
    double ACCEPT_THRESHOLD = 0.88;

    /** {@return the source's name, for logs and failure counting} */
    String name();

    /**
     * Looks the paper up.
     *
     * @param query what is known about the paper (title, authors, maybe DOI and venue)
     * @return its metadata; empty if the source answered but has no confident match
     * @throws IOException if the source is unreachable or failing (offline, 5xx, 429)
     */
    Optional<PaperMetadata> lookup(PaperQuery query) throws IOException;
}
