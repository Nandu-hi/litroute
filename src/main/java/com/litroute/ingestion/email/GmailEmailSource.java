package com.litroute.ingestion.email;

import com.litroute.model.RawEmail;
import com.litroute.security.GmailCredentials;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IMAP-backed M1 EmailSource. Scans the whole INBOX (no dedicated Gmail
 * label required) and skips anything already in M7a's cache. Bodies are
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

    public GmailEmailSource() {
        this(new AlertClassifier());
    }

    public GmailEmailSource(AlertClassifier alertClassifier) {
        this(alertClassifier, GMAIL_IMAP_HOST, GMAIL_IMAP_PORT, false);
    }

    /**
     * Visible for tests to point at a fake IMAP server (e.g. GreenMail) instead of real Gmail.
     * trustAnyServerCertificate skips hostname/certificate validation, which is only ever
     * appropriate against a local test server's self-signed certificate - never for real Gmail.
     */
    GmailEmailSource(AlertClassifier alertClassifier, String imapHost, int imapPort, boolean trustAnyServerCertificate) {
        this.alertClassifier = alertClassifier;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.trustAnyServerCertificate = trustAnyServerCertificate;
    }

    @Override
    public List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> alreadyProcessedMessageIds) {
        char[] appPassword = credentials.appPassword();
        try {
            Session session = Session.getInstance(imapProperties());
            Store store = session.getStore("imaps");
            try {
                store.connect(imapHost, imapPort, credentials.emailAddress(), new String(appPassword));
                return readInbox(store, alreadyProcessedMessageIds);
            } finally {
                if (store.isConnected()) {
                    store.close();
                }
            }
        } catch (MessagingException e) {
            LOG.log(Level.WARNING, "Failed to fetch mail from Gmail; returning no new alerts for this run", e);
            return List.of();
        } finally {
            Arrays.fill(appPassword, '\0');
        }
    }

    private List<RawEmail> readInbox(Store store, Set<String> alreadyProcessedMessageIds) throws MessagingException {
        List<RawEmail> alerts = new ArrayList<>();
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        try {
            for (Message message : inbox.getMessages()) {
                Optional<RawEmail> rawEmail = toRawEmailIfNewAlert(message, alreadyProcessedMessageIds);
                rawEmail.ifPresent(alerts::add);
            }
        } finally {
            inbox.close(false);
        }
        return alerts;
    }

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

    private static String firstHeaderOrNull(Message message, String headerName) throws MessagingException {
        String[] values = message.getHeader(headerName);
        return (values != null && values.length > 0) ? values[0] : null;
    }

    private static String fromAddressOf(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        return (from[0] instanceof InternetAddress internetAddress) ? internetAddress.getAddress() : from[0].toString();
    }

    private record BodyParts(Optional<String> html, Optional<String> plainText) {
        static final BodyParts EMPTY = new BodyParts(Optional.empty(), Optional.empty());

        BodyParts merge(BodyParts other) {
            return new BodyParts(html.or(other::html), plainText.or(other::plainText));
        }
    }

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

    private Properties imapProperties() {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", imapHost);
        properties.put("mail.imaps.port", String.valueOf(imapPort));
        properties.put("mail.imaps.ssl.enable", "true");
        properties.put("mail.imaps.connectiontimeout", "10000");
        properties.put("mail.imaps.timeout", "10000");
        if (trustAnyServerCertificate) {
            properties.put("mail.imaps.ssl.trust", "*");
            properties.put("mail.imaps.ssl.checkserveridentity", "false");
        }
        return properties;
    }
}
