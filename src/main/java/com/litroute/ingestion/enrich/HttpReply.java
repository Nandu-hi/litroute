package com.litroute.ingestion.enrich;

import java.net.URI;
import java.util.Optional;

/**
 * @param contentType the Content-Type header, or "" if absent
 * @param body        at most the requested number of bytes
 * @param finalUri    the URI after redirects
 * @param truncated   true if the body was longer than the limit and was cut off
 * @param retryAfter  the Retry-After header, if the server sent one (with a 429 or 503)
 */
public record HttpReply(int status, String contentType, byte[] body, URI finalUri, boolean truncated,
        Optional<String> retryAfter) {

    /** A reply without a Retry-After header. */
    public HttpReply(int status, String contentType, byte[] body, URI finalUri, boolean truncated) {
        this(status, contentType, body, finalUri, truncated, Optional.empty());
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
