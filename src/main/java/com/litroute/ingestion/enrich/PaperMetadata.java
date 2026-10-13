package com.litroute.ingestion.enrich;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one source (Crossref, OpenAlex, the publisher's page) knows about a paper. Every field may be
 * empty: a source states only what it found. Results from several sources are combined with
 * {@link #mergedWith}, most trusted first.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Input:  Crossref's record for DOI 10.3390/jmse12030400
 * Output: PaperMetadata[doi=Optional[10.3390/jmse12030400], title=Optional[Wave Loads on Floating Turbines],
 *         authors=[A. Smith, B. Jones], venue=Optional[Journal of Marine Science and Engineering],
 *         year=Optional[2024], abstractText=Optional[We study wave loads ...],
 *         landingUrl=Optional[https://www.mdpi.com/2077-1312/12/3/400],
 *         pdfUrls=[https://www.mdpi.com/2077-1312/12/3/400/pdf]]
 * }</pre>
 *
 * @param doi          the paper's DOI, bare (no {@code https://doi.org/} prefix)
 * @param title        the paper's title as plain text
 * @param authors      author names in byline order; empty if unknown
 * @param venue        the journal or conference name
 * @param year         the publication year
 * @param abstractText the paper's abstract as plain text, when the source holds one
 * @param landingUrl   the paper's page on the publisher's site
 * @param pdfUrls      candidate locations of the PDF, best first; may be paywalled, so the downloader
 *                     checks each one really returns a PDF
 */
public record PaperMetadata(
        Optional<String> doi,
        Optional<String> title,
        List<String> authors,
        Optional<String> venue,
        Optional<Integer> year,
        Optional<String> abstractText,
        Optional<String> landingUrl,
        List<String> pdfUrls) {

    /**
     * Metadata that knows nothing: every field empty. Used where a source gave no answer, so it can still be merged.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  page.orElse(PaperMetadata.EMPTY).mergedWith(apiResult)   // page was a 403
     * Output: apiResult's fields, unchanged
     * }</pre>
     */
    public static final PaperMetadata EMPTY = new PaperMetadata(Optional.empty(), Optional.empty(), List.of(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());

    /**
     * Makes immutable copies of the two lists, so a caller changing its own list afterwards can't change this record.
     * A null list or a null element in either list throws {@link NullPointerException}.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  List<String> names = new ArrayList<>(List.of("A. Smith"));
     *         PaperMetadata m = new PaperMetadata(..., names, ...);
     *         names.add("B. Jones");
     * Output: m.authors() is still [A. Smith], and m.authors().add(...) throws UnsupportedOperationException
     * }</pre>
     */
    public PaperMetadata {
        authors = List.copyOf(authors);
        pdfUrls = List.copyOf(pdfUrls);
    }

    /**
     * This metadata wins wherever it has a value; {@code other} only fills gaps and adds extra PDF locations.
     * Authors are taken as a whole list, never mixed: {@code other}'s list is used only if this one is empty.
     * PDF URLs are this record's first, then {@code other}'s, with duplicates removed.
     *
     * <p>Example:
     *
     * <pre>{@code
     * this  (publisher page): doi=Optional[10.3390/jmse12030400], title=Optional.empty, authors=[Smith, A.],
     *                         abstractText=Optional.empty, pdfUrls=[https://www.mdpi.com/.../400/pdf]
     * other (Crossref):       doi=Optional[10.3390/JMSE12030400], title=Optional[Wave Loads on Floating Turbines],
     *                         authors=[A. Smith, B. Jones], abstractText=Optional[We study wave loads ...],
     *                         pdfUrls=[https://www.mdpi.com/.../400/pdf, https://europepmc.org/.../pdf]
     * Output: doi=Optional[10.3390/jmse12030400], title=Optional[Wave Loads on Floating Turbines],
     *         authors=[Smith, A.], abstractText=Optional[We study wave loads ...],
     *         pdfUrls=[https://www.mdpi.com/.../400/pdf, https://europepmc.org/.../pdf]
     * }</pre>
     *
     * @param other the less trusted source's metadata
     * @return a new record combining both; neither input is changed
     */
    public PaperMetadata mergedWith(PaperMetadata other) {
        Set<String> pdfs = new LinkedHashSet<>(pdfUrls);
        pdfs.addAll(other.pdfUrls);
        return new PaperMetadata(
                doi.or(() -> other.doi),
                title.or(() -> other.title),
                authors.isEmpty() ? other.authors : authors,
                venue.or(() -> other.venue),
                year.or(() -> other.year),
                abstractText.or(() -> other.abstractText),
                landingUrl.or(() -> other.landingUrl),
                new ArrayList<>(pdfs));
    }
}
