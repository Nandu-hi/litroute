package com.litroute.ingestion.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.litroute.model.RawEmail;
import com.litroute.security.GmailCredentials;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises GmailEmailSource against an embedded fake IMAP/SMTP server
 * (GreenMail) instead of a real Gmail account, per the M1 test plan:
 * successful retrieval + cache-based skipping, and graceful failure on a
 * bad connection.
 */
public class GmailEmailSourceTest {

    private static final String TEST_USER = "student@example.com";
    private static final String TEST_PASSWORD = "app-password";

    private GreenMail greenMail;
    private GmailEmailSource emailSource;

    @Before
    public void startServer() {
        greenMail = new GreenMail(new ServerSetup[] {ServerSetup.SMTP.dynamicPort(), ServerSetup.IMAPS.dynamicPort()});
        greenMail.start();
        greenMail.setUser(TEST_USER, TEST_PASSWORD);
        emailSource = testEmailSource();
    }

    @After
    public void stopServer() {
        greenMail.stop();
    }

    @Test
    public void fetchesNewAlertAndThenSkipsItOnceCached() throws Exception {
        sendMail("scholaralerts-noreply@google.com", TEST_USER, "New citations to your articles",
                "Someone cited your paper.");
        greenMail.waitForIncomingEmail(1);

        List<RawEmail> firstRun = emailSource.fetchNewAlerts(testCredentials(), Set.of());
        assertEquals(1, firstRun.size());
        RawEmail alert = firstRun.get(0);
        assertEquals("New citations to your articles", alert.subject());
        assertTrue(alert.plainTextBody().isPresent());

        List<RawEmail> secondRun = emailSource.fetchNewAlerts(testCredentials(), Set.of(alert.messageId()));
        assertTrue("already-cached message should be skipped", secondRun.isEmpty());
    }

    @Test
    public void ignoresMailThatDoesNotLookLikeAPublicationAlert() throws Exception {
        sendMail("friend@example.com", TEST_USER, "Dinner tonight?", "<p>Are you free?</p>");
        greenMail.waitForIncomingEmail(1);

        List<RawEmail> result = emailSource.fetchNewAlerts(testCredentials(), Set.of());

        assertTrue(result.isEmpty());
    }

    @Test
    public void fetchesAlertArrivingDirectlyFromAKnownPublisherMailingList() throws Exception {
        sendMail("no-reply@springeropen.com", TEST_USER, "Some Journal Table of Contents Alert", "See attached.");
        greenMail.waitForIncomingEmail(1);

        List<RawEmail> result = emailSource.fetchNewAlerts(testCredentials(), Set.of());

        assertEquals(1, result.size());
        assertEquals("no-reply@springeropen.com", result.get(0).fromAddress());
    }

    /**
     * A forward with no recognizable "Forwarded message" header block and a subject that
     * matches no known keyword isn't accepted just because it's a forward - there's nothing
     * in it that looks like a paper alert.
     */
    @Test
    public void doesNotFetchAForwardWithNoMatchingKeywordOrDomainJustBecauseItIsAForward() throws Exception {
        sendMail("someone@gmail.com", TEST_USER, "Fwd: thought you'd find this useful", "Some completely generic forward.");
        greenMail.waitForIncomingEmail(1);

        List<RawEmail> result = emailSource.fetchNewAlerts(testCredentials(), Set.of());

        assertTrue(result.isEmpty());
    }

    /**
     * Proves the forwarded case works end to end: a forward from a personal Gmail address,
     * with a generic outer subject, is still fetched because the body contains Gmail's own
     * quoted "Forwarded message" header block naming a known publisher as the original sender.
     */
    @Test
    public void fetchesForwardedAlertRecognizedByItsQuotedOriginalHeaders() throws Exception {
        String forwardedBody = "FYI, thought this might be useful.\n\n"
                + "---------- Forwarded message ---------\n"
                + "From: SpringerOpen <no-reply@springeropen.com>\n"
                + "Date: Mon, Jan 27, 2025 at 2:35 PM\n"
                + "Subject: New Articles From Some Journal\n"
                + "To: <advisor@example.com>\n\n"
                + "The following new articles have just been published...";
        sendMail("advisor@gmail.com", TEST_USER, "Fwd: New Articles From Some Journal", forwardedBody);
        greenMail.waitForIncomingEmail(1);

        List<RawEmail> result = emailSource.fetchNewAlerts(testCredentials(), Set.of());

        assertEquals(1, result.size());
        assertEquals("advisor@gmail.com", result.get(0).fromAddress());
    }

    @Test
    public void returnsEmptyListInsteadOfThrowingWhenCredentialsAreRejected() {
        GmailCredentials wrongCredentials = new GmailCredentials(TEST_USER, "wrong-password".toCharArray());

        List<RawEmail> result = emailSource.fetchNewAlerts(wrongCredentials, Set.of());

        assertTrue(result.isEmpty());
    }

    @Test
    public void returnsEmptyListInsteadOfThrowingWhenServerIsUnreachable() {
        GmailEmailSource unreachable = new GmailEmailSource(new AlertClassifier(), "127.0.0.1", 1, true);

        List<RawEmail> result = unreachable.fetchNewAlerts(testCredentials(), Set.of());

        assertTrue(result.isEmpty());
    }

    private void sendMail(String from, String to, String subject, String htmlBody) {
        GreenMailUtil.sendTextEmail(to, from, subject, htmlBody, greenMail.getSmtp().getServerSetup());
    }

    private GmailCredentials testCredentials() {
        return new GmailCredentials(TEST_USER, TEST_PASSWORD.toCharArray());
    }

    private GmailEmailSource testEmailSource() {
        return new GmailEmailSource(new AlertClassifier(), "127.0.0.1", greenMail.getImaps().getPort(), true);
    }
}
