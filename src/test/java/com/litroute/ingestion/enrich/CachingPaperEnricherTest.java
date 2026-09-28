package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.litroute.ingestion.extract.LinkKind;
import com.litroute.ingestion.extract.PaperEnricher;
import com.litroute.model.PaperCandidate;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.EnrichmentCacheStore.Contents;
import com.litroute.util.Titles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M7c's lookup cache: a repeated paper is answered from the cache without touching the network, a
 * failed lookup is never cached, and the cache survives a restart through EnrichmentCacheStore.
 */
public class CachingPaperEnricherTest {

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

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final FakeHttp http = new FakeHttp();

    private OnlinePaperEnricher networkEnricher() {
        return new OnlinePaperEnricher(
                new ArticlePageReader(http),
                new CompositeMetadataLookup(List.of(
                        new CrossrefSource(http, "https://cr.test"), new OpenAlexSource(http, "https://oa.test"))),
                new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs")));
    }

    private void apisAnswer() {
        http.on("cr.test/works?query.bibliographic=", FakeHttp.json("{\"message\":{\"items\":[" + CROSSREF_WORK + "]}}"));
        http.on("oa.test/works/https://doi.org/" + DOI, FakeHttp.json(OPENALEX_WORK));
        http.on("oa.test/hygro.pdf", FakeHttp.pdf());
    }

    private static PaperCandidate sparse(String messageId, String title) {
        return new PaperCandidate(messageId, title, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ---- bypassing the network

    @Test
    public void repeatedPaperIsAnsweredFromTheCacheWithNoNetworkRequestsAtAll() {
        apisAnswer();
        Map<String, PaperCandidate> cache = new HashMap<>();
        CachingPaperEnricher enricher = new CachingPaperEnricher(networkEnricher(), cache);

        PaperCandidate first = enricher.enrich(sparse("<m1>", TITLE), LinkKind.OPAQUE);
        assertEquals(Optional.of(DOI), first.doi());
        assertTrue("first sighting must really query the APIs", !http.requests.isEmpty());
        http.requests.clear();

        PaperCandidate again = enricher.enrich(sparse("<m2>", TITLE), LinkKind.OPAQUE);

        assertTrue("cache hit must not contact any server, requested: " + http.requests, http.requests.isEmpty());
        assertEquals(Optional.of(DOI), again.doi());
        assertEquals(first.authors(), again.authors());
        assertEquals(first.pdfPath(), again.pdfPath());
        assertEquals("the hit belongs to the new email", "<m2>", again.sourceMessageId());
    }

    @Test
    public void titleDifferencesThatDoNotChangeTheLettersStillHitTheCache() {
        apisAnswer();
        CachingPaperEnricher enricher = new CachingPaperEnricher(networkEnricher(), new HashMap<>());
        enricher.enrich(sparse("<m1>", TITLE), LinkKind.OPAQUE);
        http.requests.clear();

        PaperCandidate again = enricher.enrich(sparse("<m2>", TITLE.toUpperCase() + "  "), LinkKind.OPAQUE);

        assertTrue(http.requests.isEmpty());
        assertEquals(Optional.of(DOI), again.doi());
    }

    @Test
    public void cacheHitNeverOverwritesWhatTheNewEmailStates() {
        PaperCandidate cached = new PaperCandidate("<old>", TITLE, List.of("Cached Author"),
                Optional.of("Cached Journal"), Optional.of(2020), Optional.empty(), Optional.of(DOI),
                Optional.of("https://doi.org/" + DOI), Optional.empty(), Optional.empty());
        Map<String, PaperCandidate> cache = new HashMap<>(Map.of(Titles.compact(TITLE), cached));
        CachingPaperEnricher enricher = new CachingPaperEnricher(networkEnricher(), cache);
        PaperCandidate fromEmail = new PaperCandidate("<new>", TITLE, List.of("Email Author"),
                Optional.of("Email Journal"), Optional.empty(), Optional.of("Research article"), Optional.empty(),
                Optional.empty(), Optional.of("Email abstract."), Optional.empty());

        PaperCandidate result = enricher.enrich(fromEmail, LinkKind.OPAQUE);

        assertEquals(List.of("Email Author"), result.authors());
        assertEquals(Optional.of("Email Journal"), result.venue());
        assertEquals(Optional.of("Research article"), result.articleType());
        assertEquals(Optional.of("Email abstract."), result.abstractSnippet());
        assertEquals("gaps are filled from the cache", Optional.of(DOI), result.doi());
        assertEquals(Optional.of(2020), result.year());
        assertTrue(http.requests.isEmpty());
    }

    // ---- what is (not) cached

    @Test
    public void failedLookupIsNotCachedSoTheNextRunTriesTheNetworkAgain() {
        http.on("cr.test", FakeHttp.offline());
        http.on("oa.test", FakeHttp.offline());
        Map<String, PaperCandidate> cache = new HashMap<>();
        CachingPaperEnricher enricher = new CachingPaperEnricher(networkEnricher(), cache);

        PaperCandidate result = enricher.enrich(sparse("<m1>", TITLE), LinkKind.OPAQUE);

        assertEquals(Optional.empty(), result.doi());
        assertTrue("an outage must not be remembered", cache.isEmpty());
    }

    @Test
    public void cachedPaperWhoseSavedPdfWasDeletedIsLookedUpAgain() throws IOException {
        Path pdf = tmp.newFile("gone.pdf").toPath();
        Files.delete(pdf);
        PaperCandidate stale = new PaperCandidate("<old>", TITLE, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(DOI), Optional.empty(), Optional.empty(), Optional.of(pdf));
        Map<String, PaperCandidate> cache = new HashMap<>(Map.of(Titles.compact(TITLE), stale));
        AtomicInteger delegateCalls = new AtomicInteger();
        PaperEnricher counting = (paper, kind) -> {
            delegateCalls.incrementAndGet();
            return paper;
        };

        new CachingPaperEnricher(counting, cache).enrich(sparse("<m1>", TITLE), LinkKind.OPAQUE);

        assertEquals(1, delegateCalls.get());
    }

    @Test
    public void titlesWithNoLatinLettersOrDigitsAreNeverCachedTogether() {
        Map<String, PaperCandidate> cache = new HashMap<>();
        AtomicInteger delegateCalls = new AtomicInteger();
        PaperEnricher withDoi = (paper, kind) -> {
            delegateCalls.incrementAndGet();
            return new PaperCandidate(paper.sourceMessageId(), paper.title(), List.of(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("10.1/" + paper.title().length()),
                    Optional.empty(), Optional.empty(), Optional.empty());
        };
        CachingPaperEnricher enricher = new CachingPaperEnricher(withDoi, cache);

        PaperCandidate first = enricher.enrich(sparse("<m1>", "结构工程中的耐久性研究"), LinkKind.OPAQUE);
        PaperCandidate second = enricher.enrich(sparse("<m2>", "橋梁の地震応答解析"), LinkKind.OPAQUE);

        assertEquals("each goes to the delegate", 2, delegateCalls.get());
        assertTrue(!first.doi().equals(second.doi()));
        assertTrue(cache.isEmpty());
    }

    // ---- surviving a restart (M7c)

    @Test
    public void cacheSavedThroughTheStoreStillPreventsNetworkAccessAfterARestart() throws IOException {
        apisAnswer();
        EnrichmentCacheStore store = new EnrichmentCacheStore(tmp.getRoot().toPath().resolve("enrichment_cache.json"));

        Contents run1 = store.load();
        PaperCandidate enriched = new CachingPaperEnricher(networkEnricher(), run1.lookups())
                .enrich(sparse("<m1>", TITLE), LinkKind.OPAQUE);
        run1.enriched().add(enriched);
        store.save(run1);
        http.requests.clear();

        Contents run2 = store.load();
        PaperCandidate again = new CachingPaperEnricher(networkEnricher(), run2.lookups())
                .enrich(sparse("<m2>", TITLE), LinkKind.OPAQUE);

        assertTrue("second run must not touch the network, requested: " + http.requests, http.requests.isEmpty());
        assertEquals(Optional.of(DOI), again.doi());
        assertEquals(enriched.pdfPath(), again.pdfPath());
        assertTrue("the PDF the cache points at really exists", Files.isRegularFile(again.pdfPath().orElseThrow()));
    }
}
