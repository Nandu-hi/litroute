package com.litroute.ingestion.enrich;

import java.net.URI;
import java.util.Optional;

/**
 * One HTTP response, as an {@code HttpFetcher} or {@code OllamaClient.Poster} returns it.
 *
 * @param status      the HTTP status code
 * @param contentType the Content-Type header, or "" if absent
 * @param body        at most the requested number of bytes
 * @param finalUri    the URI after redirects
 * @param truncated   true if the body was longer than the limit and was cut off
 * @param retryAfter  the Retry-After header, if the server sent one (with a 429 or 503)
 */
public record HttpReply(int status, String contentType, byte[] body, URI finalUri, boolean truncated,
        Optional<String> retryAfter) {

    /**
     * A reply without a Retry-After header.
     *
     * @param status      the HTTP status code
     * @param contentType the Content-Type header, or ""
     * @param body        the body read
     * @param finalUri    the URI after redirects
     * @param truncated   whether the body was cut off
     */
    public HttpReply(int status, String contentType, byte[] body, URI finalUri, boolean truncated) {
        this(status, contentType, body, finalUri, truncated, Optional.empty());
    }

    /** {@return true for a 2xx status} */
    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
