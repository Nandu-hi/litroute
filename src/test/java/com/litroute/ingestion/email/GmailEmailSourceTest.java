package com.litroute.ingestion.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.security.GmailCredentials;
import com.litroute.testing.SampleMails;
import com.litroute.testing.SampleMails.Sample;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * M1 against an embedded IMAP server (GreenMail) carrying the six real alert emails - the same
 * messages Prof. Badal forwarded, delivered as Gmail delivers them: multipart/alternative with a
 * text part and an HTML part.
 *
 * The server is local because reaching a real Gmail account from a test would need real credentials
 * and a network; what arrives over it is not invented.
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
        emailSource = new GmailEmailSource(new AlertClassifier(), "127.0.0.1", greenMail.getImaps().getPort(), true);
    }

    @After
    public void stopServer() {
        greenMail.stop();
    }

    /** All six, forwarded from Prof. Badal's address exactly as they arrived, must be picked up. */
    @Test
    public void everyOneOfTheSixRealAlertsIsFetched() throws Exception {
        for (Sample sample : SampleMails.ALL) {
            deliver(sample);
        }
        greenMail.waitForIncomingEmail(SampleMails.ALL.size());

        List<RawEmail> fetched = emailSource.fetchNewAlerts(credentials(), Set.of());

        assertEquals(SampleMails.ALL.size(), fetched.size());
        assertEquals(SampleMails.ALL.stream().map(Sample::subject).collect(Collectors.toSet()),
                fetched.stream().map(RawEmail::subject).collect(Collectors.toSet()));
        assertTrue("every one arrived from the forwarder, not the publisher",
                fetched.stream().allMatch(mail -> mail.fromAddress().equals(SampleMails.FORWARDER)));
    }

    /**
     * What M1 hands on has to be usable by M2 without going back to the server. Parsing the fetched
     * message must yield the paper the alert is about - which is the real end of the M1 contract.
     */
    @Test
    public void whatIsFetchedCarriesTheBodyM2NeedsToParseThePaperOutOfIt() throws Exception {
        deliver(SampleMails.SPRINGER_RELEVANT);
        greenMail.waitForIncomingEmail(1);

        RawEmail fetched = emailSource.fetchNewAlerts(credentials(), Set.of()).get(0);

        assertTrue("the HTML part is what M2 parses", fetched.htmlBody().isPresent());
        assertTrue("the text part is what the classifier read", fetched.plainTextBody().isPresent());
        List<PaperCandidate> papers = PaperExtractor.offline().extract(fetched);
        assertEquals(1, papers.size());
        assertEquals("Advancing soil-structure interaction (SSI): a comprehensive review of current "
                + "practices, challenges, and future directions", papers.get(0).title());
    }

    /** M7a exists so a message is processed once; a cached ID must not come back on the next run. */
    @Test
    public void anAlertAlreadyProcessedIsSkippedOnTheNextRun() throws Exception {
        deliver(SampleMails.ASCE);
        greenMail.waitForIncomingEmail(1);

        RawEmail first = emailSource.fetchNewAlerts(credentials(), Set.of()).get(0);
        List<RawEmail> second = emailSource.fetchNewAlerts(credentials(), Set.of(first.messageId()));

        assertTrue("an already-cached message must be skipped", second.isEmpty());
    }

    /**
     * The same alert arriving straight from the publisher rather than through a forwarder: same body,
     * publisher's own envelope, and it must still be fetched.
     */
    @Test
    public void anAlertArrivingDirectlyFromThePublisherIsFetched() throws Exception {
        Sample sample = SampleMails.TANDFONLINE;
        deliver(sample.quotedFrom(), sample.subject().replaceFirst("^Fwd: ", ""), sample);
        greenMail.waitForIncomingEmail(1);

        List<RawEmail> fetched = emailSource.fetchNewAlerts(credentials(), Set.of());

        assertEquals(1, fetched.size());
        assertEquals("alerts@tandfonline.com", fetched.get(0).fromAddress());
    }

    /** Prof. Badal's covering note, with no alert quoted under it, is not an alert. */
    @Test
    public void ordinaryMailFromTheSameForwarderIsIgnored() throws Exception {
        MimeMessage note = message(SampleMails.FORWARDER, "Re: research profiles",
                "I need to send you different research profiles and papers for each of them too. "
                        + "I will do that later today in a separate email.\n\nBest regards,\nPrakash S Badal",
                "<div>I need to send you different research profiles and papers for each of them too.</div>");
        GreenMailUtil.sendMimeMessage(note);
        greenMail.waitForIncomingEmail(1);

        assertTrue(emailSource.fetchNewAlerts(credentials(), Set.of()).isEmpty());
    }

    /**
     * Being a forward is not itself a reason to fetch something: with no alert wording and no quoted
     * publisher, a forward is just mail.
     */
    @Test
    public void aForwardWithNothingAlertLikeInItIsNotFetched() throws Exception {
        MimeMessage forward = message("someone@gmail.com", "Fwd: thought you'd find this useful",
                "---------- Forwarded message ---------\n"
                        + "From: A Friend <friend@example.edu>\n"
                        + "Date: Tue, Sep 1, 2026 at 4:57 PM\n"
                        + "Subject: Dinner tonight?\n"
                        + "To: <someone@gmail.com>\n\nWant to grab dinner?",
                "<div>Want to grab dinner?</div>");
        GreenMailUtil.sendMimeMessage(forward);
        greenMail.waitForIncomingEmail(1);

        assertTrue(emailSource.fetchNewAlerts(credentials(), Set.of()).isEmpty());
    }

    /** A wrong app password must not take the whole run down with it. */
    @Test
    public void rejectedCredentialsGiveAnEmptyListRatherThanAnException() {
        GmailCredentials wrong = new GmailCredentials(TEST_USER, "wrong-password".toCharArray());

        assertTrue(emailSource.fetchNewAlerts(wrong, Set.of()).isEmpty());
    }

    /** Nor must an unreachable server. */
    @Test
    public void anUnreachableServerGivesAnEmptyListRatherThanAnException() {
        GmailEmailSource unreachable = new GmailEmailSource(new AlertClassifier(), "127.0.0.1", 1, true);

        assertTrue(unreachable.fetchNewAlerts(credentials(), Set.of()).isEmpty());
    }

    // ---- delivery helpers: Gmail sends multipart/alternative, so that is what arrives here too

    private void deliver(Sample sample) throws Exception {
        deliver(SampleMails.FORWARDER, sample.subject(), sample);
    }

    private void deliver(String from, String subject, Sample sample) throws Exception {
        RawEmail source = sample.email();
        GreenMailUtil.sendMimeMessage(
                message(from, subject, sample.plainTextBody(), source.htmlBody().orElseThrow()));
    }

    private MimeMessage message(String from, String subject, String plainText, String html) throws Exception {
        Session session = greenMail.getSmtp().createSession();
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(TEST_USER));
        message.setSubject(subject, "UTF-8");

        MimeBodyPart text = new MimeBodyPart();
        text.setText(plainText, "UTF-8");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=UTF-8");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(text);
        alternative.addBodyPart(htmlPart);
        message.setContent(alternative);
        message.saveChanges();
        return message;
    }

    private GmailCredentials credentials() {
        return new GmailCredentials(TEST_USER, TEST_PASSWORD.toCharArray());
    }
}
