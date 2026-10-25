package com.litroute.ingestion.enrich;

import com.litroute.util.Dois;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Reads a paper's own page on the publisher's site (the readable link from the email). Scholarly
 * publishers embed machine-readable "citation_*" meta tags (the Highwire/Google Scholar convention),
 * which give the DOI, authors, journal, year and the PDF's URL. If the link points straight at a PDF,
 * that is reported as the PDF location instead.
 *
 * This is a plain public GET, like a browser without a login: a paywalled or bot-blocked page
 * (403, login redirect) simply yields nothing, and nothing here tries to get around that.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Input:  read("https://www.mdpi.com/2077-1312/12/3/400")
 *         -> HTTP 200 HTML with
 *            <meta name="citation_doi" content="10.3390/jmse12030400">
 *            <meta name="citation_title" content="Wave Loads on Floating Turbines">
 *            <meta name="citation_author" content="Smith, A.">
 *            <meta name="citation_journal_title" content="Journal of Marine Science and Engineering">
 *            <meta name="citation_publication_date" content="2024/03/15">
 *            <meta name="citation_pdf_url" content="https://www.mdpi.com/2077-1312/12/3/400/pdf">
 * Output: Optional[PaperMetadata[doi=Optional[10.3390/jmse12030400], title=Optional[Wave Loads on Floating Turbines],
 *         authors=[Smith, A.], venue=Optional[Journal of Marine Science and Engineering], year=Optional[2024],
 *         abstractText=Optional.empty, landingUrl=Optional[https://www.mdpi.com/2077-1312/12/3/400],
 *         pdfUrls=[https://www.mdpi.com/2077-1312/12/3/400/pdf]]]
 *
 * Input:  read("https://www.sciencedirect.com/science/article/pii/S0029801824001234") -> HTTP 403
 * Output: Optional.empty
 * }</pre>
 */
public final class ArticlePageReader {

    private static final Logger LOG = Logger.getLogger(ArticlePageReader.class.getName());
    private static final long MAX_PAGE_BYTES = 3L * 1024 * 1024;
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)\\d{2}\\b");

    private final HttpFetcher fetcher;

    /**
     * Creates a reader that fetches pages through the given client.
     * Input: fetcher - the HTTP client used for every page fetch
     * Output: a reader fetching pages through that client.
     * Used by: OnlinePaperEnricher.create (M3).
     * Example:
     * <pre>{@code
     * Input:  new ArticlePageReader(new JdkHttpFetcher("LitRoute/1.0 (personal research-paper alert reader)"))
     * Output: a reader that does real network GETs (tests pass a RecordedHttp fetcher instead)
     * }</pre>
     */
    public ArticlePageReader(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Fetches a paper's page (body capped at 3 MB) and reads what it says about the paper:
     *
     * <ul>
     *   <li>an HTML page -> DOI, title, authors, venue and year from its {@code citation_*} / Dublin Core / PRISM
     *       meta tags, the page's final URL (after redirects) as the landing URL, and PDF links from
     *       {@code citation_pdf_url} and {@code <link rel="alternate" type="application/pdf">};
     *   <li>a PDF (by its {@code %PDF-} header or content type) -> only {@code pdfUrls}, holding the final URL;
     *   <li>a non-2xx reply, network failure or malformed URL -> empty.
     * </ul>
     *
     * A page that answers 200 but carries no meta tags (e.g. a challenge page) gives metadata with only the
     * landing URL set. Fields the page doesn't state are left empty; the abstract is never read from the page.
     * Input: url - the paper's readable link from the email
     * Output: what the page says about the paper, or empty if it couldn't be read
     * Used by: OnlinePaperEnricher.enrich (M3), for READABLE links only.
     * Example:
     * <pre>{@code
     * Input:  "https://www.mdpi.com/2077-1312/12/3/400"   -> HTTP 200 HTML with citation_* tags
     * Output: Optional[PaperMetadata[doi=Optional[10.3390/jmse12030400], title=Optional[Wave Loads on ...], ...,
     *         landingUrl=Optional[https://www.mdpi.com/2077-1312/12/3/400], pdfUrls=[https://www.mdpi.com/.../400/pdf]]]
     *
     * Input:  "https://arxiv.org/pdf/2403.01234"           -> HTTP 200, body starts "%PDF-1.5"
     * Output: Optional[PaperMetadata[all fields empty except pdfUrls=[https://arxiv.org/pdf/2403.01234]]]
     *
     * Input:  "https://ascelibrary.org/doi/10.1061/JWPED5.WWENG-2001"   -> HTTP 403
     * Output: Optional.empty
     * }</pre>
     */
    public Optional<PaperMetadata> read(String url) {
        HttpReply reply;
        try {
            reply = fetcher.get(URI.create(url), MAX_PAGE_BYTES);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read article page " + url + ": " + e);
            return Optional.empty();
        }
        if (!reply.ok()) {
            return Optional.empty();
        }
        if (PdfDownloader.isPdf(reply.body()) || reply.contentType().toLowerCase().contains("application/pdf")) {
            return Optional.of(new PaperMetadata(Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(reply.finalUri().toString())));
        }
        Document page = Jsoup.parse(new String(reply.body(), StandardCharsets.UTF_8), reply.finalUri().toString());

        Optional<String> doi = firstMeta(page, "citation_doi", "dc.identifier", "prism.doi", "bepress_citation_doi")
                .flatMap(Dois::find);
        List<String> authors = new ArrayList<>();
        page.select("meta[name=citation_author]").forEach(meta -> authors.add(meta.attr("content").trim()));
        Optional<Integer> year = firstMeta(page, "citation_publication_date", "citation_date", "citation_online_date",
                "dc.date", "prism.publicationDate").flatMap(ArticlePageReader::yearOf);
        List<String> pdfUrls = new ArrayList<>();
        firstMeta(page, "citation_pdf_url").ifPresent(pdf -> pdfUrls.add(absolute(page, pdf)));
        Element alternate = page.selectFirst("link[rel=alternate][type=application/pdf][href]");
        if (alternate != null && !pdfUrls.contains(alternate.absUrl("href"))) {
            pdfUrls.add(alternate.absUrl("href"));
        }
        return Optional.of(new PaperMetadata(
                doi,
                firstMeta(page, "citation_title", "dc.title"),
                authors.stream().filter(name -> !name.isEmpty()).toList(),
                firstMeta(page, "citation_journal_title", "citation_conference_title", "prism.publicationName"),
                year,
                Optional.empty(),
                Optional.of(reply.finalUri().toString()),
                pdfUrls));
    }

    /**
     * Content of the first meta tag matching any of the names, tried in the order given (names are case-insensitive).
     * Tags with blank content are skipped, so a later name can still supply the value.
     * Input: page - the parsed HTML page; names - meta tag names, most preferred first
     * Output: the trimmed content of the first non-blank match, or empty if none
     * Used by: {@link #read}, for each citation_* / DC / PRISM field.
     * Example:
     * <pre>{@code
     * Page:   <meta name="DC.Date" content="2023-11-02">
     *         <meta name="citation_date" content="">
     *         <meta name="citation_publication_date" content="2024/03/15">
     * Input:  firstMeta(page, "citation_publication_date", "citation_date", "dc.date")
     * Output: Optional[2024/03/15]
     *
     * Input:  firstMeta(page, "citation_date", "dc.date")
     * Output: Optional[2023-11-02]   (citation_date is blank, DC.Date matches case-insensitively)
     *
     * Input:  firstMeta(page, "citation_doi")
     * Output: Optional.empty
     * }</pre>
     */
    private static Optional<String> firstMeta(Document page, String... names) {
        for (String name : names) {
            for (Element meta : page.select("meta[name][content]")) {
                if (meta.attr("name").equalsIgnoreCase(name) && !meta.attr("content").isBlank()) {
                    return Optional.of(meta.attr("content").trim());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a possibly relative URL against the page's own location (its final URL after redirects).
     * Input: page - the parsed page, whose location is the base URL; maybeRelative - a URL from the page, absolute or relative
     * Output: the absolute URL
     * Used by: {@link #read}, for PDF links on the page.
     * Example:
     * <pre>{@code
     * Page location: "https://www.mdpi.com/2077-1312/12/3/400"
     * Input:  "/2077-1312/12/3/400/pdf"                Output: "https://www.mdpi.com/2077-1312/12/3/400/pdf"
     * Input:  " pdf "                                  Output: "https://www.mdpi.com/2077-1312/12/3/pdf"
     * Input:  "https://cdn.example.org/paper.pdf"      Output: "https://cdn.example.org/paper.pdf"
     * }</pre>
     */
    private static String absolute(Document page, String maybeRelative) {
        return URI.create(page.location()).resolve(maybeRelative.trim()).toString();
    }

    /**
     * Pulls the first four-digit year from 1900 to 2099 out of a date in any format.
     * Input: date - a date string from a meta tag
     * Output: the year, or empty if none is found
     * Used by: {@link #read}.
     * Example:
     * <pre>{@code
     * Input:  "2024/03/15"       Output: Optional[2024]
     * Input:  "15 March 2023"    Output: Optional[2023]
     * Input:  "n.d."             Output: Optional.empty
     * }</pre>
     */
    private static Optional<Integer> yearOf(String date) {
        Matcher matcher = YEAR.matcher(date);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group())) : Optional.empty();
    }
}
