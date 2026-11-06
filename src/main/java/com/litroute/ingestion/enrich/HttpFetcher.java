package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.net.URI;

/**
 * The only door to the network for lookups and PDF downloads. Everything else in M2/M3 talks to this
 * interface, so tests substitute canned responses and never touch the internet.
 */
public interface HttpFetcher {

    /**
     * GETs the URI, following redirects. An HTTP error status (404, 500...) is returned as a reply, not thrown.
     *
     * @param uri      the URI
     * @param maxBytes the most body bytes to read
     * @return the reply
     * @throws IOException if the host can't be reached or the request times out
     */
    HttpReply get(URI uri, long maxBytes) throws IOException;
}
