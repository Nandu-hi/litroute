package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.ingestion.extract.LinkKind;
import com.litroute.model.PaperCandidate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The two enrichment paths the client asked for, with Crossref/OpenAlex/publisher/PDF servers all faked:
 * an opaque ("encrypted") link is resolved by title through both APIs only; a readable link is checked
 * against the APIs and the publisher's own page; either API being offline is survivable.
 */
public class OnlinePaperEnricherTest {

    private static final String TITLE =
            "Hygrothermal analysis of ceramic coating durability under thermal fluctuations on building facades in Brasília";
    private static final String DOI = "10.1186/s43065-024-00115-x";

    private static final String CROSSREF_WORK = """
            {"DOI":"%s","title":["%s"],"author":[{"given":"Gilberto","family":"Gomes"}],
             "container-title":["Journal of Infrastructure Preservation and Resilience"],
             "issued":{"date-parts":[[2025]]},"URL":"https://doi.org/%s","link":[]}
            """.formatted(DOI, TITLE, DOI);

    private static final String OPENALEX_WORK = """
            {"doi":"https://doi.org/%s","display_name":"%s","publication_year":2025,
             "authorships":[{"author":{"display_name":"Gilberto Gomes"}}],
             "primary_location":{"landing_page_url":"https://oa.test/landing","source":{"display_name":"OA Journal Name"}},
             "best_oa_location":{"pdf_url":"https://oa.test/hygro.pdf"},"locations":[]}
            """.formatted(DOI, TITLE);

    private static final String PUBLISHER_PAGE = """
            <html><head><meta name="citation_doi" content="%s">
            <meta name="citation_pdf_url" content="https://pub.test/pdf/hygro.pdf"></head></html>
            """.formatted(DOI);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final FakeHttp http = new FakeHttp();

    private OnlinePaperEnricher enricher() {
        return new OnlinePaperEnricher(
                new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(
                        new CrossrefSource(http, "https://cr.test"), new OpenAlexSource(http, "https://oa.test"))),
                new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs")));
    }

    private static PaperCandidate parsed(Optional<String> doi, Optional<String> url) {
        return new PaperCandidate("<m1>", TITLE, List.of(), Optional.empty(), Optional.empty(), Optional.of("RESEARCH"),
                doi, url, Optional.empty(), Optional.empty());
    }

    // ---- encrypted (opaque) link: APIs only

