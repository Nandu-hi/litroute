package com.litroute.ingestion.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.litroute.util.Titles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Crossref REST API. With a DOI it fetches that exact work; without one it runs a bibliographic
 * search on the title and accepts the best hit only if its title really matches. Among hits whose
 * titles match, one in the journal the alert names comes first, then any published version before a
 * preprint: a paper and its SSRN preprint share a title, and Crossref may list the preprint first.
 * Only the exact work carries the abstract (when the publisher deposited one); search hits are kept small.
 */
public final class CrossrefSource implements MetadataSource {

    private static final String DEFAULT_BASE_URL = "https://api.crossref.org";
    private static final String FIELDS = "DOI,title,author,container-title,issued,URL,link,type";
    /** Crossref's type for preprints and other posted content (SSRN, Research Square, ...). */
    private static final String PREPRINT = "posted-content";

    private final HttpFetcher fetcher;
    private final String baseUrl;

    public CrossrefSource(HttpFetcher fetcher) {
        this(fetcher, DEFAULT_BASE_URL);
    }

    CrossrefSource(HttpFetcher fetcher, String baseUrl) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "Crossref";
    }

    @Override
    public Optional<PaperMetadata> lookup(PaperQuery query) throws IOException {
        if (query.doi().isPresent()) {
            Optional<JsonNode> work = ApiJson.get(fetcher, name(),
                    baseUrl + "/works/" + ApiJson.encodeDoi(query.doi().get()));
            if (work.isPresent()) {
                return Optional.of(toMetadata(work.get().path("message")));
            }
            // Not a Crossref DOI (e.g. registered elsewhere): fall through to a title search.
        }
        Optional<JsonNode> search = ApiJson.get(fetcher, name(), baseUrl + "/works?query.bibliographic="
                + ApiJson.encode(query.title()) + "&rows=5&select=" + FIELDS);
        if (search.isEmpty()) {
            return Optional.empty();
        }
        PaperMetadata best = null;
        double bestScore = 0;
        int bestPreference = -1;
        for (JsonNode item : search.get().path("message").path("items")) {
            PaperMetadata candidate = toMetadata(item);
            double score = candidate.title().map(title -> Titles.similarity(title, query.title())).orElse(0.0);
            if (score < MetadataSource.ACCEPT_THRESHOLD) {
                continue;
            }
            int preference = preference(candidate, item, query);
            if (preference > bestPreference || preference == bestPreference && score > bestScore) {
                best = candidate;
                bestScore = score;
                bestPreference = preference;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * How much a hit whose title matches is preferred: 2 if it is in the journal the alert names, 1 if
     * it is a published version (not a preprint), 0 for a preprint of a paper whose journal is unknown
     * or different.
     */
    private static int preference(PaperMetadata hit, JsonNode item, PaperQuery query) {
        boolean sameJournal = query.venue().isPresent() && hit.venue()
                .map(venue -> Titles.compact(venue).equals(Titles.compact(query.venue().get()))).orElse(false);
        if (sameJournal) {
            return 2;
        }
        return PREPRINT.equals(item.path("type").asText("")) ? 0 : 1;
    }

    private static PaperMetadata toMetadata(JsonNode work) {
        List<String> authors = new ArrayList<>();
        for (JsonNode author : work.path("author")) {
            String given = ApiJson.clean(author.path("given").asText(""));
            String family = ApiJson.clean(author.path("family").asText(""));
            String name = (given + " " + family).trim();
            if (name.isEmpty()) {
                name = ApiJson.clean(author.path("name").asText(""));
            }
            if (!name.isEmpty()) {
                authors.add(name);
            }
        }
        List<String> pdfUrls = new ArrayList<>();
        for (JsonNode link : work.path("link")) {
            String url = link.path("URL").asText("");
            if (!url.isEmpty() && link.path("content-type").asText("").toLowerCase().contains("pdf")) {
                pdfUrls.add(url);
            }
        }
        int year = work.path("issued").path("date-parts").path(0).path(0).asInt(0);
        return new PaperMetadata(
                ApiJson.text(work.path("DOI")),
                ApiJson.text(work.path("title").path(0)),
                authors,
                ApiJson.text(work.path("container-title").path(0)),
                year > 0 ? Optional.of(year) : Optional.empty(),
                ApiJson.text(work.path("abstract")).flatMap(ApiJson::abstractText),
                ApiJson.text(work.path("URL")),
                pdfUrls);
    }
}
