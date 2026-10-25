package com.litroute.ingestion.enrich;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps requests to the metadata APIs within their rate limits. Crossref's public pool allows 5
 * requests a second, one at a time ({@code x-rate-limit-limit: 5}, {@code x-concurrency-limit: 1});
 * enrichment asks one request after another, but fast replies can still exceed 5 a second, and
 * Crossref then answers HTTP 429. So each paced host gets at least its interval between the starts of
 * two requests, and a 429 that asks for a short wait ({@code Retry-After} up to
 * {@value #MAX_RETRY_WAIT_SECONDS} s, or none stated) is waited out and retried once. A longer wait -
 * OpenAlex's daily budget says hours - is not: that reply is returned for the caller to treat as an outage.
 * Other hosts (publisher pages, PDFs) pass straight through.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Input:  new PacedFetcher(fetcher, Map.of("api.crossref.org", Duration.ofMillis(250))), ten Crossref lookups
 * Output: the same replies, the lookups started at least 250 ms apart (at most 4 a second)
 * }</pre>
 */
public final class PacedFetcher implements HttpFetcher {

    static final long MAX_RETRY_WAIT_SECONDS = 5;

    /** Waits; replaced in tests so they don't sleep. */
    @FunctionalInterface
    interface Sleeper {
        /**
         * Input: how long to wait.
         * Output: none, after waiting.
         * Used by: {@link PacedFetcher#pause}.
         * Example: {@code sleep(Duration.ofMillis(250))} -> returns after 250 ms (tests: immediately)
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpFetcher fetcher;
    private final Map<String, Duration> intervals;
    private final Sleeper sleeper;
    private final Map<String, Long> lastStart = new HashMap<>();

    /**
     * Input: the fetcher that does the requests, and the least time between two requests per paced host.
     * Output: a paced fetcher that really sleeps.
     * Used by: OnlinePaperEnricher.create (M3).
     * Example: {@code new PacedFetcher(jdk, Map.of("api.crossref.org", Duration.ofMillis(250)))}
     */
    public PacedFetcher(HttpFetcher fetcher, Map<String, Duration> intervals) {
        this(fetcher, intervals, duration -> Thread.sleep(duration.toMillis()));
    }

    /**
     * Input: as above, plus the Sleeper to wait with.
     * Output: a paced fetcher.
     * Used by: the public constructor; PacedFetcherTest with a recording sleeper.
     * Example: {@code new PacedFetcher(fake, intervals, slept::add)}
     */
    PacedFetcher(HttpFetcher fetcher, Map<String, Duration> intervals, Sleeper sleeper) {
        this.fetcher = fetcher;
        this.intervals = Map.copyOf(intervals);
        this.sleeper = sleeper;
    }

    /**
     * Input: a URI and the most body bytes to read.
     * Output: the reply; for a paced host, after its interval, with one retry on a 429 asking &lt;= 5 s.
     * Used by: ApiJson, ArticlePageReader, PdfDownloader (M3).
     * Example: two Crossref calls 50 ms apart with a 250 ms interval -> second waits ~200 ms; publisher URL -> no wait
     */
    @Override
    public HttpReply get(URI uri, long maxBytes) throws IOException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        Duration interval = intervals.get(host);
        if (interval == null) {
            return fetcher.get(uri, maxBytes);
        }
        HttpReply reply = paced(host, interval, uri, maxBytes);
        if (reply.status() == 429) {
            Duration wait = retryAfter(reply);
            if (wait.getSeconds() <= MAX_RETRY_WAIT_SECONDS) {
                pause(wait);
                reply = paced(host, interval, uri, maxBytes);
            }
        }
        return reply;
    }

    /**
     * Input: the host, its interval, the URI and the byte cap.
     * Output: the reply of one request, started no sooner than the interval after the host's previous one.
     * Used by: {@link #get}.
     * Example: previous start 100 ms ago, interval 250 ms -> waits 150 ms, then fetches
     */
    private synchronized HttpReply paced(String host, Duration interval, URI uri, long maxBytes) throws IOException {
        Long previous = lastStart.get(host);
        if (previous != null) {
            long due = previous + interval.toNanos() - System.nanoTime();
            if (due > 0) {
                pause(Duration.ofNanos(due));
            }
        }
        lastStart.put(host, System.nanoTime());
        return fetcher.get(uri, maxBytes);
    }

    /**
     * Input: a 429 reply.
     * Output: its Retry-After in seconds; 1 s when none; more than the max (no retry) for an HTTP-date value.
     * Used by: {@link #get}.
     * Example: "3" -> 3 s; absent -> 1 s; "Wed, 21 Oct 2026 07:28:00 GMT" -> 6 s (not retried)
     */
    private static Duration retryAfter(HttpReply reply) {
        try {
            return reply.retryAfter().map(value -> Duration.ofSeconds(Long.parseLong(value.strip())))
                    .orElse(Duration.ofSeconds(1));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(MAX_RETRY_WAIT_SECONDS + 1); // an HTTP date: a long wait, not retried
        }
    }

    /**
     * Input: how long to wait.
     * Output: none; IOException (interrupt flag restored) if interrupted.
     * Used by: {@link #get}, {@link #paced}.
     * Example: {@code pause(Duration.ofSeconds(1))} -> sleeps 1 s
     */
    private void pause(Duration duration) throws IOException {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting out an API rate limit", e);
        }
    }
}
