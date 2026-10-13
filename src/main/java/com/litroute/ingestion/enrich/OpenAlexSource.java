package com.litroute.ingestion.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.litroute.util.Titles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * OpenAlex API. Its strength here is open-access PDF locations ({@code best_oa_location.pdf_url} and
 * the other {@code locations}), which Crossref usually lacks, and abstracts for papers whose
 * publisher deposited none with Crossref. Like Crossref: exact by DOI, else a title search whose best
 * hit must genuinely match the title.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Input:  new OpenAlexSource(fetcher).lookup(new PaperQuery("Advancing soil-structure interaction (SSI): ...",
 *                                                            List.of(), Optional.of("10.1186/s43065-025-00118-2")))
 *         -> GET https://api.openalex.org/works/https://doi.org/10.1186/s43065-025-00118-2
 * Output: Optional[PaperMetadata[doi=Optional[10.1186/s43065-025-00118-2],
 *         title=Optional[Advancing soil-structure interaction (SSI): ...], authors=[...],
 *         venue=Optional[Journal of Infrastructure Preservation and Resilience], year=Optional[2025], abstractText=...,
 *         landingUrl=Optional[https://doi.org/10.1186/s43065-025-00118-2],
 *         pdfUrls=[https://jipr.springeropen.com/counter/pdf/10.1186/s43065-025-00118-2, ...]]]
 * }</pre>
 */
public final class OpenAlexSource implements MetadataSource {

    private static final String DEFAULT_BASE_URL = "https://api.openalex.org";
    private static final String DOI_PREFIX = "https://doi.org/";
    private static final String FIELDS =
            "doi,display_name,authorships,publication_year,primary_location,best_oa_location,locations";

    private final HttpFetcher fetcher;
    private final String baseUrl;

    /**
     * Creates a source that queries the public OpenAlex API at {@code https://api.openalex.org}.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  new OpenAlexSource(new JdkHttpFetcher("LitRoute/1.0 ..."))
     * Output: a source whose lookups go to https://api.openalex.org/works...
     * }</pre>
     *
     * @param fetcher the HTTP client used for every request
     */
    public OpenAlexSource(HttpFetcher fetcher) {
        this(fetcher, DEFAULT_BASE_URL);
    }

    /**
     * Creates a source that queries an OpenAlex-compatible API at another address, e.g. a local test server.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  new OpenAlexSource(fetcher, "http://localhost:8080")
     * Output: a source whose DOI lookups go to http://localhost:8080/works/https://doi.org/...
     * }</pre>
     *
     * @param fetcher the HTTP client used for every request
     * @param baseUrl the API root, without a trailing slash
     */
    OpenAlexSource(HttpFetcher fetcher, String baseUrl) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
    }

    /**
     * The source's name, used in log and exception messages.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  (none)
     * Output: "OpenAlex"
     * }</pre>
     *
     * @return {@code "OpenAlex"}
     */
    @Override
    public String name() {
        return "OpenAlex";
    }

    /**
     * Finds the paper in OpenAlex:
     *
     * <ol>
     *   <li>with a DOI, fetches {@code /works/https://doi.org/<doi>} and returns that record if it exists;
     *   <li>otherwise (or if OpenAlex has no record for the DOI), searches the title, takes the top 5 hits and
     *       returns the one whose title is most similar to the query's, but only if that similarity is at least
     *       {@link MetadataSource#ACCEPT_THRESHOLD} (0.88).
     * </ol>
     *
     * The search asks only for the fields in {@code FIELDS}, which doesn't include {@code abstract_inverted_index},
     * so only a DOI lookup can return an abstract.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  PaperQuery(title "Advancing soil-structure interaction (SSI): ...", doi Optional[10.1186/s43065-025-00118-2])
     * Output: Optional[PaperMetadata[doi=Optional[10.1186/s43065-025-00118-2], ...]]   (exact DOI record)
     *
     * Input:  PaperQuery(title "Wave loads on floating offshore wind turbines", doi Optional.empty)
     *         -> search hits titled "Wave loads on floating offshore wind turbines." (similarity ~0.99),
     *            "Wave loads on ships" (~0.6)   (scores illustrative)
     * Output: Optional[PaperMetadata[title=Optional[Wave loads on floating offshore wind turbines.], ...]]
     *
     * Input:  same query, but the best hit is only "Floating wind turbine design" (~0.5)
     * Output: Optional.empty
     *
     * Input:  any query while OpenAlex answers HTTP 429
     * Output: throws IOException("OpenAlex unavailable: HTTP 429")
     * }</pre>
     *
     * @param query the paper's title, authors and DOI if known
     * @return the matching record, or empty if OpenAlex has none that matches well enough
     * @throws IOException if OpenAlex is unreachable, throttling (429), failing (5xx) or returns invalid JSON
     */
    @Override
    public Optional<PaperMetadata> lookup(PaperQuery query) throws IOException {
        if (query.doi().isPresent()) {
            Optional<JsonNode> work = ApiJson.get(fetcher, name(),
                    baseUrl + "/works/" + DOI_PREFIX + ApiJson.encodeDoi(query.doi().get()));
            if (work.isPresent()) {
                return Optional.of(toMetadata(work.get()));
            }
        }
        Optional<JsonNode> search = ApiJson.get(fetcher, name(),
                baseUrl + "/works?search=" + ApiJson.encode(query.title()) + "&per-page=5&select=" + FIELDS);
        if (search.isEmpty()) {
            return Optional.empty();
        }
        PaperMetadata best = null;
        double bestScore = 0;
        for (JsonNode item : search.get().path("results")) {
            PaperMetadata candidate = toMetadata(item);
            double score = candidate.title().map(title -> Titles.similarity(title, query.title())).orElse(0.0);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return bestScore >= MetadataSource.ACCEPT_THRESHOLD ? Optional.ofNullable(best) : Optional.empty();
    }

    /**
     * Converts one OpenAlex work record into {@link PaperMetadata}:
     *
     * <ul>
     *   <li>DOI from {@code doi}, with the {@code https://doi.org/} prefix removed;
     *   <li>title from {@code display_name}, authors from each {@code authorships[].author.display_name};
     *   <li>venue from {@code primary_location.source.display_name}; year from {@code publication_year} (0 or
     *       missing gives empty);
     *   <li>abstract rebuilt from {@code abstract_inverted_index};
     *   <li>landing URL from {@code primary_location.landing_page_url};
     *   <li>PDF URLs from {@code best_oa_location}, then {@code primary_location}, then every entry in
     *       {@code locations}, without duplicates.
     * </ul>
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  {"doi": "https://doi.org/10.1186/s43065-025-00118-2",
     *          "display_name": "Advancing soil-structure interaction (SSI): ...",
     *          "publication_year": 2025,
     *          "authorships": [{"author": {"display_name": "A. Kumar"}}],
     *          "primary_location": {"landing_page_url": "https://doi.org/10.1186/s43065-025-00118-2",
     *                               "pdf_url": "https://jipr.springeropen.com/counter/pdf/10.1186/s43065-025-00118-2",
     *                               "source": {"display_name": "Journal of Infrastructure Preservation and Resilience"}},
     *          "best_oa_location": {"pdf_url": "https://jipr.springeropen.com/counter/pdf/10.1186/s43065-025-00118-2"}}
     * Output: PaperMetadata[doi=Optional[10.1186/s43065-025-00118-2], title=Optional[Advancing soil-structure ...],
     *         authors=[A. Kumar], venue=Optional[Journal of Infrastructure Preservation and Resilience],
     *         year=Optional[2025], abstractText=Optional.empty,
     *         landingUrl=Optional[https://doi.org/10.1186/s43065-025-00118-2],
     *         pdfUrls=[https://jipr.springeropen.com/counter/pdf/10.1186/s43065-025-00118-2]]
     * }</pre>
     *
     * @param work one work object, from a DOI lookup or from a search's {@code results}
     * @return the record's metadata; fields OpenAlex doesn't have are empty
     */
    private static PaperMetadata toMetadata(JsonNode work) {
        List<String> authors = new ArrayList<>();
        for (JsonNode authorship : work.path("authorships")) {
            ApiJson.text(authorship.path("author").path("display_name")).ifPresent(authors::add);
        }
        List<String> pdfUrls = new ArrayList<>();
        addPdf(pdfUrls, work.path("best_oa_location"));
        addPdf(pdfUrls, work.path("primary_location"));
        for (JsonNode location : work.path("locations")) {
            addPdf(pdfUrls, location);
        }
        int year = work.path("publication_year").asInt(0);
        return new PaperMetadata(
                ApiJson.text(work.path("doi")).map(doi -> doi.startsWith(DOI_PREFIX) ? doi.substring(DOI_PREFIX.length()) : doi),
                ApiJson.text(work.path("display_name")),
                authors,
                ApiJson.text(work.path("primary_location").path("source").path("display_name")),
                year > 0 ? Optional.of(year) : Optional.empty(),
                abstractOf(work.path("abstract_inverted_index")),
                ApiJson.text(work.path("primary_location").path("landing_page_url")),
                pdfUrls);
    }

    /**
     * OpenAlex stores an abstract "inverted" - each word with the positions it occurs at - for
     * licensing reasons; putting every word back at its positions restores the text. A leading "Abstract" heading
     * is removed (see {@link ApiJson#abstractText}).
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  {"Abstract": [0], "We": [1], "study": [2], "wave": [3, 6], "loads.": [4], "Each": [5]}
     * Output: Optional[We study wave loads. Each wave]
     *
     * Input:  missing node or {}
     * Output: Optional.empty
     * }</pre>
     *
     * @param invertedIndex the work's {@code abstract_inverted_index} (word -> positions), possibly missing
     * @return the abstract as plain text, or empty if there is none
     */
    private static Optional<String> abstractOf(JsonNode invertedIndex) {
        Map<Integer, String> byPosition = new TreeMap<>();
        invertedIndex.fields().forEachRemaining(word -> word.getValue()
                .forEach(position -> byPosition.put(position.asInt(), word.getKey())));
        return ApiJson.abstractText(String.join(" ", byPosition.values()));
    }

    /**
     * Appends a location's {@code pdf_url} to the list, unless it is missing, blank or already there.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  pdfUrls = [https://a.org/x.pdf], location {"pdf_url": "https://b.org/y.pdf"}
     * Output: pdfUrls = [https://a.org/x.pdf, https://b.org/y.pdf]
     *
     * Input:  pdfUrls = [https://a.org/x.pdf], location {"pdf_url": "https://a.org/x.pdf"} or {"pdf_url": null}
     * Output: pdfUrls unchanged
     * }</pre>
     *
     * @param pdfUrls the list being built, changed in place
     * @param location one OpenAlex location object, possibly missing
     */
    private static void addPdf(List<String> pdfUrls, JsonNode location) {
        ApiJson.text(location.path("pdf_url")).filter(url -> !pdfUrls.contains(url)).ifPresent(pdfUrls::add);
    }
}