    @Test
    public void opaqueLinkIsResolvedByTitleThroughBothApisAndThePublisherSiteIsNeverContacted() throws IOException {
        http.on("cr.test/works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + CROSSREF_WORK + "]}}"));
        http.on("oa.test/works/https://doi.org/" + DOI, FakeHttp.json(OPENALEX_WORK));
        http.on("oa.test/hygro.pdf", FakeHttp.pdf());
        // Even if a URL were somehow attached, an opaque paper's site must not be fetched.
        PaperCandidate opaque = parsed(Optional.empty(), Optional.of("https://pub.test/should-not-be-read"));

        PaperCandidate enriched = enricher().enrich(opaque, LinkKind.OPAQUE);

        assertEquals(Optional.of(DOI), enriched.doi());
        assertEquals("author, venue, year were empty in the email and come from the APIs",
                List.of("Gilberto Gomes"), enriched.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), enriched.venue());
        assertEquals(Optional.of(2025), enriched.year());
        Path pdf = enriched.pdfPath().orElseThrow();
        assertEquals("saved under the DOI's name", "10.1186_s43065-024-00115-x.pdf", pdf.getFileName().toString());
        assertArrayEquals(FakeHttp.PDF_BYTES, Files.readAllBytes(pdf));
        assertTrue("both APIs were used", http.requested("cr.test/") && http.requested("oa.test/works"));
        assertFalse("publisher site untouched", http.requested("pub.test"));
    }

    @Test
    public void opaqueLinkWithOneApiOfflineIsStillResolvedByTheOther() throws IOException {
        http.on("cr.test", FakeHttp.offline());
        http.on("oa.test/works?search=", FakeHttp.json("{\"results\":[" + OPENALEX_WORK + "]}"));
        http.on("oa.test/hygro.pdf", FakeHttp.pdf());

        PaperCandidate enriched = enricher().enrich(parsed(Optional.empty(), Optional.empty()), LinkKind.OPAQUE);

        assertEquals(Optional.of(DOI), enriched.doi());
        assertTrue(Files.exists(enriched.pdfPath().orElseThrow()));
    }

    @Test
    public void opaqueLinkWithEverythingOfflineComesBackExactlyAsExtracted() {
        http.on("cr.test", FakeHttp.offline());
        http.on("oa.test", FakeHttp.offline());
        PaperCandidate opaque = parsed(Optional.empty(), Optional.empty());

        PaperCandidate enriched = enricher().enrich(opaque, LinkKind.OPAQUE);

        assertEquals(opaque, enriched);
    }

    @Test
    public void aTitleTheApisDoNotKnowLeavesThePaperUntouchedRatherThanGuessing() {
        http.on("cr.test/works?query.bibliographic=", FakeHttp.json(
                "{\"message\":{\"items\":[{\"DOI\":\"10.9999/other\",\"title\":[\"Completely different topic\"]}]}}"));
        http.on("oa.test/works?search=", FakeHttp.json("{\"results\":[]}"));
        PaperCandidate opaque = parsed(Optional.empty(), Optional.empty());

        assertEquals(opaque, enricher().enrich(opaque, LinkKind.OPAQUE));
    }

    // ---- readable link: APIs + publisher site

    @Test
    public void readableLinkUsesThePublisherPageAndBothApisAndPrefersThePublishersOwnPdf() throws IOException {
        http.on("pub.test/articles/", FakeHttp.html(PUBLISHER_PAGE));
        http.on("cr.test/works/" + DOI, FakeHttp.json("{\"message\":" + CROSSREF_WORK + "}"));
        http.on("oa.test/works/https://doi.org/" + DOI, FakeHttp.json(OPENALEX_WORK));
        http.on("pub.test/pdf/hygro.pdf", FakeHttp.pdf());
        http.on("oa.test/hygro.pdf", FakeHttp.pdf());
        PaperCandidate readable = parsed(Optional.of(DOI), Optional.of("https://pub.test/articles/" + DOI));

        PaperCandidate enriched = enricher().enrich(readable, LinkKind.READABLE);

        assertTrue(Files.exists(enriched.pdfPath().orElseThrow()));
        assertTrue("publisher page read", http.requested("pub.test/articles/"));
        assertTrue("Crossref asked by DOI", http.requested("cr.test/works/" + DOI));
        assertTrue("OpenAlex asked by DOI", http.requested("oa.test/works/https://doi.org/" + DOI));
        assertTrue("publisher's own PDF link used", http.requested("pub.test/pdf/hygro.pdf"));
        assertFalse("no need for the open-access copy once the first PDF downloaded", http.requested("oa.test/hygro.pdf"));
        assertEquals("the email's own link is kept", Optional.of("https://pub.test/articles/" + DOI), enriched.url());
    }

    @Test
    public void readableLinkWithBothApisOfflineStillWorksFromThePublisherSite() throws IOException {
        http.on("cr.test", FakeHttp.offline());
        http.on("oa.test", FakeHttp.offline());
        http.on("pub.test/articles/", FakeHttp.html(PUBLISHER_PAGE));
        http.on("pub.test/pdf/hygro.pdf", FakeHttp.pdf());
        PaperCandidate readable = parsed(Optional.empty(), Optional.of("https://pub.test/articles/no-doi-in-this-url"));

        PaperCandidate enriched = enricher().enrich(readable, LinkKind.READABLE);

        assertEquals("DOI learned from the page's citation_doi tag", Optional.of(DOI), enriched.doi());
        assertTrue(Files.exists(enriched.pdfPath().orElseThrow()));
    }

    // ---- shared behaviour

    @Test
    public void aPaywalledPdfIsNotSavedButTheDoiFoundIsKept() {
        http.on("cr.test/works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + CROSSREF_WORK + "]}}"));
        http.on("oa.test/works/https://doi.org/" + DOI, FakeHttp.json(OPENALEX_WORK));
        http.on("oa.test/hygro.pdf", FakeHttp.html("<html>Sign in to download</html>"));

        PaperCandidate enriched = enricher().enrich(parsed(Optional.empty(), Optional.empty()), LinkKind.OPAQUE);

        assertEquals(Optional.of(DOI), enriched.doi());
        assertEquals(Optional.empty(), enriched.pdfPath());
    }

    @Test
    public void whatTheEmailAlreadyStatesIsNeverOverwrittenByLookedUpData() {
        http.on("cr.test/works/" + DOI, FakeHttp.json("{\"message\":" + CROSSREF_WORK + "}"));
        http.on("oa.test/works/https://doi.org/" + DOI, FakeHttp.json(OPENALEX_WORK));
        PaperCandidate fromEmail = new PaperCandidate("<m1>", TITLE, List.of("G Gomes"), Optional.of("Email's Venue"),
                Optional.of(2024), Optional.of("RESEARCH"), Optional.of(DOI), Optional.empty(), Optional.empty(), Optional.empty());

        PaperCandidate enriched = enricher().enrich(fromEmail, LinkKind.NONE);

        assertEquals(TITLE, enriched.title());
        assertEquals(List.of("G Gomes"), enriched.authors());
        assertEquals(Optional.of("Email's Venue"), enriched.venue());
        assertEquals(Optional.of(2024), enriched.year());
        assertEquals(Optional.of("RESEARCH"), enriched.articleType());
    }

    @Test
    public void aPaperWhosePdfIsAlreadyOnDiskIsNotDownloadedAgain() {
        http.on("cr.test/works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + CROSSREF_WORK + "]}}"));
        http.on("oa.test/works/https://doi.org/" + DOI, FakeHttp.json(OPENALEX_WORK));
        http.on("oa.test/hygro.pdf", FakeHttp.pdf());
        OnlinePaperEnricher enricher = enricher();
        PaperCandidate first = enricher.enrich(parsed(Optional.empty(), Optional.empty()), LinkKind.OPAQUE);

        PaperCandidate second = enricher.enrich(parsed(Optional.empty(), Optional.empty()), LinkKind.OPAQUE);

        assertEquals(first.pdfPath(), second.pdfPath());
        assertEquals(1, http.count("oa.test/hygro.pdf"));
    }
}
