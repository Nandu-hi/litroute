package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.ingestion.extract.LinkKind;
import com.litroute.model.PaperCandidate;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M7c's lookup cache in front of real enrichment. The papers are from the sample mails and the
 * replies are the ones the APIs really gave, so what is exercised here is the caching decision
 * itself: when a lookup may be skipped, and when it may not.
 */
public class CachingPaperEnricherTest {

    private static final String SSI_TITLE = "Advancing soil-structure interaction (SSI): a comprehensive "
            + "review of current practices, challenges, and future directions";
    private static final String SSI_DOI = "10.1186/s43065-025-00118-2";
    private static final String SSI_LINK =
            "https://jipr.springeropen.com/articles/10.1186/s43065-025-00118-2?utm_source=alert&utm_medium=email";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final RecordedHttp http = new RecordedHttp();
    private final Map<String, PaperCandidate> cache = new HashMap<>();

    private CachingPaperEnricher enricher() {
        return new CachingPaperEnricher(new OnlinePaperEnricher(
                new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(new CrossrefSource(http), new OpenAlexSource(http))),
                new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs"))), cache);
    }

    private static PaperCandidate fromAlert(String title, List<String> authors) {
        return new PaperCandidate("<m@mail.gmail.com>", title, authors, Optional.empty(), Optional.empty(),
                Optional.of("REVIEW"), Optional.empty(), Optional.of(SSI_LINK), Optional.empty(), Optional.empty());
    }

    @Test
    public void aPaperSeenBeforeIsAnsweredFromTheCacheWithNoNetworkAccess() {
        CachingPaperEnricher enricher = enricher();
        PaperCandidate first = enricher.enrich(fromAlert(SSI_TITLE, List.of()), LinkKind.READABLE);
        assertTrue("precondition: the first pass really did go to the network", http.requests.size() > 0);
        http.requests.clear();

        PaperCandidate again = enricher.enrich(fromAlert(SSI_TITLE, List.of()), LinkKind.READABLE);

        assertEquals(List.of(), http.requests);
        assertEquals(first.doi(), again.doi());
        assertEquals(first.venue(), again.venue());
        assertEquals(first.pdfPath(), again.pdfPath());
    }

    /** Punctuation and case differences between two alerts must not miss the cache. */
    @Test
    public void theSameTitleWrittenDifferentlyStillHitsTheCache() {
        CachingPaperEnricher enricher = enricher();
        enricher.enrich(fromAlert(SSI_TITLE, List.of()), LinkKind.READABLE);
        http.requests.clear();

        PaperCandidate again = enricher.enrich(fromAlert(
                "ADVANCING SOIL-STRUCTURE INTERACTION (SSI): A COMPREHENSIVE REVIEW OF CURRENT "
                        + "PRACTICES, CHALLENGES, AND FUTURE DIRECTIONS!", List.of()), LinkKind.READABLE);

        assertEquals(List.of(), http.requests);
        assertEquals(Optional.of(SSI_DOI), again.doi());
    }

    /** On a cache hit the new email still wins; the cache only fills what that email left empty. */
    @Test
    public void onACacheHitTheEmailStillWins() {
        CachingPaperEnricher enricher = enricher();
        enricher.enrich(fromAlert(SSI_TITLE, List.of()), LinkKind.READABLE);

        PaperCandidate again = enricher.enrich(new PaperCandidate("<other@mail.gmail.com>", SSI_TITLE,
                List.of("As The Second Alert Named Them"), Optional.of("A Different Venue"), Optional.of(2019),
                Optional.of("RESEARCH"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                LinkKind.READABLE);

        assertEquals(List.of("As The Second Alert Named Them"), again.authors());
        assertEquals(Optional.of("A Different Venue"), again.venue());
        assertEquals(Optional.of(2019), again.year());
        assertEquals("<other@mail.gmail.com>", again.sourceMessageId());
        assertEquals("the DOI neither alert had comes from the cache", Optional.of(SSI_DOI), again.doi());
    }

    /**
     * A lookup that found nothing must not be remembered. Otherwise one outage would be cached as
     * fact and the paper would never be looked up again on any later run.
     */
    @Test
    public void aLookupThatFoundNothingIsNotCached() {
        RecordedHttp offline = new RecordedHttp().offlineFor("");
        CachingPaperEnricher enricher = new CachingPaperEnricher(new OnlinePaperEnricher(
                new ArticlePageReader(offline),
                new CompositeMetadataLookup(List.of(new CrossrefSource(offline), new OpenAlexSource(offline))),
                new PdfDownloader(offline, tmp.getRoot().toPath().resolve("pdfs"))), cache);

        PaperCandidate result = enricher.enrich(fromAlert(SSI_TITLE, List.of()), LinkKind.READABLE);

        assertEquals(Optional.empty(), result.doi());
        assertTrue("nothing may be remembered from a failed lookup", cache.isEmpty());
    }

    /**
     * The cache records where a PDF was saved. If that file has since been deleted, the entry is stale
     * and the paper has to be looked up again rather than pointing at a file that is not there.
     */
    @Test
    public void aCachedEntryWhosePdfHasBeenDeletedIsLookedUpAgain() throws Exception {
        CachingPaperEnricher enricher = enricher();
        PaperCandidate first = enricher.enrich(fromAlert(SSI_TITLE, List.of()), LinkKind.READABLE);
        Files.delete(first.pdfPath().orElseThrow());
        http.requests.clear();

        PaperCandidate again = enricher.enrich(fromAlert(SSI_TITLE, List.of()), LinkKind.READABLE);

        assertFalse("the stale entry must not be trusted", http.requests.isEmpty());
        assertTrue("the PDF the cache points at exists again",
                Files.isRegularFile(again.pdfPath().orElseThrow()));
    }

    // CachingPaperEnricher's "title normalizes to nothing" guard has no test here: it needs a title
    // with no Latin letter or digit in it, and none of the 31 papers in the sample mails has one.
    // Reaching that branch would mean inventing a paper, so the guard stands on its javadoc instead.
}
