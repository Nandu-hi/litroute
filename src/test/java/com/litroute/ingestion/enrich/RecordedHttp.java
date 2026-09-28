package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays the HTTP responses Crossref, OpenAlex and the publishers really gave for the papers in the
 * six sample mails. The recording was made by running the live pipeline over those mails once and
 * saving every reply under src/test/resources/http/ (see the README there for what was captured and
 * what was shortened), so tests exercise real API payloads - real field names, real absences, real
 * 403s from publishers that block robots - while staying offline and deterministic.
 *
 * A URL with no recording is a failure, not a miss: it means the code asked for something the
 * recording never saw, which offline tests must never paper over.
 */
public final class RecordedHttp implements HttpFetcher {

    private record Recorded(int status, String contentType, byte[] body) {
    }

    private static final Map<String, Recorded> RECORDINGS = load();

    /** Every URL requested, in order, so a test can assert what was - and was never - contacted. */
    final List<String> requests = new ArrayList<>();

    private final List<String> offlineFragments = new ArrayList<>();

    /** Makes any URL containing this fragment fail as an unreachable host would. */
    RecordedHttp offlineFor(String urlFragment) {
        offlineFragments.add(urlFragment);
        return this;
    }

    @Override
    public HttpReply get(URI uri, long maxBytes) throws IOException {
        String url = uri.toString();
        requests.add(url);
        for (String fragment : offlineFragments) {
            if (url.contains(fragment)) {
                throw new IOException("simulated: network unreachable");
            }
        }
        Recorded recorded = RECORDINGS.get(url);
        if (recorded == null) {
            throw new AssertionError("No recorded HTTP response for " + url
                    + "\nRe-record the fixtures if the pipeline now asks for a different URL.");
        }
        byte[] body = recorded.body();
        boolean truncated = false;
        if (body.length > maxBytes) {
            body = java.util.Arrays.copyOf(body, (int) maxBytes);
            truncated = true;
        }
        return new HttpReply(recorded.status(), recorded.contentType(), body, uri, truncated);
    }

    long count(String urlFragment) {
        return requests.stream().filter(url -> url.contains(urlFragment)).count();
    }

    boolean requested(String urlFragment) {
        return count(urlFragment) > 0;
    }

    private static Map<String, Recorded> load() {
        Map<String, Recorded> recordings = new LinkedHashMap<>();
        for (String line : read("index.tsv").split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] row = line.split("\t", 6);
            recordings.put(row[5], new Recorded(Integer.parseInt(row[1]), row[2], readBytes(row[0])));
        }
        return Map.copyOf(recordings);
    }

    private static String read(String name) {
        return new String(readBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(String name) {
        try (InputStream in = RecordedHttp.class.getResourceAsStream("/http/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing recorded response file: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
