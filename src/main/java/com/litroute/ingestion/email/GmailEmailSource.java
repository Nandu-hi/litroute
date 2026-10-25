package com.litroute.ingestion.email;

import com.litroute.model.RawEmail;
import com.litroute.security.GmailCredentials;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IMAP-backed M1 EmailSource. Scans the INBOX's unread messages (no dedicated
 * Gmail label required), newest first and at most {@code maxUnread} of them per
 * run, skipping anything already in M7a's cache. Messages already processed do
 * not count against the limit, so a backlog larger than the limit is worked
 * through over successive runs. The INBOX is opened read-only, so fetching
 * leaves every message unread; once the pipeline has recorded the alerts in
 * M7a, {@link #markRead} marks just those read. Bodies are
 * extracted before the final accept/reject decision because AlertClassifier
 * needs the plain-text body to recognize a forwarded alert by its quoted
 * original headers (see AlertClassifier's javadoc) - classification can no
 * longer be a body-free, envelope-only check.
 */
public final class GmailEmailSource implements EmailSource {

    private static final Logger LOG = Logger.getLogger(GmailEmailSource.class.getName());
    private static final String GMAIL_IMAP_HOST = "imap.gmail.com";
    private static final int GMAIL_IMAP_PORT = 993;

    private final AlertClassifier alertClassifier;
    private final String imapHost;
    private final int imapPort;
    private final boolean trustAnyServerCertificate;
    private final int maxUnread;

    /** How many unread messages a run examines unless told otherwise. */
    public static final int DEFAULT_MAX_UNREAD = 50;
    /** Message-IDs looked up per IMAP search when marking mail unread. */
    private static final int SEARCH_GROUP = 50;

    /**
     * Input: none.
     * Output: a source for imap.gmail.com:993 with the default classifier and {@value #DEFAULT_MAX_UNREAD} messages per run.
     * Used by: tests and manual runs.
     * Example: {@code new GmailEmailSource().fetchNewAlerts(creds, Set.of())} -> up to 50 newest unread alerts
     */
    public GmailEmailSource() {
        this(new AlertClassifier());
    }

    /**
     * Input: how many unread, not yet processed messages one run examines (at least 1).
     * Output: a source for imap.gmail.com:993 with the default classifier.
     * Used by: PipelineRunner.atDefaultLocations (M9), from the Pipeline tab's / CLI's max-unread setting.
     * Example: {@code new GmailEmailSource(100)} -> examines the 100 newest unprocessed unread messages
     */
    public GmailEmailSource(int maxUnread) {
        this(new AlertClassifier(), GMAIL_IMAP_HOST, GMAIL_IMAP_PORT, false, maxUnread);
    }

    /**
     * Input: the classifier that decides whether an email is a publication alert.
     * Output: a source for imap.gmail.com:993 with full certificate checking.
     * Used by: {@link #GmailEmailSource()}.
     * Example: {@code new GmailEmailSource(new AlertClassifier(Set.of("mdpi.com")))} -> also trusts mdpi.com alerts
     */
    public GmailEmailSource(AlertClassifier alertClassifier) {
        this(alertClassifier, GMAIL_IMAP_HOST, GMAIL_IMAP_PORT, false);
    }

    /**
     * Visible for tests (GreenMail). trustAnyServerCertificate skips certificate checks: local test servers only, never Gmail.
     * Input: classifier, IMAP host and port, and whether to trust any certificate.
     * Output: a source for that server, {@value #DEFAULT_MAX_UNREAD} messages per run.
     * Used by: {@link #GmailEmailSource(AlertClassifier)}; GmailEmailSourceTest.
     * Example: {@code new GmailEmailSource(c, "localhost", 3993, true)} -> talks to a local GreenMail server
     */
    GmailEmailSource(AlertClassifier alertClassifier, String imapHost, int imapPort, boolean trustAnyServerCertificate) {
        this(alertClassifier, imapHost, imapPort, trustAnyServerCertificate, DEFAULT_MAX_UNREAD);
    }

    /**
     * Input: as above, plus how many unread, unprocessed messages a run examines.
     * Output: the source; throws if maxUnread &lt; 1.
     * Used by: every other constructor; tests.
     * Example: maxUnread 0 -> throws "maxUnread must be at least 1, was 0"
     */
    GmailEmailSource(AlertClassifier alertClassifier, String imapHost, int imapPort, boolean trustAnyServerCertificate,
            int maxUnread) {
        if (maxUnread < 1) {
            throw new IllegalArgumentException("maxUnread must be at least 1, was " + maxUnread);
        }
        this.maxUnread = maxUnread;
        this.alertClassifier = alertClassifier;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.trustAnyServerCertificate = trustAnyServerCertificate;
    }

    /**
     * Input: credentials and the Message-IDs already processed (M7a).
     * Output: the new alerts among the newest maxUnread unprocessed unread messages, oldest first; empty if fetching fails.
     * Used by: PipelineRunner.run (M9).
     * Example: 3 unread (1 alert, 1 newsletter, 1 processed) -> {@code [RawEmail(alert)]}
     */
    @Override
    public List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> alreadyProcessedMessageIds) {
        try {
            return withStore(credentials, store -> readInbox(store, alreadyProcessedMessageIds));
        } catch (MessagingException e) {
            LOG.log(Level.WARNING, "Failed to fetch mail from Gmail; returning no new alerts for this run", e);
            return List.of();
        }
    }

    /**
     * Sets \Seen on the unread INBOX messages whose Message-ID is in the list; every other message is untouched.
     * Input: credentials and the Message-IDs recorded in M7a.
     * Output: how many were found unread and marked read; 0 if Gmail could not be reached.
     * Used by: PipelineRunner.run (M9), after AlertBatchProcessor saved M7a.
     * Example: {@code markRead(c, ["<a@x>"])} with a@x unread -> {@code 1}; already read -> {@code 0}
     */
    @Override
    public int markRead(GmailCredentials credentials, Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return 0;
        }
        Set<String> wanted = Set.copyOf(messageIds);
        try {
            return withStore(credentials, store -> {
                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
                try {
                    List<Message> processed = new ArrayList<>();
                    for (Message message : unreadWithIds(inbox)) {
                        if (wanted.contains(firstHeaderOrNull(message, "Message-ID"))) {
                            processed.add(message);
                        }
                    }
                    if (!processed.isEmpty()) {
                        inbox.setFlags(processed.toArray(Message[]::new), new Flags(Flags.Flag.SEEN), true);
                    }
                    return processed.size();
                } finally {
                    inbox.close(false);
                }
            });
        } catch (MessagingException e) {
            LOG.log(Level.WARNING, "Failed to mark processed alerts as read in Gmail; they stay unread", e);
            return 0;
        }
    }

    /**
     * Server-side searches by Message-ID (groups of {@value #SEARCH_GROUP}) and clears \Seen. Unlike
     * {@link #markRead}, failure is thrown: the caller is about to forget these messages and must know.
     * Input: credentials and the Message-IDs recorded in M7a.
     * Output: how many were found in the INBOX (each unread afterwards); IOException if Gmail failed.
     * Used by: PipelineRunner.reset (M9).
     * Example: {@code markUnread(c, ["<a@x>","<gone@x>"])} -> {@code 1}
     */
    @Override
    public int markUnread(GmailCredentials credentials, Collection<String> messageIds) throws IOException {
        if (messageIds.isEmpty()) {
            return 0;
        }
        List<String> wanted = List.copyOf(Set.copyOf(messageIds));
        try {
            return withStore(credentials, store -> {
                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
                try {
                    List<Message> found = new ArrayList<>();
                    Set<Integer> foundNumbers = new HashSet<>();
                    for (int from = 0; from < wanted.size(); from += SEARCH_GROUP) {
                        SearchTerm[] terms = wanted.subList(from, Math.min(wanted.size(), from + SEARCH_GROUP)).stream()
                                .map(id -> new HeaderTerm("Message-ID", id)).toArray(SearchTerm[]::new);
                        for (Message message : inbox.search(terms.length == 1 ? terms[0] : new OrTerm(terms))) {
                            // The server matches a header substring; keep exact matches, each message once.
                            if (wanted.contains(firstHeaderOrNull(message, "Message-ID"))
                                    && foundNumbers.add(message.getMessageNumber())) {
                                found.add(message);
                            }
                        }
                    }
                    if (!found.isEmpty()) {
                        inbox.setFlags(found.toArray(Message[]::new), new Flags(Flags.Flag.SEEN), false);
                    }
                    return found.size();
                } finally {
                    inbox.close(false);
                }
            });
        } catch (MessagingException e) {
            throw new IOException("Could not mark the processed alerts unread in Gmail: " + e.getMessage(), e);
        }
    }

    /** What to do with a connected Store. */
    @FunctionalInterface
    private interface StoreWork<T> {
        /**
         * Input: a connected IMAP store.
         * Output: the work's result.
         * Used by: {@link #withStore}.
         * Example: {@code store -> readInbox(store, ids)} -> list of alerts
         */
        T run(Store store) throws MessagingException;
    }

    /**
     * Logs in over IMAP, runs the work, and always closes the connection and wipes the password copy.
     * Input: credentials and the work to do while connected.
     * Output: the work's result; MessagingException if connecting or the work fails.
     * Used by: fetchNewAlerts, markRead, markUnread.
     * Example: {@code withStore(c, store -> 42)} -> {@code 42}, connection closed
     */
    private <T> T withStore(GmailCredentials credentials, StoreWork<T> work) throws MessagingException {
        char[] appPassword = credentials.appPassword();
        try {
            Session session = Session.getInstance(imapProperties());
            Store store = session.getStore("imaps");
            try {
                store.connect(imapHost, imapPort, credentials.emailAddress(), new String(appPassword));
                return work.run(store);
            } finally {
                if (store.isConnected()) {
                    store.close();
                }
            }
        } finally {
            Arrays.fill(appPassword, '\0');
        }
    }

    /**
     * Input: an open folder.
     * Output: its unread messages, oldest first, with only their Message-ID header fetched (bodies not downloaded).
     * Used by: readInbox, markRead.
     * Example: INBOX with 3 unread of 10 -> 3 messages
     */
    private static Message[] unreadWithIds(Folder folder) throws MessagingException {
        Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        FetchProfile ids = new FetchProfile();
        ids.add("Message-ID");
        folder.fetch(unread, ids);
        return unread;
    }

    /**
     * Opens the INBOX read-only, picks messages by Message-ID alone, then downloads and classifies only those.
     * Input: a connected Store and the Message-IDs already processed.
     * Output: the alerts among the newest maxUnread unprocessed unread messages, oldest first.
     * Used by: {@link #fetchNewAlerts}.
     * Example: maxUnread 2, unread [m1,m2,m3] none processed -> examines m2,m3 -> returns those that are alerts
     */
    private List<RawEmail> readInbox(Store store, Set<String> alreadyProcessedMessageIds) throws MessagingException {
        List<RawEmail> alerts = new ArrayList<>();
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        try {
            Message[] unread = unreadWithIds(inbox);
            // Newest first, so the limit keeps the latest alerts; only the chosen ones are downloaded in full.
            List<Message> chosen = new ArrayList<>();
            for (int i = unread.length - 1; i >= 0 && chosen.size() < maxUnread; i--) {
                String messageId = firstHeaderOrNull(unread[i], "Message-ID");
                if (messageId != null && !alreadyProcessedMessageIds.contains(messageId)) {
                    chosen.add(unread[i]);
                }
            }
            LOG.info("Examining " + chosen.size() + " of " + unread.length + " unread messages (limit " + maxUnread
                    + ", already processed ones skipped)");
            // Back to oldest first.
            for (int i = chosen.size() - 1; i >= 0; i--) {
                Optional<RawEmail> rawEmail = toRawEmailIfNewAlert(chosen.get(i), alreadyProcessedMessageIds);
                rawEmail.ifPresent(alerts::add);
            }
        } finally {
            inbox.close(false);
        }
        return alerts;
    }

    /**
     * Input: one message and the Message-IDs already processed.
     * Output: a RawEmail if it is a new, readable alert; empty if it has no ID, was processed, is no alert or is unreadable.
     * Used by: {@link #readInbox}.
     * Example: from alerts@springeropen.com, subject "New articles" -> {@code Optional[RawEmail]}; a receipt -> empty
     */
    private Optional<RawEmail> toRawEmailIfNewAlert(Message message, Set<String> alreadyProcessedMessageIds) {
        try {
            String messageId = firstHeaderOrNull(message, "Message-ID");
            if (messageId == null || alreadyProcessedMessageIds.contains(messageId)) {
                return Optional.empty();
            }

            String fromAddress = fromAddressOf(message);
            String subject = message.getSubject();
            BodyParts bodyParts = extractBodies(message);
            if (!alertClassifier.isPublicationAlert(fromAddress, subject, bodyParts.plainText())) {
                return Optional.empty();
            }

            if (bodyParts.html().isEmpty() && bodyParts.plainText().isEmpty()) {
                LOG.warning("Skipping message " + messageId + " with no readable body");
                return Optional.empty();
            }

            // Some servers omit the received date; "now" keeps the record valid.
            Instant receivedAt = message.getReceivedDate() != null
                    ? message.getReceivedDate().toInstant()
                    : Instant.now();

            return Optional.of(new RawEmail(
                    messageId, subject, fromAddress, receivedAt, bodyParts.html(), bodyParts.plainText()));
        } catch (MessagingException | IOException e) {
            LOG.log(Level.WARNING, "Skipping unreadable message", e);
            return Optional.empty();
        }
    }

    /**
     * Input: a message and a header name.
     * Output: the header's first value, or null if absent.
     * Used by: markRead, markUnread, readInbox, toRawEmailIfNewAlert.
     * Example: {@code firstHeaderOrNull(m, "Message-ID")} -> {@code "<a@x>"}
     */
    private static String firstHeaderOrNull(Message message, String headerName) throws MessagingException {
        String[] values = message.getHeader(headerName);
        return (values != null && values.length > 0) ? values[0] : null;
    }

    /**
     * Input: a message.
     * Output: the first sender's bare address (no display name), or null if there is no sender.
     * Used by: {@link #toRawEmailIfNewAlert}.
     * Example: From {@code "Springer" <alerts@springer.com>} -> {@code "alerts@springer.com"}
     */
    private static String fromAddressOf(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        return (from[0] instanceof InternetAddress internetAddress) ? internetAddress.getAddress() : from[0].toString();
    }

    private record BodyParts(Optional<String> html, Optional<String> plainText) {
        static final BodyParts EMPTY = new BodyParts(Optional.empty(), Optional.empty());

        /**
         * Input: another BodyParts.
         * Output: this one's HTML and plain text, with any gap filled from the other (first found wins).
         * Used by: {@link GmailEmailSource#extractBodies} for multipart messages.
         * Example: (html, -) merge (html2, text) -> (html, text)
         */
        BodyParts merge(BodyParts other) {
            return new BodyParts(html.or(other::html), plainText.or(other::plainText));
        }
    }

    /**
     * Walks the MIME tree, recursing into multiparts, for the first text/html and first text/plain body.
     * Input: a message or message part.
     * Output: its HTML and plain-text bodies (either may be empty).
     * Used by: {@link #toRawEmailIfNewAlert}.
     * Example: multipart/alternative[text/plain, text/html] -> BodyParts(html, plain)
     */
    private BodyParts extractBodies(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/html") && part.getContent() instanceof String htmlContent) {
            return new BodyParts(Optional.of(htmlContent), Optional.empty());
        }
        if (part.isMimeType("text/plain") && part.getContent() instanceof String plainContent) {
            return new BodyParts(Optional.empty(), Optional.of(plainContent));
        }
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
            BodyParts combined = BodyParts.EMPTY;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                combined = combined.merge(extractBodies(bodyPart));
            }
            return combined;
        }
        return BodyParts.EMPTY;
    }

    /**
     * Input: none (this instance's host, port and trust settings).
     * Output: Jakarta Mail properties for IMAPS with 10 s timeouts, PEEK reads and optional trust-all.
     * Used by: {@link #withStore}.
     * Example: -> {@code {mail.imaps.host=imap.gmail.com, mail.imaps.port=993, mail.imaps.peek=true, ...}}
     */
    private Properties imapProperties() {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", imapHost);
        properties.put("mail.imaps.port", String.valueOf(imapPort));
        properties.put("mail.imaps.ssl.enable", "true");
        properties.put("mail.imaps.connectiontimeout", "10000");
        properties.put("mail.imaps.timeout", "10000");
        // BODY.PEEK: reading a message must leave it unread, whether or not the server honours read-only mode.
        properties.put("mail.imaps.peek", "true");
        if (trustAnyServerCertificate) {
            properties.put("mail.imaps.ssl.trust", "*");
            properties.put("mail.imaps.ssl.checkserveridentity", "false");
        }
        return properties;
    }
}
