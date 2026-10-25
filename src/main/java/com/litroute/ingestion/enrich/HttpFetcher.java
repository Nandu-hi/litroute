package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.net.URI;

/**
 * The only door to the network for lookups and PDF downloads. Everything else in M2/M3 talks to this
 * interface, so tests substitute canned responses and never touch the internet.
 */
public interface HttpFetcher {

    /**
     * GETs the URI, following redirects. An HTTP error status (404, 500...) is NOT an exception: it comes back as a reply.
     * Input: the URI and the most body bytes to read.
     * Output: the reply; IOException if the host can't be reached or the request times out.
     * Used by: ApiJson (Crossref/OpenAlex), ArticlePageReader, PdfDownloader (M3); PacedFetcher wraps another.
     * Example: {@code get(https://api.crossref.org/works/10.1/x, 2_000_000)} -> HttpReply(200, "application/json", ...)
     */
    HttpReply get(URI uri, long maxBytes) throws IOException;
}
