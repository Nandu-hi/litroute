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
     * Input: the URI, extra headers (e.g. Authorization), the JSON body and the most reply bytes to read.
     * Output: the reply (an HTTP error status is a reply, not an exception); IOException if unreachable or timed out.
     * Used by: ApiCalls.post, for every Groq request.
     * Example: POST https://api.groq.com/openai/v1/chat/completions -> HttpReply(200, "application/json", {...choices...})
     */
    HttpReply post(URI uri, Map<String, String> headers, byte[] body, long maxBytes) throws IOException;
}
