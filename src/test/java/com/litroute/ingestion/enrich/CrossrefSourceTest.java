package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class CrossrefSourceTest {

    private static final String TITLE =
            "Hygrothermal analysis of ceramic coating durability under thermal fluctuations on building facades in Brasília";

    private static final String WORK = """
            {"DOI":"10.1186/s43065-024-00115-x","title":["%s"],
             "author":[{"given":"Gilberto","family":"Gomes"},{"given":"Luis","family":"Silva"},{"name":"Some Consortium"}],
             "container-title":["Journal of Infrastructure Preservation and Resilience"],
             "issued":{"date-parts":[[2025,2,10]]},"URL":"https://doi.org/10.1186/s43065-024-00115-x",
             "link":[{"URL":"https://cr.test/x1.pdf","content-type":"application/pdf"},
                     {"URL":"https://cr.test/x1.xml","content-type":"text/xml"}]}
            """.formatted(TITLE);

    private static final String UNRELATED_WORK = """
            {"DOI":"10.9999/other","title":["Seismic fragility of masonry arch bridges"],"author":[],"container-title":["Other Journal"]}
            """;

    private final FakeHttp http = new FakeHttp();
    private final CrossrefSource source = new CrossrefSource(http, "https://cr.test");

    private static PaperQuery byTitle() {
        return new PaperQuery(TITLE, List.of(), Optional.empty());
    }

    @Test
    public void lookupByDoiReadsTheWorkAndKeepsOnlyPdfLinks() throws IOException {
        http.on("cr.test/works/10.1186/s43065-024-00115-x", FakeHttp.json("{\"message\":" + WORK + "}"));

        PaperMetadata found = source.lookup(new PaperQuery("ignored", List.of(), Optional.of("10.1186/s43065-024-00115-x"))).orElseThrow();

        assertEquals(Optional.of("10.1186/s43065-024-00115-x"), found.doi());
        assertEquals(Optional.of(TITLE), found.title());
        assertEquals(List.of("Gilberto Gomes", "Luis Silva", "Some Consortium"), found.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), found.venue());
        assertEquals(Optional.of(2025), found.year());
        assertEquals(Optional.of("https://doi.org/10.1186/s43065-024-00115-x"), found.landingUrl());
        assertEquals(List.of("https://cr.test/x1.pdf"), found.pdfUrls());
    }

    /** Seen live: Crossref returned the venue "Earthquake Engineering &amp; Structural Dynamics". */
    @Test
    public void htmlEntitiesAndTagsInCrossrefTextAreCleanedToWhatAReaderSees() throws IOException {
        http.on("cr.test/works/10.9999/html", FakeHttp.json("""
                {"message":{"DOI":"10.9999/html","title":["Effect of CO<sub>2</sub> on <i>in situ</i> curing"],
                 "author":[{"given":"Ren&eacute;","family":"O&#39;Brien"}],
                 "container-title":["Earthquake Engineering &amp; Structural Dynamics"]}}
                """));

        PaperMetadata found = source.lookup(new PaperQuery("x", List.of(), Optional.of("10.9999/html"))).orElseThrow();

        assertEquals(Optional.of("Earthquake Engineering & Structural Dynamics"), found.venue());
        assertEquals(Optional.of("Effect of CO2 on in situ curing"), found.title());
        assertEquals(List.of("René O'Brien"), found.authors());
    }

    @Test
    public void doiCrossrefDoesNotKnowFallsBackToTheTitleSearch() throws IOException {
        http.on("cr.test/works/10.9999/unknown", FakeHttp.status(404));
        http.on("works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + WORK + "]}}"));

        PaperMetadata found = source.lookup(new PaperQuery(TITLE, List.of(), Optional.of("10.9999/unknown"))).orElseThrow();

        assertEquals(Optional.of("10.1186/s43065-024-00115-x"), found.doi());
    }

    @Test
    public void titleSearchTakesTheHitWhoseTitleMatchesNotJustTheFirstOne() throws IOException {
        http.on("works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + UNRELATED_WORK + "," + WORK + "]}}"));

        assertEquals(Optional.of("10.1186/s43065-024-00115-x"), source.lookup(byTitle()).orElseThrow().doi());
    }

    @Test
    public void titleSearchIsToleratedTheQuirksPublishersAddToTitles() throws IOException {
        // The alert dropped spaces ("ofEarthquake") and used an en dash; Crossref has the clean title.
        http.on("works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + WORK + "]}}"));
        String quirky = "Hygrothermal analysis of ceramic coating durability under thermal fluctuations"
                + "on building facades in Brasília";

        assertEquals(Optional.of("10.1186/s43065-024-00115-x"),
                source.lookup(new PaperQuery(quirky, List.of(), Optional.empty())).orElseThrow().doi());
    }

    @Test
    public void titleSearchWithNothingSimilarIsNoMatchNotAWrongPaper() throws IOException {
        http.on("works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + UNRELATED_WORK + "]}}"));

        assertEquals(Optional.empty(), source.lookup(byTitle()));
    }

    @Test
    public void unreachableOrThrottledCrossrefIsAFailureNotANoMatch() {
        http.on("works?query.bibliographic=", FakeHttp.status(503));
        assertThrows(IOException.class, () -> source.lookup(byTitle()));

        FakeHttp throttled = new FakeHttp().on("cr.test", FakeHttp.status(429));
        assertThrows(IOException.class, () -> new CrossrefSource(throttled, "https://cr.test").lookup(byTitle()));

        FakeHttp offline = new FakeHttp().on("cr.test", FakeHttp.offline());
        assertThrows(IOException.class, () -> new CrossrefSource(offline, "https://cr.test").lookup(byTitle()));
    }

    @Test
    public void titleIsUrlEncodedInTheSearchRequest() throws IOException {
        http.on("works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[]}}"));

        source.lookup(new PaperQuery("A & B: 100% \"quoted\" title", List.of(), Optional.empty()));

        assertTrue(http.requests.get(0), http.requests.get(0).contains("query.bibliographic=A+%26+B%3A+100%25+%22quoted%22+title"));
    }
}
