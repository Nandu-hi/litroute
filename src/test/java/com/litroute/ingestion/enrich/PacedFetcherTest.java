package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

/**
 * Pacing and 429 handling, against scripted status codes: Crossref's real limits (5 a second, one at a
 * time) and the two kinds of 429 seen in October 2026 - Crossref's momentary one and OpenAlex's
 * spent daily budget ({@code Retry-After: 21391}).
 */
public class PacedFetcherTest {

    private static final URI CROSSREF = URI.create("https://api.crossref.org/works/10.1061/nhrefo.nheng-2753");
    private static final URI PUBLISHER = URI.create("https://ascelibrary.org/doi/10.1061/NHREFO.NHENG-2753");

    /** Answers with the scripted replies in order, then 200s, and remembers what was asked. */
    private static final class Scripted implements HttpFetcher {
        final Deque<HttpReply> replies = new ArrayDeque<>();
        final List<URI> asked = new ArrayList<>();

        Scripted then(int status, String retryAfter) {
            replies.add(new HttpReply(status, "application/json", new byte[0], CROSSREF, false,
                    Optional.ofNullable(retryAfter)));
            return this;
        }

        @Override
        public HttpReply get(URI uri, long maxBytes) {
            asked.add(uri);
            return replies.isEmpty() ? new HttpReply(200, "application/json", new byte[0], uri, false) : replies.poll();
        }
    }

    private final List<Duration> slept = new ArrayList<>();

    private PacedFetcher paced(Scripted fetcher) {
        return new PacedFetcher(fetcher, Map.of("api.crossref.org", Duration.ofMillis(250)), slept::add);
    }

    @Test
    public void requestsToAPacedHostAreSpacedByItsInterval() throws Exception {
        PacedFetcher fetcher = paced(new Scripted());

        fetcher.get(CROSSREF, 1000);
        fetcher.get(CROSSREF, 1000);

        assertEquals("the first goes at once, the second waits", 1, slept.size());
        assertTrue(slept.get(0).compareTo(Duration.ofMillis(250)) <= 0 && !slept.get(0).isNegative());
    }

    @Test
    public void otherHostsAreNotPaced() throws Exception {
        PacedFetcher fetcher = paced(new Scripted());

        fetcher.get(PUBLISHER, 1000);
        fetcher.get(PUBLISHER, 1000);

        assertTrue(slept.isEmpty());
    }

    /** Crossref's momentary 429: wait what it asks (or a second) and try once more. */
    @Test
    public void aShortRateLimitIsWaitedOutAndRetriedOnce() throws Exception {
        Scripted scripted = new Scripted().then(429, "2");

        HttpReply reply = paced(scripted).get(CROSSREF, 1000);

        assertEquals(200, reply.status());
        assertEquals(2, scripted.asked.size());
        assertTrue(slept.contains(Duration.ofSeconds(2)));
    }

    @Test
    public void aRateLimitWithoutRetryAfterWaitsASecond() throws Exception {
        Scripted scripted = new Scripted().then(429, null);

        assertEquals(200, paced(scripted).get(CROSSREF, 1000).status());
        assertTrue(slept.contains(Duration.ofSeconds(1)));
    }

    /** OpenAlex's spent daily budget asks for hours: no waiting, the 429 goes to the caller as an outage. */
    @Test
    public void aLongRateLimitIsReturnedWithoutWaiting() throws Exception {
        Scripted scripted = new Scripted().then(429, "21391");

        HttpReply reply = paced(scripted).get(CROSSREF, 1000);

        assertEquals(429, reply.status());
        assertEquals(1, scripted.asked.size());
        assertTrue(slept.isEmpty());
    }

    @Test
    public void aSecond429IsReturnedRatherThanRetriedForever() throws Exception {
        Scripted scripted = new Scripted().then(429, "1").then(429, "1");

        assertEquals(429, paced(scripted).get(CROSSREF, 1000).status());
        assertEquals(2, scripted.asked.size());
    }
}
