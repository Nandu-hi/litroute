package com.litroute.ingestion.email;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an inbox message looks like a publication/citation alert worth
 * handing to M2. M1 scans the whole INBOX with no label or filter, so this is the
 * only gate between every email the user has received and the pipeline.
 *
 * Acceptance is uniform and not limited to known publishers: KNOWN_ALERT_SENDER_DOMAINS
 * is a low-false-positive fast path (from six real client alerts: SpringerOpen,
 * Taylor &amp; Francis, Google Scholar, ScienceDirect/Elsevier, ASCE), while the broad
 * SUBJECT_KEYWORDS catch alerts from any other publication by subject wording.
 *
 * A forwarded alert has the forwarder as its IMAP sender, so when the direct
 * sender/subject don't match, the same check runs on <em>every</em> quoted
 * {@code From:}/{@code Subject:} header block in the body (the innermost block is
 * often the one naming the publisher). Blocks are found by the header pair itself,
 * not by a mail client's forward banner, so Gmail, Apple Mail and Outlook forwards,
 * including multi-hop chains with "&gt;" quote markers, all work.
 *
 * See {@link #isPublicationAlert(String, String, Optional)}. Extend
 * KNOWN_ALERT_SENDER_DOMAINS/SUBJECT_KEYWORDS for new mailing lists instead of
 * adding classifier logic.
 */
public final class AlertClassifier {

    /**
     * The "From:" line of a quoted message, and the address inside it. The address in the
     * last "&lt;...&gt;" is preferred, so a display name that itself contains an address
     * ({@code "a@corp.com via Lists" <noreply@publisher.com>}) can't be mistaken for the
     * sender; a bare address with no brackets is the fallback.
     */
    private static final Pattern QUOTED_FROM_LINE =
            Pattern.compile("(?i)^From:(?:.*<|.*?)(?<from>[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,})");

    /** The "Subject:" line of the same block. */
    private static final Pattern QUOTED_SUBJECT_LINE = Pattern.compile("(?i)^Subject:\\s*(?<subject>.*)");

    /**
     * The "> " (or HTML-escaped "&gt;&gt; ") markers a client adds to each line for every
     * level it quotes.
     */
    private static final Pattern QUOTE_MARKERS = Pattern.compile("(?i)^(?:>|&gt;|\\s)+");

    /**
     * How far below a "From:" line its block's "Subject:" may sit. Gmail writes
     * From/Date/Subject/To and Outlook From/Sent/To/Subject, so three lines of slack
     * covers both with room to spare; the search stops at the blank line that ends the
     * header block anyway, so it never runs on into the message body.
     */
    private static final int SUBJECT_LOOKAHEAD_LINES = 4;

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
                && ((KNOWN_ALERT_SENDER_DOMAINS.contains(domain) || extraAllowedSenderDomains.contains(domain)))) {
            return true;
        }

        if (subject == null) {
            return false;
        }
        String normalizedSubject = subject.toLowerCase(Locale.ROOT);
        return SUBJECT_KEYWORDS.stream().anyMatch(normalizedSubject::contains);
    }

    /**
     * Same as {@link #isPublicationAlert(String, String)}, but additionally recovers the quoted
     * sender/subject of every message the body quotes (see class javadoc) and re-checks each of
     * them - so a paper alert forwarded by anyone, any number of times, is still recognized
     * without needing a forwarder-specific mechanism.
     */
    public boolean isPublicationAlert(String fromAddress, String subject, Optional<String> plainTextBody) {
        if (isPublicationAlert(fromAddress, subject)) {
            return true;
        }
        for (QuotedHeaders quoted : plainTextBody.map(AlertClassifier::extractQuotedHeaders).orElse(List.of())) {
            if (isPublicationAlert(quoted.fromAddress(), quoted.subject())) {
                return true;
            }
        }
        return false;
    }

    private record QuotedHeaders(String fromAddress, String subject) {
    }

    /**
     * Every quoted From/Subject pair in the body, outermost first. A message forwarded n times
     * quotes n blocks; the publisher's own is the innermost, so all of them are returned rather
     * than only the first.
     */
    private static List<QuotedHeaders> extractQuotedHeaders(String body) {
        String[] lines = body.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = QUOTE_MARKERS.matcher(lines[i]).replaceFirst("");
        }
        List<QuotedHeaders> quoted = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher from = QUOTED_FROM_LINE.matcher(lines[i]);
            if (!from.find()) {
                continue;
            }
            int lastLine = Math.min(lines.length - 1, i + SUBJECT_LOOKAHEAD_LINES);
            for (int j = i + 1; j <= lastLine && !lines[j].isBlank(); j++) {
                Matcher subject = QUOTED_SUBJECT_LINE.matcher(lines[j]);
                if (subject.matches()) {
                    quoted.add(new QuotedHeaders(from.group("from"), subject.group("subject").trim()));
                    break;
                }
            }
        }
        return quoted;
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
