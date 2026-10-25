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
 * handing to M2. M1 scans the INBOX's unread mail with no label or filter, so this is
 * the only gate between every email the user has received and the pipeline.
 *
 * Acceptance is uniform and not limited to known publishers: KNOWN_ALERT_SENDER_DOMAINS
 * and KNOWN_ALERT_SENDER_ADDRESSES are a low-false-positive fast path (from six real
 * client alerts: SpringerOpen, Taylor &amp; Francis, Google Scholar, ScienceDirect/Elsevier,
 * ASCE), while the broad SUBJECT_KEYWORDS catch alerts from any other publication by
 * subject wording.
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

    /**
     * Domains that send nothing but alerts, or whose alerts are worth the occasional newsletter. A
     * domain that also sends account, security or product mail (google.com: sign-in alerts, Workspace
     * and Cloud notices) must not be here; trust its alert address in KNOWN_ALERT_SENDER_ADDRESSES.
     */
    private static final Set<String> KNOWN_ALERT_SENDER_DOMAINS = Set.of(
            "scholar.google.com",
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

    /**
     * Single alert addresses on domains that send other mail too. Google Scholar alerts come from
     * scholaralerts-noreply@google.com, the only google.com address trusted as an alert sender.
     */
    private static final Set<String> KNOWN_ALERT_SENDER_ADDRESSES = Set.of(
            "scholaralerts-noreply@google.com");

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

    /**
     * Input: none.
     * Output: a classifier trusting only the built-in sender lists and subject keywords.
     * Used by: GmailEmailSource (M1) constructors.
     * Example: {@code new AlertClassifier().isPublicationAlert("x@springer.com", "Hi")} -> true
     */
    public AlertClassifier() {
        this(Set.of());
    }

    /**
     * Input: additional publisher domains to trust, beyond KNOWN_ALERT_SENDER_DOMAINS.
     * Output: a classifier that also accepts any sender on those domains.
     * Used by: {@link #AlertClassifier()}; AlertClassifierTest.
     * Example: {@code new AlertClassifier(Set.of("mdpi.com")).isPublicationAlert("news@mdpi.com", "Hi")} -> true
     */
    public AlertClassifier(Set<String> extraAllowedSenderDomains) {
        this.extraAllowedSenderDomains = extraAllowedSenderDomains;
    }

    /**
     * Input: the sender address and subject (either may be null).
     * Output: true if the address, its domain, or a subject keyword marks an alert.
     * Used by: the three-argument overload, for the direct and each quoted header block.
     * Example: ("bob@gmail.com", "New citations to my articles") -> true; ("security@google.com", "Sign-in alert") -> false
     */
    public boolean isPublicationAlert(String fromAddress, String subject) {
        if (fromAddress != null && KNOWN_ALERT_SENDER_ADDRESSES.contains(fromAddress.toLowerCase(Locale.ROOT))) {
            return true;
        }
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
     * As {@link #isPublicationAlert(String, String)}, then re-checks every quoted From/Subject block in the body,
     * so an alert forwarded by anyone, any number of times, is still recognized.
     * Input: sender, subject and the plain-text body (if any).
     * Output: true if the message or any message it quotes is an alert.
     * Used by: GmailEmailSource.toRawEmailIfNewAlert (M1).
     * Example: from me@uni.edu, "Fwd: FYI", body quotes "From: alerts@asce.org" -> true
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
     * Every quoted From/Subject pair, outermost first; the publisher's own is the innermost, so all are returned.
     * Input: a plain-text body.
     * Output: the quoted header pairs found, after stripping "&gt;" quote markers.
     * Used by: {@link #isPublicationAlert(String, String, Optional)}.
     * Example: "From: A &lt;a@x.com&gt;\nDate: ..\nSubject: Hi" -> {@code [QuotedHeaders("a@x.com", "Hi")]}
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
            // Look a few lines down for this block's Subject, stopping at the blank line ending the headers.
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

    /**
     * Input: an email address (null allowed).
     * Output: its lowercased domain, or null if there is none.
     * Used by: {@link #isPublicationAlert(String, String)}.
     * Example: {@code domainOf("Alerts@SpringerOpen.com")} -> {@code "springeropen.com"}
     */
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
