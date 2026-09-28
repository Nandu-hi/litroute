package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Scripted stand-in for the network: the first route whose fragment appears in the requested URL
 * answers; unrouted URLs get 404. Every request is recorded so tests can assert what was (and was
 * never) contacted.
 */
final class FakeHttp implements HttpFetcher {

    static final byte[] PDF_BYTES = "%PDF-1.4\n% fake pdf used by tests\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);

    interface Reply {
        HttpReply to(URI uri) throws IOException;
    }

    private record Route(String urlContains, Reply reply) {
    }

    private final List<Route> routes = new ArrayList<>();
    final List<String> requests = new ArrayList<>();

    FakeHttp on(String urlContains, Reply reply) {
        routes.add(new Route(urlContains, reply));
        return this;
    }

    @Override
    public HttpReply get(URI uri, long maxBytes) throws IOException {
        requests.add(uri.toString());
        for (Route route : routes) {
            if (uri.toString().contains(route.urlContains())) {
                return route.reply().to(uri);
            }
        }
        return new HttpReply(404, "text/plain", new byte[0], uri, false);
    }

    long count(String urlFragment) {
        return requests.stream().filter(url -> url.contains(urlFragment)).count();
    }

    boolean requested(String urlFragment) {
        return count(urlFragment) > 0;
    }

    // ---- canned replies

    static Reply json(String body) {
        return uri -> new HttpReply(200, "application/json", body.getBytes(StandardCharsets.UTF_8), uri, false);
    }

    static Reply html(String body) {
        return uri -> new HttpReply(200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), uri, false);
    }

    static Reply pdf() {
        return uri -> new HttpReply(200, "application/pdf", PDF_BYTES, uri, false);
    }

    static Reply status(int code) {
        return uri -> new HttpReply(code, "text/plain", new byte[0], uri, false);
    }

    static Reply tooLarge() {
        return uri -> new HttpReply(200, "application/pdf", PDF_BYTES, uri, true);
    }

    static Reply offline() {
        return uri -> {
            throw new IOException("simulated: network unreachable");
        };
    }
}
