package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.PaperCandidate;
import com.litroute.testing.SampleMails;
import com.litroute.testing.SampleMails.Sample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The whole of M2 and M3 - parse, enrich, save the PDF - run over the six real alert mails against
 * the replies Crossref, OpenAlex and the publishers really gave for the 31 papers in them.
 *
 * Nothing here is constructed: the emails are the ones Prof. Badal forwarded, and every DOI, venue
 * and year asserted is what the real pipeline produced from the real responses. That makes this the
 * test that would notice if any stage quietly changed what it produces.
 */
public class PaperExtractorPipelineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final RecordedHttp http = new RecordedHttp();

    private PaperExtractor extractor() {
        return new PaperExtractor(new OnlinePaperEnricher(
                new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(new CrossrefSource(http), new OpenAlexSource(http))),
                new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs"))));
    }

    private List<PaperCandidate> run(Sample sample) {
        return extractor().extract(sample.email());
    }

    /** Each mail yields exactly the papers it lists - no paper dropped, none invented from a stray link. */
    @Test
    public void eachMailYieldsExactlyThePapersItLists() {
        for (Sample sample : SampleMails.ALL) {
            assertEquals(sample.file(), sample.papers(), run(sample).size());
        }
    }

    /**
     * Every one of the 31 papers comes out identified by a DOI. That is the pipeline's whole job at
     * this stage, and it holds across all five publishers and all three link styles.
     */
    @Test
    public void everyPaperInEveryMailEndsUpWithItsDoi() {
        for (Sample sample : SampleMails.ALL) {
            for (PaperCandidate paper : run(sample)) {
                assertTrue(sample.file() + ": no DOI for \"" + paper.title() + "\"", paper.doi().isPresent());
            }
        }
    }

    /** A readable DOI link: the publisher's own page is read, and its open-access PDF saved. */
    @Test
    public void theSpringerOpenAlertWithAReadableLinkIsFullyResolvedAndItsPdfSaved() throws IOException {
        PaperCandidate paper = run(SampleMails.SPRINGER_RELEVANT).get(0);

        assertEquals("Advancing soil-structure interaction (SSI): a comprehensive review of current "
                + "practices, challenges, and future directions", paper.title());
        assertEquals(Optional.of("10.1186/s43065-025-00118-2"), paper.doi());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), paper.venue());
        assertEquals(Optional.of(2025), paper.year());
        assertEquals("the three names the alert shows, with the \"…\" placeholder dropped",
                List.of("Imtiyaz Akbar Najar", "Raudhah Ahmadi", "Shanshan Tang"), paper.authors());

        Path pdf = paper.pdfPath().orElseThrow();
        assertEquals("10.1186_s43065-025-00118-2.pdf", pdf.getFileName().toString());
        assertTrue(pdf.startsWith(tmp.getRoot().toPath()));
        assertTrue(new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
    }

    /**
     * The same journal, but every link in the mail is an opaque tracking token. The paper still has to
     * be identified - by title, through the APIs - and the email's tracking links never followed.
     */
    @Test
    public void theSpringerOpenAlertWithOnlyOpaqueLinksIsStillResolvedByTitle() {
        PaperCandidate paper = run(SampleMails.SPRINGER_IRRELEVANT).get(0);

        assertEquals(Optional.of("10.1186/s43065-024-00115-x"), paper.doi());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), paper.venue());
        assertEquals("having no link of its own, the paper is addressed by its DOI",
                Optional.of("https://doi.org/10.1186/s43065-024-00115-x"), paper.url());
        assertTrue(paper.pdfPath().isPresent());
        assertFalse("the email's own tracking links are never contacted",
                http.requested("email.springeropen.com"));
    }

    /** Google Scholar wraps the real target in a redirect; it must be unwrapped, not followed blindly. */
    @Test
    public void theGoogleScholarAlertsRedirectWrapperIsUnwrappedToTheRealArticle() {
        PaperCandidate paper = run(SampleMails.GOOGLE_SCHOLAR).get(0);

        assertEquals(Optional.of("10.1002/eqe.70286"), paper.doi());
        assertEquals("https://onlinelibrary.wiley.com/doi/pdf/10.1002/eqe.70286", paper.url().orElseThrow());
        assertFalse(paper.url().orElseThrow().contains("scholar.google.com"));
        assertEquals(Optional.of(2026), paper.year());
        assertTrue(paper.abstractSnippet().orElseThrow()
                .startsWith("In current dynamic time history assessment protocols"));
        assertTrue("the alert cuts the abstract off after a dozen words; Crossref's completes it",
                paper.abstractSnippet().orElseThrow().contains("mostly oriented along fixed sensor directions"));
    }

    /** Taylor & Francis states no year in the alert; enrichment supplies it without inventing one. */
    @Test
    public void theTaylorAndFrancisAlertGainsTheYearItNeverStated() {
        PaperCandidate paper = run(SampleMails.TANDFONLINE).get(0);

        assertEquals(Optional.of("10.1080/23789689.2026.2723803"), paper.doi());
        assertEquals(Optional.of("Sustainable and Resilient Infrastructure"), paper.venue());
        assertEquals("the alert gives no year; Crossref does", Optional.of(2026), paper.year());
        assertEquals(List.of("R. Wirahadikusumah", "M. Abduh", "P. Kusumaningrum", "D. Apoji", "M. A. Viqolbi"),
                paper.authors());
    }

    /**
     * ScienceDirect mixes link styles in one mail: three wrappers whose real target can be decoded,
     * and two that are encrypted. Both kinds must end at the right paper, and the tracking host must
     * never be contacted for either.
     */
    @Test
    public void theScienceDirectAlertResolvesBothItsDecodableAndItsEncryptedLinks() {
        List<PaperCandidate> papers = run(SampleMails.SCIENCEDIRECT);

        assertEquals(List.of("10.1016/j.ijdrr.2024.105116", "10.1016/j.oceaneng.2024.118952",
                        "10.1016/j.jclepro.2024.141641", "10.1016/j.ijimpeng.2025.105466",
                        "10.1016/j.soildyn.2025.109696"),
                papers.stream().map(p -> p.doi().orElseThrow()).toList());
        assertEquals("only the three decodable links point at a page worth reading", 3,
                http.count("www.sciencedirect.com/science/article/pii/"));
        assertFalse("an encrypted tracking link is never contacted",
                http.requested("click.notification.elsevier.com"));
    }

    /** A 22-entry table of contents: each of its 17 research papers keeps its own DOI, section and title. */
    @Test
    public void theAsceTableOfContentsResolvesAllSeventeenResearchPapersSeparately() {
        List<PaperCandidate> papers = run(SampleMails.ASCE);

        assertEquals(17, papers.size());
        assertEquals("every paper has a DOI of its own", 17,
                papers.stream().map(p -> p.doi().orElseThrow()).distinct().count());
        assertTrue("all of them are Natural Hazards Review DOIs",
                papers.stream().allMatch(p -> p.doi().orElseThrow().startsWith("10.1061/nhrefo.nheng-")));
        assertEquals(Set.of("Natural Hazards Review"),
                papers.stream().map(p -> p.venue().orElseThrow()).collect(Collectors.toSet()));

        assertEquals("Research Hotspots and Evolutionary Trends ofEarthquake-Induced Landslides: "
                + "A BibliometricAnalysis Based on Web of Science", papers.get(0).title());
        assertEquals(Optional.of("10.1061/nhrefo.nheng-2717"), papers.get(0).doi());
        assertEquals(Optional.of("State-of-the-Art Reviews"), papers.get(0).articleType());
        assertEquals(Optional.of("10.1061/nhrefo.nheng-2711"), papers.get(16).doi());
        assertEquals(Optional.of("Technical Notes"), papers.get(16).articleType());
    }

    /**
     * ASCE and ScienceDirect serve a robot 403, and no open-access PDF is offered for their papers.
     * Only the two SpringerOpen papers end with a file on disk, and that is the honest outcome:
     * a paywalled paper must leave the candidate without a PDF rather than with a fake one.
     */
    @Test
    public void onlyThePapersWithAnOpenAccessCopyEndUpWithAPdf() {
        for (Sample sample : SampleMails.ALL) {
            long saved = run(sample).stream().filter(p -> p.pdfPath().isPresent()).count();
            long expected = sample.file().startsWith("springeropen") ? 1 : 0;
            assertEquals(sample.file(), expected, saved);
        }
    }

    /** With the network gone, enrichment must add nothing rather than half-fill the papers. */
    @Test
    public void withEverythingOfflineTheResultIsExactlyTheParseOnlyResult() {
        RecordedHttp offline = new RecordedHttp().offlineFor("");
        PaperExtractor online = new PaperExtractor(new OnlinePaperEnricher(
                new ArticlePageReader(offline),
                new CompositeMetadataLookup(List.of(new CrossrefSource(offline), new OpenAlexSource(offline))),
                new PdfDownloader(offline, tmp.getRoot().toPath().resolve("pdfs"))));

        for (Sample sample : SampleMails.ALL) {
            assertEquals(sample.file(),
                    PaperExtractor.offline().extract(sample.email()), online.extract(sample.email()));
        }
    }
}
