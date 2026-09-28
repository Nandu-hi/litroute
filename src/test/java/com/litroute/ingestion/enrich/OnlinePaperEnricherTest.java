package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.ingestion.extract.LinkKind;
import com.litroute.model.PaperCandidate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M3 enrichment, against the replies Crossref, OpenAlex and the publishers really gave for two
 * papers from the sample mails: the SSI review, whose alert carries a readable DOI link, and the
 * Hygrothermal paper, whose alert carries nothing but opaque tracking tokens.
 *
 * What these pin down is the precedence rule: the email always wins, lookups only fill gaps.
 */
public class OnlinePaperEnricherTest {

    private static final String SSI_TITLE = "Advancing soil-structure interaction (SSI): a comprehensive "
            + "review of current practices, challenges, and future directions";
    private static final String SSI_DOI = "10.1186/s43065-025-00118-2";
    private static final String SSI_LINK =
            "https://jipr.springeropen.com/articles/10.1186/s43065-025-00118-2?utm_source=alert&utm_medium=email";

    private static final String HYGRO_TITLE = "Hygrothermal analysis of ceramic coating durability "
            + "under thermal fluctuations on building facades in Brasília";
    private static final String HYGRO_DOI = "10.1186/s43065-024-00115-x";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final RecordedHttp http = new RecordedHttp();

    private OnlinePaperEnricher enricher() {
        return new OnlinePaperEnricher(
                new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(new CrossrefSource(http), new OpenAlexSource(http))),
                new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs")));
    }

    /** What M2 hands over: whatever the alert itself stated, and nothing more. */
    private static PaperCandidate fromAlert(String title, List<String> authors, Optional<String> url) {
        return new PaperCandidate("<m@mail.gmail.com>", title, authors, Optional.empty(), Optional.empty(),
                Optional.of("REVIEW"), Optional.empty(), url, Optional.empty(), Optional.empty());
    }

    @Test
    public void aReadableLinkGivesTheDoiVenueYearAndPdfTheAlertNeverStated() {
        PaperCandidate enriched = enricher().enrich(
                fromAlert(SSI_TITLE, List.of("Imtiyaz Akbar Najar"), Optional.of(SSI_LINK)), LinkKind.READABLE);

        assertEquals(Optional.of(SSI_DOI), enriched.doi());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), enriched.venue());
        assertEquals(Optional.of(2025), enriched.year());
        assertEquals("10.1186_s43065-025-00118-2.pdf", enriched.pdfPath().orElseThrow().getFileName().toString());
        assertTrue(Files.isRegularFile(enriched.pdfPath().orElseThrow()));
    }

    /**
     * With only opaque tokens in the mail there is no publisher page to read, so the paper has to be
     * identified by title through the APIs alone - and the publisher's own site must never be touched.
     */
    @Test
    public void anOpaqueLinkIsIdentifiedByTitleThroughTheApisAlone() {
        PaperCandidate enriched = enricher().enrich(
                fromAlert(HYGRO_TITLE, List.of("Gilberto Gomes"), Optional.empty()), LinkKind.OPAQUE);

        assertEquals(Optional.of(HYGRO_DOI), enriched.doi());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), enriched.venue());
        assertEquals("with no link of its own, the DOI becomes the paper's URL",
                Optional.of("https://doi.org/" + HYGRO_DOI), enriched.url());
        assertTrue(Files.isRegularFile(enriched.pdfPath().orElseThrow()));
        assertFalse("no publisher page may be read for an opaque link",
                http.requested("jipr.springeropen.com/articles"));
    }

    /** The alert's own words are never overwritten, however confident a lookup is. */
    @Test
    public void whatTheEmailStatedIsNeverOverwrittenByALookup() {
        PaperCandidate fromEmail = new PaperCandidate("<m@mail.gmail.com>", SSI_TITLE,
                List.of("Najar", "Ahmadi"), Optional.of("The Journal As The Alert Named It"), Optional.of(2024),
                Optional.of("REVIEW"), Optional.empty(), Optional.of(SSI_LINK), Optional.of("As the alert put it."),
                Optional.empty());

        PaperCandidate enriched = enricher().enrich(fromEmail, LinkKind.READABLE);

        assertEquals(List.of("Najar", "Ahmadi"), enriched.authors());
        assertEquals(Optional.of("The Journal As The Alert Named It"), enriched.venue());
        assertEquals(Optional.of(2024), enriched.year());
        assertEquals(Optional.of(SSI_LINK), enriched.url());
        assertEquals(Optional.of("As the alert put it."), enriched.abstractSnippet());
        assertEquals("the DOI is the one thing the alert did not have", Optional.of(SSI_DOI), enriched.doi());
    }

    /** Everything offline: the paper must come back exactly as the alert stated it, not half-filled. */
    @Test
    public void withEverythingOfflineTheCandidateComesBackUnchanged() {
        OnlinePaperEnricher offline = new OnlinePaperEnricher(
                new ArticlePageReader(new RecordedHttp().offlineFor("")),
                new CompositeMetadataLookup(List.of(
                        new CrossrefSource(new RecordedHttp().offlineFor("")),
                        new OpenAlexSource(new RecordedHttp().offlineFor("")))),
                new PdfDownloader(new RecordedHttp().offlineFor(""), tmp.getRoot().toPath().resolve("pdfs")));
        PaperCandidate fromEmail = fromAlert(SSI_TITLE, List.of("Imtiyaz Akbar Najar"), Optional.of(SSI_LINK));

        assertEquals(fromEmail, offline.enrich(fromEmail, LinkKind.READABLE));
    }

    /** A publisher that blocks robots is not a failure either - the APIs still identify the paper. */
    @Test
    public void aPublisherThatBlocksRobotsStillLeavesTheApisToIdentifyThePaper() {
        // M2 already read the DOI out of the readable ascelibrary.org link, so what reaches M3 carries it.
        PaperCandidate fromEmail = new PaperCandidate("<m@mail.gmail.com>",
                "Preparing for Inevitable Surprise: Managing Unexpected Interinfrastructural Connectivities "
                        + "in Post-Disaster Infrastructure Service Restoration",
                List.of(), Optional.empty(), Optional.empty(), Optional.of("Technical Notes"),
                Optional.of("10.1061/nhrefo.nheng-2711"),
                Optional.of("https://ascelibrary.org/doi/10.1061/nhrefo.nheng-2711?af=R"),
                Optional.empty(), Optional.empty());

        PaperCandidate enriched = enricher().enrich(fromEmail, LinkKind.READABLE);

        assertEquals(Optional.of("10.1061/nhrefo.nheng-2711"), enriched.doi());
        assertEquals(Optional.of("Natural Hazards Review"), enriched.venue());
        assertEquals("ASCE serves no open-access PDF to a robot", Optional.empty(), enriched.pdfPath());
    }

    /** A PDF found once is not fetched a second time when the same paper comes round again. */
    @Test
    public void aPdfIsNotDownloadedTwiceForTheSamePaper() {
        OnlinePaperEnricher enricher = enricher();
        PaperCandidate first = enricher.enrich(
                fromAlert(SSI_TITLE, List.of(), Optional.of(SSI_LINK)), LinkKind.READABLE);
        long downloadsAfterFirst = http.count("link.springer.com/content/pdf");

        PaperCandidate second = enricher.enrich(
                fromAlert(SSI_TITLE, List.of(), Optional.of(SSI_LINK)), LinkKind.READABLE);

        assertEquals(first.pdfPath(), second.pdfPath());
        assertEquals("the file was already on disk", downloadsAfterFirst,
                http.count("link.springer.com/content/pdf"));
    }
}
