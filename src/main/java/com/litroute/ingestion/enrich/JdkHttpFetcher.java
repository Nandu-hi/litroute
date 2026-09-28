package com.litroute.ingestion.enrich;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * {@link HttpFetcher} on the JDK's built-in HttpClient. Only http/https, redirects followed (never
 * from https down to http), short connect timeout so an offline machine fails fast, and a body size cap.
 */
public final class JdkHttpFetcher implements HttpFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final String userAgent;

    public JdkHttpFetcher(String userAgent) {
        this.userAgent = userAgent;
    }

    @Override
    public HttpReply get(URI uri, long maxBytes) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("Refusing non-http(s) URI: " + uri);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, application/pdf, text/html;q=0.9, */*;q=0.5")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long total = 0;
                boolean truncated = false;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (total + read > maxBytes) {
                        out.write(buffer, 0, (int) (maxBytes - total));
                        truncated = true;
                        break;
                    }
                    out.write(buffer, 0, read);
                    total += read;
                }
                return new HttpReply(response.statusCode(),
                        response.headers().firstValue("content-type").orElse(""),
                        out.toByteArray(), response.uri(), truncated);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + uri, e);
        }
    }
}
