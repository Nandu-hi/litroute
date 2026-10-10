package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * Crossref lookups, against the replies Crossref really gave for the papers in the sample mails.
 */
public class CrossrefSourceTest {

    private static final String HYGRO_DOI = "10.1186/s43065-024-00115-x";
    private static final String HYGRO_TITLE = "Hygrothermal analysis of ceramic coating durability "
            + "under thermal fluctuations on building facades in Brasília";

    private final RecordedHttp http = new RecordedHttp();
    private final CrossrefSource source = new CrossrefSource(http);

    @Test
    public void aDoiLookupReturnsThatExactWork() throws IOException {
        PaperMetadata found = source.lookup(
                new PaperQuery(HYGRO_TITLE, List.of(), Optional.of(HYGRO_DOI))).orElseThrow();

        assertEquals(Optional.of(HYGRO_DOI), found.doi());
        assertEquals(Optional.of(HYGRO_TITLE), found.title());
        assertEquals("Crossref stores given and family names apart; they are joined given-name-first",
                List.of("Gilberto Gomes", "Luis Silva", "Elton Bauer"), found.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), found.venue());
        assertEquals(Optional.of(2025), found.year());
        assertEquals(Optional.of("https://doi.org/" + HYGRO_DOI), found.landingUrl());
        assertTrue("the deposited JATS abstract, as plain text without its heading", found.abstractText().orElseThrow()
                .startsWith("Building facades are continuously subjected to various external agents"));
    }

    /** A paper whose alert gave no DOI has to be found from its title alone. */
    @Test
    public void aTitleSearchFindsTheWorkWhenNoDoiIsKnown() throws IOException {
        PaperMetadata found = source.lookup(
                new PaperQuery(HYGRO_TITLE, List.of(), Optional.empty())).orElseThrow();

        assertEquals(Optional.of(HYGRO_DOI), found.doi());
        assertEquals(Optional.of(HYGRO_TITLE), found.title());
    }

    /**
     * A bibliographic search always returns Crossref's nearest guesses, even when the paper is not
     * there at all: asked for a title that exists nowhere, the real API answers with five genuine but
     * unrelated works ("Bamboo basket weaving" and friends). None of them may be accepted, or the
     * pipeline would staple a stranger's DOI onto the paper and every later stage would believe it.
     */
    @Test
    public void aSearchThatOnlyFindsUnrelatedWorkYieldsNothing() throws IOException {
        Optional<PaperMetadata> found = source.lookup(new PaperQuery(
                "Ultrasonic levitation of Antarctic basket-weaving looms under Martian gravity",
                List.of(), Optional.empty()));

        assertEquals(Optional.empty(), found);
        assertTrue("the search really was performed", http.requested("query.bibliographic="));
    }

    /** Crossref being down has to reach CompositeMetadataLookup, which is what decides to skip it. */
    @Test
    public void anUnreachableApiIsReportedToTheCaller() {
        CrossrefSource offline = new CrossrefSource(new RecordedHttp().offlineFor("crossref.org"));

        assertThrows(IOException.class, () -> offline.lookup(
                new PaperQuery(HYGRO_TITLE, List.of(), Optional.of(HYGRO_DOI))));
    }
}
