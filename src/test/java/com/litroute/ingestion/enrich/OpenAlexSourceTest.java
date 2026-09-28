package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * OpenAlex lookups, against the replies OpenAlex really gave for the papers in the sample mails.
 * OpenAlex earns its place in the pipeline by knowing where the open-access PDF is, which Crossref
 * usually does not.
 */
public class OpenAlexSourceTest {

    private static final String HYGRO_DOI = "10.1186/s43065-024-00115-x";
    private static final String HYGRO_TITLE = "Hygrothermal analysis of ceramic coating durability "
            + "under thermal fluctuations on building facades in Brasília";

    private final RecordedHttp http = new RecordedHttp();
    private final OpenAlexSource source = new OpenAlexSource(http);

    private static PaperQuery byDoi() {
        return new PaperQuery(HYGRO_TITLE, List.of(), Optional.of(HYGRO_DOI));
    }

    @Test
    public void aDoiLookupReturnsWhatOpenAlexKnowsAboutThatWork() throws IOException {
        PaperMetadata found = source.lookup(byDoi()).orElseThrow();

        assertEquals("the DOI comes back bare, not as the https://doi.org/... form OpenAlex stores",
                Optional.of(HYGRO_DOI), found.doi());
        assertEquals(Optional.of(HYGRO_TITLE), found.title());
        assertEquals(List.of("Gilberto Gomes", "Luís Silva", "Elton Bauer"), found.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), found.venue());
        assertEquals(Optional.of(2025), found.year());
    }

    /** The open-access PDF location is the reason this source is in the pipeline at all. */
    @Test
    public void theOpenAccessPdfLocationIsReported() throws IOException {
        PaperMetadata found = source.lookup(byDoi()).orElseThrow();

        assertTrue(found.pdfUrls().contains("https://link.springer.com/content/pdf/" + HYGRO_DOI + ".pdf"));
        assertFalse("this work has a second location with no pdf_url; it must not become an empty entry",
                found.pdfUrls().contains(""));
    }

    // OpenAlex's title-search branch has no test here on purpose: across all six sample mails it is
    // never taken. Crossref answers first and always supplies a DOI, so CompositeMetadataLookup only
    // ever asks OpenAlex by DOI, and the recording therefore contains no OpenAlex title search to
    // replay. Covering it would mean inventing a request the pipeline does not make.

    /**
     * An unreachable API is reported, not swallowed: CompositeMetadataLookup is the layer that
     * decides a dead source should be skipped, and it can only do that if it hears about the failure.
     */
    @Test
    public void anUnreachableApiIsReportedToTheCaller() {
        OpenAlexSource offline = new OpenAlexSource(new RecordedHttp().offlineFor("openalex.org"));

        assertThrows(IOException.class, () -> offline.lookup(byDoi()));
    }
}
