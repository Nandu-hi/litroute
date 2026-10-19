package com.litroute.scoring.ai;

import com.litroute.ingestion.enrich.HttpReply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * {@link JsonPoster} on the JDK's built-in HttpClient, like {@code JdkHttpFetcher}: https only, a short
 * connect timeout so an offline machine fails fast, and a body size cap. The request timeout is long,
 * since a language model reading every profile and paper of a batch takes a while to answer.
 */
public final class JdkJsonPoster implements JsonPoster {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public HttpReply post(URI uri, Map<String, String> headers, byte[] body, long maxBytes) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Refusing non-https URI: " + uri);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
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
                return new HttpReply(response.statusCode(), response.headers().firstValue("content-type").orElse(""),
                        out.toByteArray(), response.uri(), truncated);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while posting to " + uri.getHost(), e);
        }
    }
}
