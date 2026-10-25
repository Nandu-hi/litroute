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

    /**
     * Input: the User-Agent to send (APIs ask for a contact address in it).
     * Output: a fetcher with 8 s connect and 30 s request timeouts.
     * Used by: OnlinePaperEnricher.create (M3), wrapped in a PacedFetcher.
     * Example: {@code new JdkHttpFetcher("LitRoute/1.0 (mailto:x@y.org)")}
     */
    public JdkHttpFetcher(String userAgent) {
        this.userAgent = userAgent;
    }

    /**
     * Input: an http(s) URI and the most body bytes to read.
     * Output: the reply, body cut at maxBytes (truncated = true); IOException on non-http(s), network failure or interrupt.
     * Used by: PacedFetcher, and through it every M3 lookup and download.
     * Example: {@code get(pdfUrl, 100)} for a 5 MB PDF -> reply with 100 bytes, truncated = true
     */
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
                // Stop reading at the cap rather than downloading a huge body.
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
                        out.toByteArray(), response.uri(), truncated, response.headers().firstValue("retry-after"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + uri, e);
        }
    }
}
