package com.litroute.ingestion.enrich;

import java.util.List;
import java.util.Optional;

/** What is known about a paper going into a lookup: its title always, its DOI when the email revealed one. */
public record PaperQuery(String title, List<String> authors, Optional<String> doi) {

    public PaperQuery withDoi(String newDoi) {
        return new PaperQuery(title, authors, Optional.of(newDoi));
    }
}
