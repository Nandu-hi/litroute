package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class OpenAlexSourceTest {

    private static final String TITLE = "Advancing soil-structure interaction (SSI): a comprehensive review of current practices, "
            + "challenges, and future directions";

    private static final String WORK = """
            {"doi":"https://doi.org/10.1186/s43065-025-00118-2","display_name":"%s","publication_year":2025,
             "authorships":[{"author":{"display_name":"Imtiyaz Akbar Najar"}},{"author":{"display_name":"Shanshan Tang"}}],
             "primary_location":{"landing_page_url":"https://oa.test/landing","pdf_url":null,
                                 "source":{"display_name":"Journal of Infrastructure Preservation and Resilience"}},
             "best_oa_location":{"pdf_url":"https://oa.test/ssi.pdf"},
             "locations":[{"pdf_url":"https://oa.test/ssi.pdf"},{"pdf_url":"https://repo.test/ssi-preprint.pdf"},{"pdf_url":null}]}
            """.formatted(TITLE);

    private static final String UNRELATED = """
            {"doi":"https://doi.org/10.9999/other","display_name":"Deep learning for crack detection","authorships":[]}
            """;

    private final FakeHttp http = new FakeHttp();
    private final OpenAlexSource source = new OpenAlexSource(http, "https://oa.test");

    @Test
    public void lookupByDoiReadsTheWorkWithTheDoiPrefixStrippedAndOpenAccessPdfsFirst() throws IOException {
        http.on("oa.test/works/https://doi.org/10.1186/s43065-025-00118-2", FakeHttp.json(WORK));

        PaperMetadata found = source.lookup(new PaperQuery("ignored", List.of(), Optional.of("10.1186/s43065-025-00118-2"))).orElseThrow();

        assertEquals(Optional.of("10.1186/s43065-025-00118-2"), found.doi());
        assertEquals(Optional.of(TITLE), found.title());
        assertEquals(List.of("Imtiyaz Akbar Najar", "Shanshan Tang"), found.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), found.venue());
        assertEquals(Optional.of(2025), found.year());
        assertEquals(Optional.of("https://oa.test/landing"), found.landingUrl());
        // best_oa_location first, no duplicates, nulls skipped.
        assertEquals(List.of("https://oa.test/ssi.pdf", "https://repo.test/ssi-preprint.pdf"), found.pdfUrls());
    }

    @Test
    public void titleSearchPicksTheMatchingResult() throws IOException {
        http.on("oa.test/works?search=", FakeHttp.json("{\"results\":[" + UNRELATED + "," + WORK + "]}"));

        PaperMetadata found = source.lookup(new PaperQuery(TITLE, List.of(), Optional.empty())).orElseThrow();

        assertEquals(Optional.of("10.1186/s43065-025-00118-2"), found.doi());
    }

    @Test
    public void titleSearchWithNothingSimilarIsNoMatch() throws IOException {
        http.on("oa.test/works?search=", FakeHttp.json("{\"results\":[" + UNRELATED + "]}"));

        assertEquals(Optional.empty(), source.lookup(new PaperQuery(TITLE, List.of(), Optional.empty())));
    }

    @Test
    public void unreachableOpenAlexIsAFailureNotANoMatch() {
        http.on("oa.test", FakeHttp.offline());

        assertThrows(IOException.class, () -> source.lookup(new PaperQuery(TITLE, List.of(), Optional.empty())));
    }
}
