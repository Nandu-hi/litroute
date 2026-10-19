package com.litroute.scoring.ai;

import com.litroute.ingestion.enrich.HttpReply;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * The AI matcher's only door to the network: POSTs a JSON body. Tests substitute a replay of a reply the
 * API really gave, so they run offline.
 */
public interface JsonPoster {

    /**
     * POSTs {@code body} as {@code application/json} with the given extra headers, and reads at most
     * {@code maxBytes} of the reply.
     *
     * @throws IOException if the host can't be reached or the request times out. An HTTP error status
     *         is NOT an exception: it comes back as a reply for the caller to judge.
     */
    HttpReply post(URI uri, Map<String, String> headers, byte[] body, long maxBytes) throws IOException;
}
