package com.litroute.delivery;

import com.litroute.security.GmailCredentials;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

/**
 * M10's way out: sends {@link DigestEmail}s from the professor's Gmail account over SMTP (smtp.gmail.com,
 * SSL on port 465), with the same App Password M1 reads the inbox with. Gmail keeps a copy of each email
 * in the account's Sent folder. One {@link Connection} logs in once and sends a whole run's emails.
 */
public final class GmailSmtpSender {

    private static final String GMAIL_SMTP_HOST = "smtp.gmail.com";
    private static final int GMAIL_SMTP_PORT = 465;

    private final String smtpHost;
    private final int smtpPort;
    private final boolean trustAnyServerCertificate;

    /**
     * Input: none.
     * Output: a sender for smtp.gmail.com:465 with full certificate checks.
     * Used by: ReviewPane (M8), ApprovedPaperSender.atDefaultLocations.
     * Example: {@code new GmailSmtpSender().connect(creds)}
     */
    public GmailSmtpSender() {
        this(GMAIL_SMTP_HOST, GMAIL_SMTP_PORT, false);
    }

    /**
     * Visible for tests to point at a local SMTP server (GreenMail) instead of Gmail. trustAnyServerCertificate
     * skips certificate checks, which is only ever appropriate against a test server's self-signed certificate.
     * Input: host, port, and whether to trust any certificate (test servers only).
     * Output: a sender for that server.
     * Used by: the public constructor; tests with GreenMail.
     * Example: {@code new GmailSmtpSender("localhost", 3465, true)}
     */
    GmailSmtpSender(String smtpHost, int smtpPort, boolean trustAnyServerCertificate) {
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.trustAnyServerCertificate = trustAnyServerCertificate;
    }

    /**
     * Logs in to send as the credentials' address.
     * Input: the professor's credentials (not cleared; the password copy is wiped).
     * Output: a logged-in connection; IOException if unreachable or the login is refused.
     * Used by: ApprovedPaperSender.send.
     * Example: wrong App Password -> IOException "Gmail refused the login for prof@gmail.com: check the App Password"
     */
    public Connection connect(GmailCredentials credentials) throws IOException {
        Session session = Session.getInstance(smtpProperties());
        char[] appPassword = credentials.appPassword();
        try {
            Transport transport = session.getTransport("smtps");
            transport.connect(smtpHost, smtpPort, credentials.emailAddress(), new String(appPassword));
            return new Connection(session, transport, credentials.emailAddress());
        } catch (AuthenticationFailedException e) {
            throw new IOException("Gmail refused the login for " + credentials.emailAddress()
                    + ": check the App Password", e);
        } catch (MessagingException e) {
            throw new IOException("Could not connect to " + smtpHost + ":" + smtpPort + ": " + e.getMessage(), e);
        } finally {
            Arrays.fill(appPassword, '\0');
        }
    }

    /** A logged-in SMTP session. */
    public static final class Connection implements AutoCloseable {

        private final Session session;
        private final Transport transport;
        private final String fromAddress;

        /**
         * Input: the mail session, the connected transport and the sender address.
         * Output: the connection.
         * Used by: {@link GmailSmtpSender#connect}.
         * Example: {@code new Connection(session, transport, "prof@gmail.com").fromAddress()} -> "prof@gmail.com"
         */
        private Connection(Session session, Transport transport, String fromAddress) {
            this.session = session;
            this.transport = transport;
            this.fromAddress = fromAddress;
        }

        /**
         * The address the emails are sent from.
         * Input: none.
         * Output: the address the emails are sent from.
         * Used by: {@link #send}.
         * Example: -> "prof@gmail.com"
         */
        public String fromAddress() {
            return fromAddress;
        }

        /**
         * Whether the server is still connected; false after a failure that ended the session.
         * Input: none.
         * Output: whether the server is still connected.
         * Used by: ApprovedPaperSender.send, to decide whether later emails can still go.
         * Example: after the server dropped the session -> false
         */
        public boolean isOpen() {
            return transport.isConnected();
        }

        /**
         * Sends one email from {@link #fromAddress()}, replies going back to it.
         * Input: one digest.
         * Output: none; the email (text + PDF attachments) is sent, Reply-To the professor; IOException on a bad address, unreadable PDF or server failure.
         * Used by: ApprovedPaperSender.send.
         * Example: DigestEmail(alice, [a@x.edu], "LitRoute: 2 new papers for alice", ...) -> delivered, copy in Gmail's Sent
         */
        public void send(DigestEmail email) throws IOException {
            try {
                MimeMessage message = new MimeMessage(session);
                InternetAddress from = new InternetAddress(fromAddress, true);
                message.setFrom(from);
                message.setReplyTo(new InternetAddress[] {from});
                for (String recipient : email.recipients()) {
                    message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
                }
                message.setSubject(email.subject(), "UTF-8");
                message.setSentDate(new Date());
                MimeMultipart content = new MimeMultipart("mixed");
                MimeBodyPart text = new MimeBodyPart();
                text.setText(email.body(), "UTF-8");
                content.addBodyPart(text);
                for (Path pdf : email.attachments()) {
                    MimeBodyPart attachment = new MimeBodyPart();
                    attachment.attachFile(pdf.toFile(), "application/pdf", "base64");
                    content.addBodyPart(attachment);
                }
                message.setContent(content);
                message.saveChanges();
                transport.sendMessage(message, message.getAllRecipients());
            } catch (MessagingException e) {
                throw new IOException("Could not send \"" + email.subject() + "\" to "
                        + String.join(", ", email.recipients()) + ": " + e.getMessage(), e);
            }
        }

        /**
         * Input: none.
         * Output: none; the session is closed (a failed QUIT is ignored).
         * Used by: try-with-resources in ApprovedPaperSender.send.
         * Example: {@code try (Connection c = smtp.connect(creds)) { c.send(email); }} -> closed after the block
         */
        @Override
        public void close() {
            try {
                transport.close();
            } catch (MessagingException e) {
                // Everything was sent already; a failed QUIT changes nothing.
            }
        }
    }

    /**
     * Input: none (this sender's host, port, trust).
     * Output: Jakarta Mail properties for SMTPS with auth and 10 s / 60 s timeouts.
     * Used by: {@link #connect}.
     * Example: -> {mail.smtps.host=smtp.gmail.com, mail.smtps.port=465, mail.smtps.auth=true, ...}
     */
    private Properties smtpProperties() {
        Properties properties = new Properties();
        properties.put("mail.transport.protocol", "smtps");
        properties.put("mail.smtps.host", smtpHost);
        properties.put("mail.smtps.port", String.valueOf(smtpPort));
        properties.put("mail.smtps.auth", "true");
        properties.put("mail.smtps.connectiontimeout", "10000");
        // Uploading a digest's PDFs takes longer than reading the inbox.
        properties.put("mail.smtps.timeout", "60000");
        properties.put("mail.smtps.writetimeout", "60000");
        if (trustAnyServerCertificate) {
            properties.put("mail.smtps.ssl.trust", "*");
            properties.put("mail.smtps.ssl.checkserveridentity", "false");
        }
        return properties;
    }
}
