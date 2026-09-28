package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.net.URI;

/**
 * The only door to the network for lookups and PDF downloads. Everything else in M2/M3 talks to this
 * interface, so tests substitute canned responses and never touch the internet.
 */
public interface HttpFetcher {

    /**
     * GETs the URI, following redirects, and reads at most {@code maxBytes} of the body.
     *
     * @throws IOException if the host can't be reached or the request times out. An HTTP error status
     *         (404, 403, 500...) is NOT an exception: it comes back as a reply for the caller to judge.
     */
    HttpReply get(URI uri, long maxBytes) throws IOException;
}
