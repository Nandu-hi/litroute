package com.litroute.ingestion.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** Shared plumbing for the JSON APIs: fetch + parse, and URL-encoding. */
final class ApiJson {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_JSON_BYTES = 4L * 1024 * 1024;
    private static final Pattern ABSTRACT_HEADING = Pattern.compile("(?i)^abstract\\b[\\s.:]*");

    private ApiJson() {
    }

    /**
     * @return the parsed body; empty for 404 and other client errors ("no such record")
     * @throws IOException for network failure, HTTP 429/5xx (the API is down or throttling us) or a body that isn't JSON
     */
    static Optional<JsonNode> get(HttpFetcher fetcher, String sourceName, String url) throws IOException {
        HttpReply reply = fetcher.get(URI.create(url), MAX_JSON_BYTES);
        if (reply.status() == 429 || reply.status() >= 500) {
            throw new IOException(sourceName + " unavailable: HTTP " + reply.status());
        }
        if (!reply.ok()) {
            return Optional.empty();
        }
        return Optional.of(JSON.readTree(reply.body()));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Percent-encodes a DOI for use as a URL path, keeping its "/" separators. */
    static String encodeDoi(String doi) {
        return encode(doi).replace("%2F", "/").replace("+", "%20");
    }

    static Optional<String> text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        String value = clean(node.asText(""));
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** An abstract without the "Abstract" heading both APIs often keep at its start; empty if nothing is left. */
    static Optional<String> abstractText(String text) {
        String body = ABSTRACT_HEADING.matcher(text.strip()).replaceFirst("");
        return body.isBlank() ? Optional.empty() : Optional.of(body);
    }

    /**
     * Crossref/OpenAlex text is often HTML: "Engineering &amp; Structural Dynamics", "CO&lt;sub&gt;2&lt;/sub&gt;",
     * "&lt;i&gt;in situ&lt;/i&gt;". Returns what a reader would see.
     */
    static String clean(String value) {
        return value.indexOf('&') < 0 && value.indexOf('<') < 0 ? value.trim() : Jsoup.parse(value).text().trim();
    }
}
