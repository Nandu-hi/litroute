package com.litroute.ingestion.enrich;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one source (Crossref, OpenAlex, the publisher's page) knows about a paper.
 *
 * @param pdfUrls candidate locations of the PDF, best first; may be paywalled, so the downloader
 *                checks each one really returns a PDF
 */
public record PaperMetadata(
        Optional<String> doi,
        Optional<String> title,
        List<String> authors,
        Optional<String> venue,
        Optional<Integer> year,
        Optional<String> landingUrl,
        List<String> pdfUrls) {

    public static final PaperMetadata EMPTY = new PaperMetadata(Optional.empty(), Optional.empty(), List.of(),
            Optional.empty(), Optional.empty(), Optional.empty(), List.of());

    public PaperMetadata {
        authors = List.copyOf(authors);
        pdfUrls = List.copyOf(pdfUrls);
    }

    /** This metadata wins wherever it has a value; {@code other} only fills gaps and adds extra PDF locations. */
    public PaperMetadata mergedWith(PaperMetadata other) {
        Set<String> pdfs = new LinkedHashSet<>(pdfUrls);
        pdfs.addAll(other.pdfUrls);
        return new PaperMetadata(
                doi.or(() -> other.doi),
                title.or(() -> other.title),
                authors.isEmpty() ? other.authors : authors,
                venue.or(() -> other.venue),
                year.or(() -> other.year),
                landingUrl.or(() -> other.landingUrl),
                new ArrayList<>(pdfs));
    }
}
