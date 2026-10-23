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
     * Output: a new GmailEmailSource using a default AlertClassifier and real Gmail IMAP settings.
     *
     * Creates a production email source with default classification rules.
     */
    public GmailEmailSource() {
        this(new AlertClassifier());
    }

    /**
     * Input: maxUnread - how many unread, not yet processed messages one run examines, at least 1.
     * Output: a new GmailEmailSource using a default AlertClassifier and real Gmail IMAP settings.
     */
    public GmailEmailSource(int maxUnread) {
        this(new AlertClassifier(), GMAIL_IMAP_HOST, GMAIL_IMAP_PORT, false, maxUnread);
    }

    /**
     * Input: alertClassifier - decides whether an email is a publication alert.
     * Output: a new GmailEmailSource pointed at imap.gmail.com:993 with full certificate checking.
     *
     * Creates a production email source with a caller-supplied classifier.
     */
    public GmailEmailSource(AlertClassifier alertClassifier) {
        this(alertClassifier, GMAIL_IMAP_HOST, GMAIL_IMAP_PORT, false);
    }

    /**
     * Input: alertClassifier, imapHost, imapPort, and trustAnyServerCertificate (skip certificate checks).
     * Output: a new GmailEmailSource connected to the given IMAP server.
     *
     * Creates an email source with fully configurable server settings.
     *
     * Visible for tests to point at a fake IMAP server (e.g. GreenMail) instead of real Gmail.
     * trustAnyServerCertificate skips hostname/certificate validation, which is only ever
     * appropriate against a local test server's self-signed certificate - never for real Gmail.
     */
    GmailEmailSource(AlertClassifier alertClassifier, String imapHost, int imapPort, boolean trustAnyServerCertificate) {
        this(alertClassifier, imapHost, imapPort, trustAnyServerCertificate, DEFAULT_MAX_UNREAD);
    }

    /** As above, examining at most {@code maxUnread} unread, not yet processed messages per run. */
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
     * Input: credentials (Gmail address and app password) and the message IDs already processed.
     * Output: the new alert emails found in the INBOX, or an empty list if fetching fails.
     *
     * Logs in to Gmail over IMAP, reads the inbox, and always closes the connection and wipes the password.
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
     * Input: credentials and the Message-IDs of alerts the pipeline has recorded in M7a.
     * Output: how many of them were found unread in the INBOX and marked read; 0 if Gmail could not be reached.
     *
     * Opens the INBOX read-write, finds the unread messages by their Message-ID header alone, and sets the
     * \Seen flag on those in the list; every other message is left as it was.
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
     * Input: credentials and the Message-IDs of alerts the pipeline has processed.
     * Output: how many of them were found in the INBOX; each is unread afterwards.
     *
     * Opens the INBOX read-write, lets the server find the messages by their Message-ID header (in groups of
     * {@value #SEARCH_GROUP}, so no search command grows unbounded), and clears their \Seen flag. Unlike
     * {@link #markRead}, a failure is thrown: the caller is about to forget these messages and must know.
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
        T run(Store store) throws MessagingException;
    }

    /**
     * Input: credentials and the work to do while connected.
     * Output: the work's result; throws MessagingException if connecting or the work fails.
     *
     * Logs in over IMAP, runs the work, and always closes the connection and wipes the password copy.
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
     * Output: its unread messages, oldest first, with only their Message-ID header fetched.
     */
    private static Message[] unreadWithIds(Folder folder) throws MessagingException {
        Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        FetchProfile ids = new FetchProfile();
        ids.add("Message-ID");
        folder.fetch(unread, ids);
        return unread;
    }

    /**
     * Input: a connected Store and the message IDs already processed.
     * Output: the new alert emails among the newest maxUnread unread, unprocessed messages, oldest first;
     * throws MessagingException if the folder cannot be read.
     *
     * Opens the INBOX read-only, picks the unread messages by their Message-ID header alone, and converts
     * each new alert among them into a RawEmail.
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
     * Input: one mail message and the message IDs already processed.
     * Output: a RawEmail if the message is a new, readable alert; otherwise an empty Optional.
     *
     * Skips messages that lack an ID, were already processed, are not alerts, or cannot be read.
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
     * Input: a mail message and a header name.
     * Output: the first value of that header, or null if the header is absent.
     *
     * Reads a single header value from a message.
     */
    private static String firstHeaderOrNull(Message message, String headerName) throws MessagingException {
        String[] values = message.getHeader(headerName);
        return (values != null && values.length > 0) ? values[0] : null;
    }

    /**
     * Input: a mail message.
     * Output: the first sender's plain email address, or null if the message has no sender.
     *
     * Extracts the sender address from the From header, without the display name.
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
         * Output: a new BodyParts holding this one's HTML and plain text, filling any gap from the other.
         *
         * Combines two results, keeping the first HTML and first plain text found.
         */
        BodyParts merge(BodyParts other) {
            return new BodyParts(html.or(other::html), plainText.or(other::plainText));
        }
    }

    /**
     * Input: a message or message part.
     * Output: its HTML and plain-text bodies; throws MessagingException or IOException if content cannot be read.
     *
     * Walks the MIME structure, recursing into multipart sections, to find the text bodies.
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
     * Input: none (uses the host, port and trust settings of this instance).
     * Output: the Jakarta Mail properties for an SSL IMAP connection.
     *
     * Builds the connection settings, including 10-second timeouts and optional certificate trust.
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
