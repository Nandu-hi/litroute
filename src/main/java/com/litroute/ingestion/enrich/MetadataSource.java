package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.util.Optional;

/** One bibliographic API (Crossref, OpenAlex). */
public interface MetadataSource {

    /** Minimum title similarity (0-1) for an API hit to be accepted as the paper the alert named. */
    double ACCEPT_THRESHOLD = 0.88;

    String name();

    /**
     * @return the paper's metadata, or empty if the source was reachable but has no confident match
     * @throws IOException if the source is unreachable or failing (offline, timeout, HTTP 5xx/429) -
     *         distinct from "no match", so callers can fall back to another source
     */
    Optional<PaperMetadata> lookup(PaperQuery query) throws IOException;
}
