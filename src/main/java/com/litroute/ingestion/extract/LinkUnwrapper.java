package com.litroute.ingestion.extract;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Works out what an alert email's link is, using nothing but the link text itself (never the network):
 *
 * <ul>
 *   <li>a redirect wrapper that carries its real target percent-encoded, either as a query value
 *       ({@code scholar_url?url=https%3A%2F%2F...}) or as a path segment
 *       ({@code /CL0/https:%2F%2Fwww.sciencedirect.com%2F...%2F1/...}), is unwrapped -> READABLE;
 *   <li>a plain publisher URL -> READABLE;
 *   <li>a click-tracker whose target can't be decoded (random token) -> OPAQUE, and its URL is dropped
 *       because it identifies nothing and embeds the recipient's tracking ID.
 * </ul>
 *
 * <p>Example:
 *
 * <pre>{@code
 * Input:  "https://scholar.google.com/scholar_url?url=https%3A%2F%2Fwww.nature.com%2Farticles%2Fs41586-024-0001&hl=en"
 * Output: Link[kind=READABLE, url=Optional[https://www.nature.com/articles/s41586-024-0001]]
 *
 * Input:  "https://click.mailchimp.com/ls/click?upn=aB3xQz9"
 * Output: Link[kind=OPAQUE, url=Optional.empty]
 *
 * Input:  "mailto:alerts@example.org"
 * Output: Link[kind=NONE, url=Optional.empty]
 * }</pre>
 */
final class LinkUnwrapper {

    /**
     * The outcome of resolving one href: what kind of link it is and, only when it is {@link LinkKind#READABLE},
     * the final unwrapped URL.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  resolve("https://www.sciencedirect.com/science/article/pii/S0001")
     * Output: Link[kind=READABLE, url=Optional[https://www.sciencedirect.com/science/article/pii/S0001]]
     *
     * Input:  resolve(null)
     * Output: Link.NONE   // Link[kind=NONE, url=Optional.empty]
     * }</pre>
     *
     * @param kind whether the link is readable, an opaque tracker, or not a web link at all
     * @param url the unwrapped target URL; empty unless {@code kind} is {@link LinkKind#READABLE}
     */
    record Link(LinkKind kind, Optional<String> url) {
        static final Link NONE = new Link(LinkKind.NONE, Optional.empty());
    }

    private static final int MAX_UNWRAP_DEPTH = 4;

    private static final Pattern TRACKER_HOST = Pattern.compile(
            "(?i)^(?:click|clicks|email|emails|em|e|links|track|tracking|trk|url|mail|sg|ls|mkt|mktg|elink)\\..+"
                    + "|.*(?:list-manage\\.com|sendgrid\\.net|mandrillapp\\.com|mailchimp|rs6\\.net|constantcontact"
                    + "|exacttarget|eloqua|pardot|hubspotlinks|emltrk|amazonses).*");
    private static final Pattern TRACKER_PATH = Pattern.compile("(?i)/(?:ls/click|e/ctt|track/click|click)(?:[/?]|$)");

    /** Not instantiable; all behaviour is in static methods. */
    private LinkUnwrapper() {
    }

    /**
     * Classifies an href from an alert email, peeling off up to {@code MAX_UNWRAP_DEPTH} (4) layers of redirect
     * wrappers first. Returns {@link Link#NONE} for null or non-http(s) hrefs, an OPAQUE link (with no URL) when
     * the innermost URL still looks like a click-tracker, and otherwise a READABLE link carrying that URL.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "https://scholar.google.com/scholar_url?url=https%3A%2F%2Fwww.nature.com%2Farticles%2Fs41586-024-0001&hl=en"
     * Output: Link[kind=READABLE, url=Optional[https://www.nature.com/articles/s41586-024-0001]]
     *
     * Input:  "https://links.example-publisher.com/e/ctt/xYz123"
     * Output: Link[kind=OPAQUE, url=Optional.empty]
     *
     * Input:  "#top"
     * Output: Link[kind=NONE, url=Optional.empty]
     * }</pre>
     *
     * @param href the raw href attribute value; may be null
     * @return the resolved link, never null
     */
    static Link resolve(String href) {
        if (href == null) {
            return Link.NONE;
        }
        String current = href.trim();
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            Optional<String> inner = embeddedTarget(current);
            if (inner.isEmpty()) {
                break;
            }
            current = inner.get();
        }
        if (!isHttp(current)) {
            return Link.NONE;
        }
        if (looksLikeTracker(current)) {
            return new Link(LinkKind.OPAQUE, Optional.empty());
        }
        return new Link(LinkKind.READABLE, Optional.of(current));
    }

    /**
     * Finds one layer of embedded target inside a wrapper URL: the first query value that decodes to an http(s)
     * URL, or failing that the first path segment that does. The fragment ({@code #...}) is ignored.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "https://scholar.google.com/scholar_url?hl=en&url=https%3A%2F%2Fwww.nature.com%2Farticles%2Fx"
     * Output: Optional[https://www.nature.com/articles/x]
     *
     * Input:  "https://tracking.example.com/CL0/https:%2F%2Fwww.sciencedirect.com%2Fscience%2Farticle%2Fpii%2FS0001/1/abc"
     * Output: Optional[https://www.sciencedirect.com/science/article/pii/S0001]
     *
     * Input:  "https://www.nature.com/articles/x"
     * Output: Optional.empty
     * }</pre>
     *
     * @param url an absolute URL, possibly a redirect wrapper
     * @return the decoded inner URL, or empty if none is embedded
     */
    private static Optional<String> embeddedTarget(String url) {
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd < 0 ? -1 : url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return Optional.empty();
        }
        String pathAndQuery = url.substring(pathStart);
        int hash = pathAndQuery.indexOf('#');
        if (hash >= 0) {
            pathAndQuery = pathAndQuery.substring(0, hash);
        }
        int questionMark = pathAndQuery.indexOf('?');
        String path = questionMark < 0 ? pathAndQuery : pathAndQuery.substring(0, questionMark);
        String query = questionMark < 0 ? "" : pathAndQuery.substring(questionMark + 1);

        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && isHttp(decode(pair.substring(equals + 1)))) {
                return Optional.of(decode(pair.substring(equals + 1)));
            }
        }
        for (String segment : path.split("/")) {
            if (isHttp(decode(segment))) {
                return Optional.of(decode(segment));
            }
        }
        return Optional.empty();
    }

    /**
     * Tells whether a URL looks like an email click-tracker, either by its host (a tracking subdomain such as
     * {@code click.} or {@code links.}, or a known email-marketing provider) or by a tracking path such as
     * {@code /ls/click} or {@code /e/ctt}.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "https://click.mailchimp.com/track?u=abc"            Output: true   (tracker host)
     * Input:  "https://news.example.org/ls/click?upn=aB3xQz9"       Output: true   (tracker path)
     * Input:  "https://www.nature.com/articles/s41586-024-0001"     Output: false
     * }</pre>
     *
     * @param url an absolute http(s) URL
     * @return true if the host or path matches a known tracker pattern
     */
    private static boolean looksLikeTracker(String url) {
        int hostStart = url.indexOf("://") + 3;
        int hostEnd = hostStart;
        while (hostEnd < url.length() && "/:?#".indexOf(url.charAt(hostEnd)) < 0) {
            hostEnd++;
        }
        String host = url.substring(hostStart, hostEnd).toLowerCase(Locale.ROOT);
        String path = url.substring(hostEnd);
        return TRACKER_HOST.matcher(host).matches() || TRACKER_PATH.matcher(path).find();
    }

    /**
     * Tells whether a string is an absolute http or https URL, ignoring case.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "HTTPS://www.nature.com/"   Output: true
     * Input:  "http://arxiv.org/abs/1"    Output: true
     * Input:  "mailto:a@b.org"            Output: false
     * }</pre>
     *
     * @param value the string to test
     * @return true if it starts with {@code http://} or {@code https://}
     */
    private static boolean isHttp(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Percent-decodes a URL component as UTF-8, keeping a literal {@code +} as {@code +} (rather than turning it
     * into a space, as form decoding would). Returns the input unchanged if it contains a malformed escape.
     *
     * <p>Example:
     *
     * <pre>{@code
     * Input:  "https%3A%2F%2Fwww.nature.com%2Farticles%2Fx"   Output: "https://www.nature.com/articles/x"
     * Input:  "a+b%20c"                                        Output: "a+b c"
     * Input:  "100%zz"                                         Output: "100%zz"   (malformed, unchanged)
     * }</pre>
     *
     * @param value the encoded string
     * @return the decoded string, or {@code value} itself if it can't be decoded
     */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
