package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Whole M2 (parse + enrich + save PDF) on the client's real sample mails, with the network faked.
 * Which link style each sample uses (see the fixtures) decides its path:
 * SpringerOpen "relevant" = readable DOI link; SpringerOpen "irrelevant" = opaque tokens;
 * ScienceDirect = three decodable wrapper links and two opaque ones.
 */
public class PaperExtractorPipelineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final FakeHttp http = new FakeHttp();

    private PaperExtractor extractor() {
        return new PaperExtractor(new OnlinePaperEnricher(
                new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(
                        new CrossrefSource(http, "https://cr.test"), new OpenAlexSource(http, "https://oa.test"))),
                new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs"))));
    }

    @Test
    public void opaqueSpringerOpenMailGetsItsDoiAndPdfFromTheApisAlone() throws IOException {
        String title = "Hygrothermal analysis of ceramic coating durability under thermal fluctuations on building facades in Brasília";
        http.on("cr.test/works?query.bibliographic=", FakeHttp.json("""
                {"message":{"items":[{"DOI":"10.1186/s43065-024-00115-x","title":["%s"],"author":[],
                 "container-title":["Journal of Infrastructure Preservation and Resilience"],"issued":{"date-parts":[[2025]]}}]}}
                """.formatted(title)));
        http.on("oa.test/works/https://doi.org/10.1186/s43065-024-00115-x", FakeHttp.json("""
                {"doi":"https://doi.org/10.1186/s43065-024-00115-x","display_name":"%s",
                 "best_oa_location":{"pdf_url":"https://oa.test/hygro.pdf"}}
                """.formatted(title)));
        http.on("oa.test/hygro.pdf", FakeHttp.pdf());

        List<PaperCandidate> papers = extractor().extract(sample("springeropen_irrelevant.html"));

        assertEquals(1, papers.size());
        assertEquals(Optional.of("10.1186/s43065-024-00115-x"), papers.get(0).doi());
        Path pdf = papers.get(0).pdfPath().orElseThrow();
        assertTrue(pdf.startsWith(tmp.getRoot().toPath()));
        assertTrue(new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
        assertFalse("the email's tracking links are never followed", http.requested("email.springeropen.com"));
    }

    @Test
    public void readableSpringerOpenMailReadsThePublisherPageAndSavesItsPdf() {
        http.on("jipr.springeropen.com/articles/10.1186/s43065-025-00118-2", FakeHttp.html(
                "<html><head><meta name=\"citation_pdf_url\" content=\"https://pdf.test/ssi.pdf\"></head></html>"));
        http.on("pdf.test/ssi.pdf", FakeHttp.pdf());

        List<PaperCandidate> papers = extractor().extract(sample("springeropen_relevant.html"));

        assertEquals(1, papers.size());
        assertEquals("DOI came straight from the readable link", Optional.of("10.1186/s43065-025-00118-2"), papers.get(0).doi());
        assertEquals("10.1186_s43065-025-00118-2.pdf", papers.get(0).pdfPath().orElseThrow().getFileName().toString());
    }

    @Test
    public void scienceDirectMailReadsThreeSitesResolvesFiveTitlesAndNeverFollowsATrackingLink() {
        http.on("www.sciencedirect.com/science/article/pii/", FakeHttp.status(403));

        List<PaperCandidate> papers = extractor().extract(sample("sciencedirect_recommendations.html"));

        assertEquals(5, papers.size());
        assertEquals("only the three decodable links point at a page worth reading", 3,
                http.count("www.sciencedirect.com/science/article/pii/"));
        assertFalse("opaque tracking links are never contacted", http.requested("click.notification.elsevier.com"));
        assertEquals("every paper is looked up by both APIs", 5, http.count("cr.test/works?query.bibliographic="));
        assertEquals(5, http.count("oa.test/works?search="));
    }

    @Test
    public void everythingOfflineStillYieldsEveryPaperAsExtracted() {
        http.on("cr.test", FakeHttp.offline());
        http.on("oa.test", FakeHttp.offline());
        http.on("pub", FakeHttp.offline());

        List<PaperCandidate> online = extractor().extract(sample("asce_natural_hazards_review.html"));
        List<PaperCandidate> offline = PaperExtractor.offline().extract(sample("asce_natural_hazards_review.html"));

        assertEquals(22, online.size());
        assertEquals("with no network the result equals the parse-only result", offline, online);
    }

    private static RawEmail sample(String file) {
        try (InputStream in = PaperExtractorPipelineTest.class.getResourceAsStream("/samples/" + file)) {
            return new RawEmail("<sample-" + file + "@mail.gmail.com>", "Fwd: alert", "aprakashn@gmail.com",
                    Instant.parse("2026-09-04T07:28:00Z"),
                    Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8)), Optional.empty());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
