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
 */
final class LinkUnwrapper {

    record Link(LinkKind kind, Optional<String> url) {
        static final Link NONE = new Link(LinkKind.NONE, Optional.empty());
    }

    private static final int MAX_UNWRAP_DEPTH = 4;

    private static final Pattern TRACKER_HOST = Pattern.compile(
            "(?i)^(?:click|clicks|email|emails|em|e|links|track|tracking|trk|url|mail|sg|ls|mkt|mktg|elink)\\..+"
                    + "|.*(?:list-manage\\.com|sendgrid\\.net|mandrillapp\\.com|mailchimp|rs6\\.net|constantcontact"
                    + "|exacttarget|eloqua|pardot|hubspotlinks|emltrk|amazonses).*");
    private static final Pattern TRACKER_PATH = Pattern.compile("(?i)/(?:ls/click|e/ctt|track/click|click)(?:[/?]|$)");

    private LinkUnwrapper() {
    }

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

    private static boolean isHttp(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
