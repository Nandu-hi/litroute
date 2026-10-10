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
 */
public final class OpenAlexSource implements MetadataSource {

    private static final String DEFAULT_BASE_URL = "https://api.openalex.org";
    private static final String DOI_PREFIX = "https://doi.org/";
    private static final String FIELDS =
            "doi,display_name,authorships,publication_year,primary_location,best_oa_location,locations";

    private final HttpFetcher fetcher;
    private final String baseUrl;

    public OpenAlexSource(HttpFetcher fetcher) {
        this(fetcher, DEFAULT_BASE_URL);
    }

    OpenAlexSource(HttpFetcher fetcher, String baseUrl) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "OpenAlex";
    }

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
     * licensing reasons; putting every word back at its positions restores the text.
     */
    private static Optional<String> abstractOf(JsonNode invertedIndex) {
        Map<Integer, String> byPosition = new TreeMap<>();
        invertedIndex.fields().forEachRemaining(word -> word.getValue()
                .forEach(position -> byPosition.put(position.asInt(), word.getKey())));
        return ApiJson.abstractText(String.join(" ", byPosition.values()));
    }

    private static void addPdf(List<String> pdfUrls, JsonNode location) {
        ApiJson.text(location.path("pdf_url")).filter(url -> !pdfUrls.contains(url)).ifPresent(pdfUrls::add);
    }
}
