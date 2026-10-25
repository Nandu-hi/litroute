package com.litroute.ingestion.enrich;

import java.net.URI;
import java.util.Optional;

/**
 * One HTTP response, as an {@code HttpFetcher} or {@code JsonPoster} returns it.
 *
 * @param contentType the Content-Type header, or "" if absent
 * @param body        at most the requested number of bytes
 * @param finalUri    the URI after redirects
 * @param truncated   true if the body was longer than the limit and was cut off
 * @param retryAfter  the Retry-After header, if the server sent one (with a 429 or 503)
 */
public record HttpReply(int status, String contentType, byte[] body, URI finalUri, boolean truncated,
        Optional<String> retryAfter) {

    /**
     * Input: status, content type, body, final URI, truncated flag.
     * Output: a reply without a Retry-After header.
     * Used by: JdkHttpFetcher, JdkJsonPoster (M6 AI), recorded-reply test fetchers.
     * Example: {@code new HttpReply(200, "application/json", bytes, uri, false).retryAfter()} -> empty
     */
    public HttpReply(int status, String contentType, byte[] body, URI finalUri, boolean truncated) {
        this(status, contentType, body, finalUri, truncated, Optional.empty());
    }

    /**
     * Input: none.
     * Output: true for a 2xx status.
     * Used by: ApiJson, ArticlePageReader, PdfDownloader (M3); ApiCalls (M6 AI).
     * Example: 200 -> true; 302 -> false; 429 -> false
     */
    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
