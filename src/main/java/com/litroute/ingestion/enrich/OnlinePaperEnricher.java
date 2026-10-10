package com.litroute.ingestion.enrich;

import com.litroute.ingestion.extract.LinkKind;
import com.litroute.ingestion.extract.PaperEnricher;
import com.litroute.model.PaperCandidate;
import java.nio.file.Path;
import java.util.List;
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
 * candidate. Data from the email is never overwritten - lookups only fill fields it left empty - and
 * if everything is offline the candidate comes back exactly as it went in.
 */
public final class OnlinePaperEnricher implements PaperEnricher {

    private static final String USER_AGENT = "LitRoute/1.0 (personal research-paper alert reader)";

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
        HttpFetcher fetcher = new JdkHttpFetcher(USER_AGENT);
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
        Optional<PaperMetadata> apis = lookup.find(new PaperQuery(paper.title(), paper.authors(), knownDoi));

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
                paper.abstractSnippet().or(() -> found.abstractText()),
                pdf);
    }
}
