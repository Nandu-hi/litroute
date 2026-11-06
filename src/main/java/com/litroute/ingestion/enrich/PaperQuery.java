package com.litroute.ingestion.enrich;

import java.util.List;
import java.util.Optional;

/**
 * What is known about a paper going into a lookup.
 *
 * @param title   the title; always known
 * @param authors the authors, if the email listed them
 * @param doi     the DOI, when the email revealed one
 * @param venue   the journal, when the email named it (tells a journal article from its preprint)
 */
public record PaperQuery(String title, List<String> authors, Optional<String> doi, Optional<String> venue) {

    /**
     * A query with no journal.
     *
     * @param title   the title
     * @param authors the authors
     * @param doi     the DOI, if known
     */
    public PaperQuery(String title, List<String> authors, Optional<String> doi) {
        this(title, authors, doi, Optional.empty());
    }

    /**
     * {@return the same query with this DOI}, so later sources look up by exact DOI.
     *
     * @param newDoi a DOI found by an earlier source
     */
    public PaperQuery withDoi(String newDoi) {
        return new PaperQuery(title, authors, Optional.of(newDoi), venue);
    }
}
