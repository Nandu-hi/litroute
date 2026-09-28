package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class ArticlePageReaderTest {

    private static final String ARTICLE_PAGE = """
            <html><head>
            <meta name="citation_title" content="Advancing soil-structure interaction (SSI)">
            <meta name="citation_author" content="Imtiyaz Akbar Najar">
            <meta name="citation_author" content="Shanshan Tang">
            <meta name="citation_journal_title" content="Journal of Infrastructure Preservation and Resilience">
            <meta name="citation_publication_date" content="2025/01/26">
            <meta name="citation_doi" content="10.1186/s43065-025-00118-2">
            <meta name="citation_pdf_url" content="/counter/pdf/10.1186/s43065-025-00118-2.pdf">
            </head><body>paywalled or not, the meta tags are public</body></html>
            """;

    private final FakeHttp http = new FakeHttp();
    private final ArticlePageReader reader = new ArticlePageReader(http);

    @Test
    public void readsTheCitationMetaTagsAndResolvesARelativePdfLink() {
        http.on("pub.test/articles/", FakeHttp.html(ARTICLE_PAGE));

        PaperMetadata page = reader.read("https://pub.test/articles/10.1186/s43065-025-00118-2").orElseThrow();

        assertEquals(Optional.of("10.1186/s43065-025-00118-2"), page.doi());
        assertEquals(Optional.of("Advancing soil-structure interaction (SSI)"), page.title());
        assertEquals(List.of("Imtiyaz Akbar Najar", "Shanshan Tang"), page.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), page.venue());
        assertEquals(Optional.of(2025), page.year());
        assertEquals(List.of("https://pub.test/counter/pdf/10.1186/s43065-025-00118-2.pdf"), page.pdfUrls());
        assertEquals(Optional.of("https://pub.test/articles/10.1186/s43065-025-00118-2"), page.landingUrl());
    }

    @Test
    public void aLinkThatIsItselfAPdfIsReportedAsThePdfLocation() {
        http.on("pub.test/doi/pdf/", FakeHttp.pdf());

        PaperMetadata page = reader.read("https://pub.test/doi/pdf/10.9999/eqe.test").orElseThrow();

        assertEquals(List.of("https://pub.test/doi/pdf/10.9999/eqe.test"), page.pdfUrls());
        assertEquals(Optional.empty(), page.doi());
    }

    @Test
    public void aBlockedOrMissingPageGivesNothingAndNeverThrows() {
        http.on("pub.test/blocked", FakeHttp.status(403));
        http.on("pub.test/down", FakeHttp.offline());

        assertEquals(Optional.empty(), reader.read("https://pub.test/blocked"));
        assertEquals(Optional.empty(), reader.read("https://pub.test/down"));
        assertEquals(Optional.empty(), reader.read("https://pub.test/never-routed-so-404"));
    }

    @Test
    public void aPageWithoutCitationMetaTagsStillYieldsItsAddressButNothingElse() {
        http.on("pub.test/plain", FakeHttp.html("<html><body><h1>Some article</h1></body></html>"));

        PaperMetadata page = reader.read("https://pub.test/plain").orElseThrow();

        assertEquals(Optional.empty(), page.doi());
        assertEquals(List.of(), page.pdfUrls());
        assertEquals(List.of(), page.authors());
    }
}
