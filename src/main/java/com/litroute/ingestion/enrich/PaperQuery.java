package com.litroute.ingestion.enrich;

import java.util.List;
import java.util.Optional;

/**
 * What is known about a paper going into a lookup: its title always, its DOI when the email revealed one,
 * and the journal when the email named it (to tell a journal article from its preprint).
 */
public record PaperQuery(String title, List<String> authors, Optional<String> doi, Optional<String> venue) {

    /**
     * Input: title, authors and DOI (if known).
     * Output: a query with no journal.
     * Used by: tests and sources that don't need the venue.
     * Example: {@code new PaperQuery("Wave loads", List.of(), Optional.empty()).venue()} -> empty
     */
    public PaperQuery(String title, List<String> authors, Optional<String> doi) {
        this(title, authors, doi, Optional.empty());
    }

    /**
     * Input: a DOI found by an earlier source.
     * Output: the same query, now with that DOI.
     * Used by: CompositeMetadataLookup.find, so later sources look up by exact DOI.
     * Example: {@code q.withDoi("10.1/x").doi()} -> {@code Optional["10.1/x"]}
     */
    public PaperQuery withDoi(String newDoi) {
        return new PaperQuery(title, authors, Optional.of(newDoi), venue);
    }
}
