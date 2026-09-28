package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * Asking Crossref and OpenAlex together, against the replies they really gave for the Hygrothermal
 * paper from the SpringerOpen alert. The two APIs genuinely disagree about that paper - Crossref
 * spells its second author "Luis Silva" and OpenAlex "Luís Silva", and Crossref lists the same PDF
 * URL twice - which is what makes it a fair test of the merge rules.
 *
 * Outages are simulated at the network layer rather than by standing in for a source, so what is
 * being exercised is the real Crossref and OpenAlex code paths meeting a real failure.
 */
public class CompositeMetadataLookupTest {

    private static final String TITLE = "Hygrothermal analysis of ceramic coating durability "
            + "under thermal fluctuations on building facades in Brasília";
    private static final String DOI = "10.1186/s43065-024-00115-x";
    private static final String PDF = "https://link.springer.com/content/pdf/" + DOI + ".pdf";

    /** What M3 asks when the alert gave no DOI - the case that makes Crossref search by title. */
    private static PaperQuery byTitle() {
        return new PaperQuery(TITLE, List.of(), Optional.empty());
    }

    /** What M3 asks when the alert's link already yielded a DOI. */
    private static PaperQuery byDoi() {
        return new PaperQuery(TITLE, List.of(), Optional.of(DOI));
    }

    private static CompositeMetadataLookup lookupOver(RecordedHttp http) {
        return new CompositeMetadataLookup(List.of(new CrossrefSource(http), new OpenAlexSource(http)));
    }

    @Test
    public void bothSourcesAreMergedWithTheEarlierOneWinningFieldByField() {
        PaperMetadata merged = lookupOver(new RecordedHttp()).find(byTitle()).orElseThrow();

        assertEquals(Optional.of(DOI), merged.doi());
        assertEquals("Crossref is asked first, so its spelling of the authors is the one kept",
                List.of("Gilberto Gomes", "Luis Silva", "Elton Bauer"), merged.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), merged.venue());
        assertEquals(Optional.of(2025), merged.year());
    }

    /** Crossref really does list this paper's PDF twice; one URL must come out, not two. */
    @Test
    public void aPdfLocationOfferedByBothSourcesIsNotListedTwice() {
        PaperMetadata merged = lookupOver(new RecordedHttp()).find(byTitle()).orElseThrow();

        assertEquals(List.of(PDF), merged.pdfUrls());
    }

    /**
     * Once Crossref has turned the title into a DOI, OpenAlex must be asked by that DOI - an exact
     * lookup - rather than made to guess from the title all over again.
     */
    @Test
    public void aDoiFoundByTheFirstSourceIsUsedToQueryTheSecondExactly() {
        RecordedHttp http = new RecordedHttp();

        lookupOver(http).find(byTitle());

        assertTrue("Crossref had only the title to go on",
                http.requests.get(0).contains("api.crossref.org/works?query.bibliographic="));
        assertTrue("OpenAlex was asked by DOI: " + http.requests,
                http.requests.stream().anyMatch(u -> u.equals("https://api.openalex.org/works/https://doi.org/" + DOI)));
        assertEquals("OpenAlex never had to search by title", 0,
                http.requests.stream().filter(u -> u.contains("openalex.org/works?search=")).count());
    }

    @Test
    public void whenCrossrefIsDownOpenAlexStillAnswers() {
        PaperMetadata found = lookupOver(new RecordedHttp().offlineFor("api.crossref.org"))
                .find(byDoi()).orElseThrow();

        assertEquals(Optional.of(DOI), found.doi());
        assertEquals("OpenAlex's own spelling now, with no Crossref to override it",
                List.of("Gilberto Gomes", "Luís Silva", "Elton Bauer"), found.authors());
        assertEquals(List.of(PDF), found.pdfUrls());
    }

    @Test
    public void whenOpenAlexIsDownCrossrefStillAnswers() {
        PaperMetadata found = lookupOver(new RecordedHttp().offlineFor("api.openalex.org"))
                .find(byTitle()).orElseThrow();

        assertEquals(Optional.of(DOI), found.doi());
        assertEquals(List.of("Gilberto Gomes", "Luis Silva", "Elton Bauer"), found.authors());
    }

    /** Both APIs unreachable is a paper we learned nothing about, not a failure that stops the run. */
    @Test
    public void bothSourcesDownGivesNoResultInsteadOfAnException() {
        RecordedHttp http = new RecordedHttp().offlineFor("api.crossref.org").offlineFor("api.openalex.org");

        assertEquals(Optional.empty(), lookupOver(http).find(byDoi()));
    }

    /**
     * An API that is down stays down for a while. Retrying it once per paper would cost a timeout on
     * every one of the 31 papers in a batch, so after three failures in a row it is left alone for the
     * rest of the run - while the source that still works keeps being asked.
     */
    @Test
    public void aSourceThatKeepsFailingIsSkippedForTheRestOfTheRun() {
        RecordedHttp http = new RecordedHttp().offlineFor("api.crossref.org");
        CompositeMetadataLookup lookup = lookupOver(http);

        for (int paper = 0; paper < 6; paper++) {
            lookup.find(byDoi());
        }

        assertEquals("Crossref stops being asked after MAX_CONSECUTIVE_FAILURES",
                CompositeMetadataLookup.MAX_CONSECUTIVE_FAILURES, http.count("api.crossref.org"));
        assertEquals("the healthy source is asked for every paper", 6, http.count("api.openalex.org"));
    }

    /**
     * Throttling and timeouts come in bursts, and a source that recovers must not be written off. Two
     * failures then a success, repeatedly, never reaches three in a row - so Crossref is asked about
     * every paper.
     */
    @Test
    public void aSuccessResetsTheFailureCountSoOccasionalBlipsNeverDisableASource() {
        RecordedHttp http = new RecordedHttp()
                .flakyFor("api.crossref.org", true, true, false, true, true, false);
        CompositeMetadataLookup lookup = lookupOver(http);

        for (int paper = 0; paper < 6; paper++) {
            lookup.find(byDoi());
        }

        assertEquals("never three in a row, so Crossref is never skipped", 6, http.count("api.crossref.org"));
    }
}
