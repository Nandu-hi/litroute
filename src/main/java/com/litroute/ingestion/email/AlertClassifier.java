package com.litroute.ingestion.email;

import java.util.ArrayList;
import java.util.List;
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
 * re-run against the sender/subject of every message quoted in the body:
 *
 * <pre>
 * ---------- Forwarded message ---------
 * From: An Advisor &lt;advisor@example.edu&gt;
 * Date: Tue, Jan 28, 2025 at 9:10 AM
 * Subject: Fwd: New Articles From Journal of Infrastructure Preservation and Resilience
 * To: &lt;aprakashn@gmail.com&gt;
 *
 * ---------- Forwarded message ---------
 * From: SpringerOpen &lt;no-reply@springeropen.com&gt;
 * Date: Mon, Jan 27, 2025 at 2:35 PM
 * Subject: New Articles From Journal of Infrastructure Preservation and Resilience
 * To: &lt;someone@example.edu&gt;
 * </pre>
 *
 * An alert can reach the inbox after any number of hops, so <em>every</em>
 * quoted header block is checked, not just the outermost one: the block a
 * publisher's own domain appears in is the innermost, and with a subject that
 * only the publisher domain identifies (no alert keyword), stopping at the
 * first block would miss the alert entirely. Blocks are found by the quoted
 * {@code From:}/{@code Subject:} header pair itself rather than by one mail
 * client's forward marker, so Gmail's "Forwarded message" banner, Apple Mail's
 * "Begin forwarded message:" and Outlook's "-----Original Message-----" all
 * work, including a chain that passed through several of them and picked up
 * "&gt;" quote markers on the way.
 *
 * See {@link #isPublicationAlert(String, String, Optional)}. Extend
 * KNOWN_ALERT_SENDER_DOMAINS/SUBJECT_KEYWORDS as new mailing lists or
 * phrasings come up rather than writing new classifier logic.
 */
public final class AlertClassifier {

    /** The "From:" line of a quoted message, and the address inside it. */
    private static final Pattern QUOTED_FROM_LINE =
            Pattern.compile("(?i)^From:.*?<?(?<from>[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,})>?");

    /** The "Subject:" line of the same block. */
    private static final Pattern QUOTED_SUBJECT_LINE = Pattern.compile("(?i)^Subject:\\s*(?<subject>.*)");

    /** The "> " (or "&gt;&gt; ") markers a client adds to each line for every level it quotes. */
    private static final Pattern QUOTE_MARKERS = Pattern.compile("^[>\\s]+");

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
