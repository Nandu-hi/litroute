package com.litroute.ingestion.enrich;

import com.litroute.ingestion.extract.LinkKind;
import com.litroute.ingestion.extract.PaperEnricher;
import com.litroute.model.PaperCandidate;
import com.litroute.util.Titles;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fills in a parsed paper's DOI, missing metadata and local PDF.
 *
 * <ul>
 *   <li>Opaque (encrypted) link: the link is useless, so the paper is identified by title through
 *       Crossref and OpenAlex only. The publisher's site is never contacted.
 *   <li>Readable link (direct or decoded): the publisher's own page is read too, and the APIs are
 *       asked by DOI (exact) when the link or page revealed one, else by title.
 * </ul>
 *
 * Then the PDF is downloaded from the first location that really serves one (publisher page's own
 * PDF link first, then Crossref, then OpenAlex's open-access copy) and its path stored on the
 * candidate. Data from the email is never overwritten - lookups only fill fields it left empty, or
 * complete an abstract the alert cut short ({@link #completed}) - and
 * if everything is offline the candidate comes back exactly as it went in.
 */
public final class OnlinePaperEnricher implements PaperEnricher {

    private static final String USER_AGENT = "LitRoute/1.0 (personal research-paper alert reader)";
    /**
     * Least time between two requests to each API: Crossref's public pool allows 5 a second, OpenAlex 10;
     * staying a little under keeps a burst of fast replies from earning a 429.
     */
    private static final Map<String, Duration> API_PACE = Map.of(
            "api.crossref.org", Duration.ofMillis(250),
            "api.openalex.org", Duration.ofMillis(125));

    private final ArticlePageReader pages;
    private final CompositeMetadataLookup lookup;
    private final PdfDownloader pdfs;

    public OnlinePaperEnricher(ArticlePageReader pages, CompositeMetadataLookup lookup, PdfDownloader pdfs) {
        this.pages = pages;
        this.lookup = lookup;
        this.pdfs = pdfs;
    }

    /** Real network: Crossref + OpenAlex + the publisher site, PDFs saved under {@code pdfDirectory}. */
    public static OnlinePaperEnricher create(Path pdfDirectory) {
        HttpFetcher fetcher = new PacedFetcher(new JdkHttpFetcher(USER_AGENT), API_PACE);
        return new OnlinePaperEnricher(
                new ArticlePageReader(fetcher),
                new CompositeMetadataLookup(List.of(new CrossrefSource(fetcher), new OpenAlexSource(fetcher))),
                new PdfDownloader(fetcher, pdfDirectory));
    }

    @Override
    public PaperCandidate enrich(PaperCandidate paper, LinkKind linkKind) {
        Optional<PaperMetadata> page = linkKind == LinkKind.READABLE && paper.url().isPresent()
                ? pages.read(paper.url().get())
                : Optional.empty();

        Optional<String> knownDoi = paper.doi().or(() -> page.flatMap(PaperMetadata::doi));
        Optional<PaperMetadata> apis = lookup.find(new PaperQuery(paper.title(), paper.authors(), knownDoi, paper.venue()));

        PaperMetadata found = page.orElse(PaperMetadata.EMPTY).mergedWith(apis.orElse(PaperMetadata.EMPTY));

        Optional<String> doi = paper.doi().or(() -> found.doi());
        Optional<Path> pdf = paper.pdfPath();
        if (pdf.isEmpty() && !found.pdfUrls().isEmpty()) {
            pdf = pdfs.download(found.pdfUrls(), PdfDownloader.fileStem(doi, paper.title()));
        }
        return new PaperCandidate(
                paper.sourceMessageId(),
                paper.title(),
                paper.authors().isEmpty() ? found.authors() : paper.authors(),
                paper.venue().or(() -> found.venue()),
                paper.year().or(() -> found.year()),
                paper.articleType(),
                doi,
                paper.url().or(() -> found.landingUrl()).or(() -> doi.map("https://doi.org/"::concat)),
                completed(paper.abstractSnippet(), found.abstractText()),
                pdf);
    }

    /**
     * The alert's abstract, unless a looked-up one begins with the same text and goes on: then the alert
     * had cut it short (the Wiley "Recommended articles" alert stops after a dozen words), and the
     * looked-up one is the same abstract, whole.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "In current dynamic time history assessment protocols, ground motion recordings are",
     *         "In current dynamic time history assessment protocols, ground motion recordings are mostly oriented ..."
     * Output: "In current dynamic time history assessment protocols, ground motion recordings are mostly oriented ..."
     * }</pre>
     */
    static Optional<String> completed(Optional<String> fromAlert, Optional<String> lookedUp) {
        if (fromAlert.isEmpty()) {
            return lookedUp;
        }
        String alert = Titles.compact(fromAlert.get());
        boolean continues = lookedUp.map(Titles::compact)
                .filter(full -> !alert.isEmpty() && full.length() > alert.length() && full.startsWith(alert)).isPresent();
        return continues ? lookedUp : fromAlert;
    }
}
