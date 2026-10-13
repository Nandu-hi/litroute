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

/**
 * Shared plumbing for the JSON APIs (Crossref, OpenAlex): fetch + parse, URL-encoding, and turning the APIs'
 * HTML-flavoured text fields into plain text.
 *
 * <p>Example (as {@code CrossrefSource} uses it):
 *
 * <pre>{@code
 * Input:  ApiJson.get(fetcher, "Crossref", "https://api.crossref.org/works/" + ApiJson.encodeDoi("10.1016/j.oceaneng.2024.117"))
 * Output: Optional[{"status":"ok","message":{"title":["Wave loads on ..."], ...}}]
 *
 * Input:  ApiJson.text(message.path("container-title").path(0))   // JSON "Earthquake Engineering &amp; Structural Dynamics"
 * Output: Optional[Earthquake Engineering & Structural Dynamics]
 * }</pre>
 */
final class ApiJson {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_JSON_BYTES = 4L * 1024 * 1024;
    private static final Pattern ABSTRACT_HEADING = Pattern.compile("(?i)^abstract\\b[\\s.:]*");

    /** Not instantiable; all behaviour is in static methods. */
    private ApiJson() {
    }

    /**
     * Fetches a JSON API URL (body capped at 4 MB) and parses the reply. A 404 or other client error means
     * "no such record" and gives empty; a 429 or 5xx means the API is down or throttling us and is thrown, so the
     * caller can tell an outage apart from a real miss.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  get(fetcher, "OpenAlex", "https://api.openalex.org/works/doi:10.1016/j.oceaneng.2024.117")
     *         -> HTTP 200 {"id":"https://openalex.org/W123","title":"Wave loads on ..."}
     * Output: Optional[{"id":"https://openalex.org/W123","title":"Wave loads on ..."}]
     *
     * Input:  same URL -> HTTP 404
     * Output: Optional.empty
     *
     * Input:  get(fetcher, "Crossref", url) -> HTTP 503
     * Output: throws IOException("Crossref unavailable: HTTP 503")
     * }</pre>
     *
     * @param fetcher the HTTP client to fetch with
     * @param sourceName the API's name, used only in the exception message
     * @param url the full request URL, already encoded
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

    /**
     * Encodes a value for use as a URL query parameter (UTF-8, spaces as {@code +}).
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "Wave loads & mooring: a review"
     * Output: "Wave+loads+%26+mooring%3A+a+review"
     * }</pre>
     *
     * @param value the raw text, e.g. a paper title
     * @return the query-safe encoding of {@code value}
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Percent-encodes a DOI for use as a URL path, keeping its "/" separators. Spaces become {@code %20}, since
     * {@code +} would mean a literal plus in a path.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "10.1016/j.oceaneng.2024.117"
     * Output: "10.1016/j.oceaneng.2024.117"
     *
     * Input:  "10.1002/(SICI)1097-0258"
     * Output: "10.1002/%28SICI%291097-0258"
     * }</pre>
     *
     * @param doi a bare DOI, without the {@code https://doi.org/} prefix
     * @return the DOI encoded for a URL path
     */
    static String encodeDoi(String doi) {
        return encode(doi).replace("%2F", "/").replace("+", "%20");
    }

    /**
     * Reads a JSON value as cleaned plain text (see {@link #clean}). A missing, null or blank value, or an object or
     * array, gives empty.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  JSON "  Ocean Engineering "              Output: Optional[Ocean Engineering]
     * Input:  JSON "CO<sub>2</sub> capture"           Output: Optional[CO2 capture]
     * Input:  JSON 2024                               Output: Optional[2024]
     * Input:  JSON null, a missing field, or ""       Output: Optional.empty
     * }</pre>
     *
     * @param node a JSON node, possibly null or missing
     * @return the node's text with HTML removed, or empty if there is none
     */
    static Optional<String> text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        String value = clean(node.asText(""));
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /**
     * An abstract without the "Abstract" heading both APIs often keep at its start; empty if nothing is left. The
     * heading is matched case-insensitively as a whole word, so a sentence starting with "Abstracting" is kept.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "Abstract: We study wave loads on floating turbines."   Output: Optional[We study wave loads on floating turbines.]
     * Input:  "ABSTRACT. Mooring lines fail ..."                      Output: Optional[Mooring lines fail ...]
     * Input:  "Abstracting features from sonar data ..."              Output: Optional[Abstracting features from sonar data ...]
     * Input:  "Abstract"                                              Output: Optional.empty
     * }</pre>
     *
     * @param text the abstract as the API gave it, already cleaned of HTML
     * @return the abstract without its heading, or empty if it was only a heading
     */
    static Optional<String> abstractText(String text) {
        String body = ABSTRACT_HEADING.matcher(text.strip()).replaceFirst("");
        return body.isBlank() ? Optional.empty() : Optional.of(body);
    }

    /**
     * Crossref/OpenAlex text is often HTML: "Engineering &amp; Structural Dynamics", "CO&lt;sub&gt;2&lt;/sub&gt;",
     * "&lt;i&gt;in situ&lt;/i&gt;". Returns what a reader would see. Text with no {@code &} or {@code <} is only
     * trimmed, skipping the HTML parse.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "Engineering &amp; Structural Dynamics"   Output: "Engineering & Structural Dynamics"
     * Input:  "<i>In situ</i> measurement of CO<sub>2</sub>"   Output: "In situ measurement of CO2"
     * Input:  "  Ocean Engineering  "                   Output: "Ocean Engineering"
     * }</pre>
     *
     * @param value raw text from an API field
     * @return the visible text, trimmed
     */
    static String clean(String value) {
        return value.indexOf('&') < 0 && value.indexOf('<') < 0 ? value.trim() : Jsoup.parse(value).text().trim();
    }
}
