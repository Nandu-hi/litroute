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
 */
public final class ArticlePageReader {

    private static final Logger LOG = Logger.getLogger(ArticlePageReader.class.getName());
    private static final long MAX_PAGE_BYTES = 3L * 1024 * 1024;
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)\\d{2}\\b");

    private final HttpFetcher fetcher;

    public ArticlePageReader(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** @return what the page says about the paper, or empty if it couldn't be read */
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
                    Optional.empty(), Optional.empty(), List.of(reply.finalUri().toString())));
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
                Optional.of(reply.finalUri().toString()),
                pdfUrls));
    }

    /** Content of the first meta tag matching any of the names, tried in the order given (names are case-insensitive). */
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

    private static String absolute(Document page, String maybeRelative) {
        return URI.create(page.location()).resolve(maybeRelative.trim()).toString();
    }

    private static Optional<Integer> yearOf(String date) {
        Matcher matcher = YEAR.matcher(date);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group())) : Optional.empty();
    }
}
