package com.litroute.ingestion.enrich;

import java.net.URI;

/**
 * @param contentType the Content-Type header, or "" if absent
 * @param body        at most the requested number of bytes
 * @param finalUri    the URI after redirects
 * @param truncated   true if the body was longer than the limit and was cut off
 */
public record HttpReply(int status, String contentType, byte[] body, URI finalUri, boolean truncated) {

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
