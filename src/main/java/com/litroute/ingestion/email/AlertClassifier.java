package com.litroute.ingestion.email;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an inbox message looks like a publication/citation alert
 * worth handing to M2, as opposed to ordinary mail. M1 scans the whole
 * INBOX rather than requiring a dedicated Gmail label/filter, so this is the
 * only thing standing between "every email the user has ever received" and
 * the pipeline.
 *
 * The job is purely "does this look like a paper alert", applied uniformly
 * regardless of how the mail arrived - there is no separate forwarding
 * mechanism and no forwarder-address allowlist. Acceptance is not limited to
 * a fixed set of known publishers either: KNOWN_ALERT_SENDER_DOMAINS is a
 * fast, low-false-positive path for mailing lists already confirmed (via six
 * real alert emails the client shared: SpringerOpen, Taylor & Francis,
 * Google Scholar, ScienceDirect/Elsevier, and ASCE), while SUBJECT_KEYWORDS
 * is deliberately broad so an alert from any publication is still recognized
 * by its subject wording alone.
 *
 * A forwarded alert is still just "an email that looks like a paper alert" -
 * when the direct envelope sender/subject don't match (typical for a forward,
 * since the IMAP "From" is the forwarder's own address), the same check is
 * re-run against the original message's quoted sender/subject, recovered
 * from Gmail's own forward marker in the body:
 *
 * <pre>
 * ---------- Forwarded message ---------
 * From: SpringerOpen &lt;no-reply@springeropen.com&gt;
 * Date: Mon, Jan 27, 2025 at 2:35 PM
 * Subject: New Articles From Journal of Infrastructure Preservation and Resilience
 * To: &lt;aprakashn@gmail.com&gt;
 * </pre>
 *
 * See {@link #isPublicationAlert(String, String, Optional)}. Extend
 * KNOWN_ALERT_SENDER_DOMAINS/SUBJECT_KEYWORDS as new mailing lists or
 * phrasings come up rather than writing new classifier logic.
 */
public final class AlertClassifier {

    /**
     * Matches Gmail's own "Forwarded message" marker plus the quoted
     * original From/Date/Subject header block it inserts above a forwarded
     * message's body.
     */
    private static final Pattern FORWARDED_HEADER_PATTERN = Pattern.compile(
            "-{2,}\\s*Forwarded message\\s*-{2,}\\R"
                    + "From:.*?<?(?<from>[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,})>?.*?\\R"
                    + "Date:.*?\\R"
                    + "Subject:\\s*(?<subject>.*?)\\R",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> KNOWN_ALERT_SENDER_DOMAINS = Set.of(
            "scholar.google.com",
            "google.com",
            "arxiv.org",
            "ieee.org",
            "sciencedirect.com",
            "elsevier.com",
            "notification.elsevier.com",
            "springer.com",
            "springernature.com",
            "springeropen.com",
            "tandfonline.com",
            "informa.com",
            "acm.org",
            "ieeexplore.ieee.org",
            "asce.org",
            "semanticscholar.org",
            "researchgate.net",
            "wiley.com",
            "nature.com");

    private static final Set<String> SUBJECT_KEYWORDS = Set.of(
            "new citations",
            "new results for",
            "new articles",
            "recommended articles",
            "new recommendations",
            "table of contents",
            "publication alert",
            "citation alert",
            "new paper",
            "toc alert",
            "new issue alert",
            "issue alert",
            "new content alert",
            "content alert",
            "search alert",
            "saved search alert",
            "now available online",
            "latest issue",
            "current issue",
            "just accepted",
            "early access",
            "advance access",
            "online first",
            "accepted articles",
            "forthcoming articles",
            "papers matching your interests",
            "recommended for you",
            "research alert",
            "journal alert",
            "new preprint",
            "preprint alert");

    private final Set<String> extraAllowedSenderDomains;

    public AlertClassifier() {
        this(Set.of());
    }

    /**
     * @param extraAllowedSenderDomains additional publisher domains to trust, beyond
     *         KNOWN_ALERT_SENDER_DOMAINS.
     */
    public AlertClassifier(Set<String> extraAllowedSenderDomains) {
        this.extraAllowedSenderDomains = extraAllowedSenderDomains;
    }

    public boolean isPublicationAlert(String fromAddress, String subject) {
        String domain = domainOf(fromAddress);
        if (domain != null
                && (KNOWN_ALERT_SENDER_DOMAINS.contains(domain) || extraAllowedSenderDomains.contains(domain))) {
            return true;
        }

        if (subject == null) {
            return false;
        }
        String normalizedSubject = subject.toLowerCase(Locale.ROOT);
        return SUBJECT_KEYWORDS.stream().anyMatch(normalizedSubject::contains);
    }

    /**
     * Same as {@link #isPublicationAlert(String, String)}, but additionally recovers a
     * forwarded message's quoted original sender/subject from its plain-text body (see class
     * javadoc) and re-checks those too - so a paper alert forwarded by anyone is still
     * recognized, without needing a forwarder-specific mechanism.
     */
    public boolean isPublicationAlert(String fromAddress, String subject, Optional<String> plainTextBody) {
        if (isPublicationAlert(fromAddress, subject)) {
            return true;
        }
        return plainTextBody
                .flatMap(AlertClassifier::extractForwardedOriginalHeaders)
                .map(original -> isPublicationAlert(original.fromAddress(), original.subject()))
                .orElse(false);
    }

    private record ForwardedOriginalHeaders(String fromAddress, String subject) {
    }

    private static Optional<ForwardedOriginalHeaders> extractForwardedOriginalHeaders(String body) {
        Matcher matcher = FORWARDED_HEADER_PATTERN.matcher(body);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new ForwardedOriginalHeaders(matcher.group("from"), matcher.group("subject").trim()));
    }

    private static String domainOf(String emailAddress) {
        if (emailAddress == null) {
            return null;
        }
        int atIndex = emailAddress.lastIndexOf('@');
        if (atIndex < 0 || atIndex == emailAddress.length() - 1) {
            return null;
        }
        return emailAddress.substring(atIndex + 1).toLowerCase(Locale.ROOT);
    }
}
