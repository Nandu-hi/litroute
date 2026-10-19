package com.litroute.scoring.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.ingestion.enrich.HttpReply;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the language model APIs share: a POST that is retried while the API is throttling (HTTP 429) or
 * overloaded (5xx), and turned into an exception when it still fails, so a client only ever reads a
 * successful reply.
 */
final class ApiCalls {

    private static final Logger LOG = Logger.getLogger(ApiCalls.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Waits out a pause; tests record the pause instead of sleeping. */
    interface Pauser {
        void pause(Duration pause) throws IOException;
    }

    /**
     * How a throttled or overloaded request is retried.
     *
     * @param delays      pauses before each retry; their number is the number of retries
     * @param longestWait the longest wait the API may ask for and still be waited out; one that asks for
     *                    longer (an exhausted daily quota asks for hours) is reported at once
     * @param pauser      what waits
     */
    record RetryPolicy(List<Duration> delays, Duration longestWait, Pauser pauser) {

        RetryPolicy {
            delays = List.copyOf(delays);
        }

        /** Retries after 10 s, 30 s and 60 s, or after the wait the API asks for, up to 2 minutes. */
        static final RetryPolicy DEFAULT = new RetryPolicy(List.of(Duration.ofSeconds(10), Duration.ofSeconds(30),
                Duration.ofSeconds(60)), Duration.ofMinutes(2), ApiCalls::sleep);
    }
    private static final Pattern TRY_AGAIN_IN = Pattern.compile(
            "(?i)try again in (?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+(?:\\.\\d+)?)s)?(?:(\\d+(?:\\.\\d+)?)ms)?");

    private ApiCalls() {
    }

    /**
     * POSTs the request, and posts it again after each of the policy's delays while the reply is a 429 or
     * 5xx - or after the wait the API asks for, if longer. When the API asks for a longer wait than the
     * policy's {@code longestWait}, the failure is reported at once.
     *
     * @param askedWait how long a failed reply asks the caller to wait, if it says
     * @param api       the API's name, for messages
     * @return the reply, which is a 2xx
     * @throws IOException if the API can't be reached, still fails after the retries, refuses the request
     *         (another 4xx), or replies with more than {@code maxBytes}
     */
    static HttpReply post(JsonPoster poster, URI uri, Map<String, String> headers, byte[] body, long maxBytes,
            RetryPolicy policy, Function<HttpReply, Optional<Duration>> askedWait, String api) throws IOException {
        List<Duration> retryDelays = policy.delays();
        HttpReply reply = poster.post(uri, headers, body, maxBytes);
        for (int retry = 0; isTransient(reply) && retry < retryDelays.size(); retry++) {
            Optional<Duration> asked = askedWait.apply(reply);
            if (asked.isPresent() && asked.get().compareTo(policy.longestWait()) > 0) {
                throw new IOException(api + " unavailable: HTTP " + reply.status() + errorMessage(reply) + " (" + api
                        + " asks to wait " + asked.get().toSeconds() + " s, so it is not retried)");
            }
            Duration ours = retryDelays.get(retry);
            Duration pause = asked.filter(wait -> wait.compareTo(ours) > 0).orElse(ours);
            LOG.warning(api + " unavailable (HTTP " + reply.status() + errorMessage(reply) + "); trying again in "
                    + pause.toMillis() / 1000.0 + " s");
            policy.pauser().pause(pause);
            reply = poster.post(uri, headers, body, maxBytes);
        }
        if (isTransient(reply)) {
            throw new IOException(api + " unavailable: HTTP " + reply.status() + errorMessage(reply));
        }
        if (!reply.ok()) {
            throw new IOException(api + " refused the request: HTTP " + reply.status() + errorMessage(reply));
        }
        if (reply.truncated()) {
            throw new IOException(api + "'s reply is larger than " + maxBytes + " bytes");
        }
        return reply;
    }

    /**
     * The wait an error message asks for, as in "Please try again in 7.66s", "in 2m59.5s" or "in 1h2m3s";
     * empty if it names none.
     */
    static Optional<Duration> tryAgainIn(String message) {
        Matcher wait = TRY_AGAIN_IN.matcher(message);
        while (wait.find()) {
            if (wait.group(1) == null && wait.group(2) == null && wait.group(3) == null && wait.group(4) == null) {
                continue;
            }
            long hours = wait.group(1) == null ? 0 : Long.parseLong(wait.group(1));
            long minutes = wait.group(2) == null ? 0 : Long.parseLong(wait.group(2));
            double seconds = wait.group(3) == null ? 0 : Double.parseDouble(wait.group(3));
            double millis = wait.group(4) == null ? 0 : Double.parseDouble(wait.group(4));
            return Optional.of(Duration.ofHours(hours).plusMinutes(minutes)
                    .plusMillis(Math.round(seconds * 1000 + millis)));
        }
        return Optional.empty();
    }

    /** Throttled or overloaded: worth asking again in a while. */
    private static boolean isTransient(HttpReply reply) {
        return reply.status() == 429 || reply.status() >= 500;
    }

    private static void sleep(Duration pause) throws IOException {
        try {
            Thread.sleep(pause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to ask again", e);
        }
    }

    /** The API's own explanation of an error status ({@code error.message}, as both APIs send it), if any. */
    static String errorMessage(HttpReply reply) {
        try {
            String message = JSON.readTree(reply.body()).path("error").path("message").asText("");
            return message.isBlank() ? "" : ": " + message.strip();
        } catch (IOException e) {
            return "";
        }
    }
}
