package com.litroute.ingestion.enrich;

import java.util.List;
import java.util.Optional;

/**
 * What is known about a paper going into a lookup: its title always, its DOI when the email revealed one,
 * and the journal when the email named it (to tell a journal article from its preprint).
 */
public record PaperQuery(String title, List<String> authors, Optional<String> doi, Optional<String> venue) {

    /** A query that knows no journal. */
    public PaperQuery(String title, List<String> authors, Optional<String> doi) {
        this(title, authors, doi, Optional.empty());
    }

    public PaperQuery withDoi(String newDoi) {
        return new PaperQuery(title, authors, Optional.of(newDoi), venue);
    }
}
