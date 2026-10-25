package com.litroute.ingestion.email;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.testing.SampleMails;
import com.litroute.testing.SampleMails.Sample;
import java.util.Optional;
import org.junit.Test;

/**
 * M1 AlertClassifier against the six alert emails Prof. Prakash Badal forwarded (see
 * {@link SampleMails}). Every mail here is a real message: a real publisher's alert, forwarded by a
 * real person, with the envelope headers and body Gmail actually delivered.
 *
 * All six reach the inbox from aprakashn@gmail.com rather than from the publisher, so they are the
 * case the classifier exists for - deciding "is this a paper alert" from content alone, with no
 * forwarder allowlist and no publisher pre-registration.
 */
public class AlertClassifierTest {

    private final AlertClassifier classifier = new AlertClassifier();

    /** As each mail actually arrived: the forwarder's address, the "Fwd: ..." subject, the real body. */
    @Test
    public void everySampleAlertIsRecognizedExactlyAsItArrived() {
        for (Sample sample : SampleMails.ALL) {
            assertTrue(sample.file(), classifier.isPublicationAlert(
                    SampleMails.FORWARDER, sample.subject(), Optional.of(sample.plainTextBody())));
        }
    }

    /**
     * The same six bodies, but with a subject carrying no alert wording at all - what happens when a
     * forwarder retitles the mail ("FYI", "have a look"). Recognition then rests entirely on
     * recovering the publisher from the quoted header block, so this is what proves that recovery
     * works against real Gmail forward markup for all five mailing lists.
     */
    @Test
    public void everySampleIsStillRecognizedWhenTheForwarderRetitledIt() {
        for (Sample sample : SampleMails.ALL) {
            assertFalse("precondition: the retitled subject must not match on its own",
                    classifier.isPublicationAlert("labmate@example.edu", "FYI"));
            assertTrue(sample.file() + " (quoted " + sample.quotedFrom() + ")", classifier.isPublicationAlert(
                    "labmate@example.edu", "FYI", Optional.of(sample.plainTextBody())));
        }
    }

    /**
     * An alert rarely stops at one hop. Here each real mail is forwarded on a second time, exactly as
     * Gmail nests it: a new header block naming the previous forwarder, above the original message.
     * Only the innermost block still names the publisher, so a classifier that read just the first
     * block would miss every one of these.
     */
    @Test
    public void everySampleSurvivesBeingForwardedASecondTime() {
        for (Sample sample : SampleMails.ALL) {
            String body = "Worth a look.\n\n"
                    + "---------- Forwarded message ---------\n"
                    + "From: Prakash Singh <" + SampleMails.FORWARDER + ">\n"
                    + "Date: Fri, Sep 4, 2026 at 12:58 PM\n"
                    + "Subject: " + sample.subject() + "\n"
                    + "To: <cs24b027@smail.iitm.ac.in>\n\n"
                    + sample.plainTextBody();

            assertTrue(sample.file(), classifier.isPublicationAlert(
                    "cs24b027@smail.iitm.ac.in", "FYI", Optional.of(body)));
        }
    }

    /**
     * Acceptance is not limited to publishers already on KNOWN_ALERT_SENDER_DOMAINS: a journal never
     * seen before must still be recognized by its subject wording. The subjects are the real ones the
     * six alerts carry; only the sending domain is one the classifier has no knowledge of.
     */
    @Test
    public void realAlertSubjectsAreRecognizedEvenFromAPublisherNotOnTheKnownDomainList() {
        String unknownPublisher = "notifications@a-journal-we-have-never-seen.example";
        for (Sample sample : SampleMails.ALL) {
            String withoutFwdPrefix = sample.subject().replaceFirst("^Fwd: ", "");
            assertTrue(withoutFwdPrefix,
                    classifier.isPublicationAlert(unknownPublisher, withoutFwdPrefix));
        }
    }

    /**
     * Each publisher's own sender is recognized on its own, for alerts that arrive directly: by its
     * domain, or for Google Scholar by its alert address.
     */
    @Test
    public void everySamplesPublisherIsRecognizedByItsSendingDomainOrAddress() {
        for (Sample sample : SampleMails.ALL) {
            assertTrue(sample.quotedFrom(),
                    classifier.isPublicationAlert(sample.quotedFrom(), "a subject matching no keyword"));
        }
    }

    /**
     * google.com also sends sign-in and security alerts, Workspace and Cloud notices and product
     * news. A live run took eight of those for paper alerts, and M2 queued their "Go to your Google
     * Account", "Try demo apps" and "Visit the Help Center" links as papers. Only Scholar's alert
     * address is trusted, directly or quoted in a forward.
     */
    @Test
    public void googleMailOtherThanScholarAlertsIsRejected() {
        assertFalse(classifier.isPublicationAlert("no-reply@accounts.google.com", "Security alert"));
        assertFalse(classifier.isPublicationAlert("no-reply@google.com", "Go to your Google Account"));
        assertFalse(classifier.isPublicationAlert("googlecloud@google.com", "Try demo apps"));
        assertFalse(classifier.isPublicationAlert("workspace-noreply@google.com", "Visit the Help Center"));
        String forwardedSignInAlert = "---------- Forwarded message ---------\n"
                + "From: Google <no-reply@accounts.google.com>\n"
                + "Date: Mon, Oct 19, 2026 at 9:00 PM\n"
                + "Subject: Security alert\n"
                + "To: <student@example.edu>\n\n"
                + "A new sign-in on Linux";
        assertFalse(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: Security alert",
                Optional.of(forwardedSignInAlert)));

        assertTrue(classifier.isPublicationAlert("ScholarAlerts-NoReply@google.com", "a subject matching no keyword"));
    }

    @Test
    public void ordinaryMailIsRejected() {
        assertFalse(classifier.isPublicationAlert("friend@gmail.com", "Dinner tonight?"));
        assertFalse(classifier.isPublicationAlert(null, "Dinner tonight?"));
        assertFalse(classifier.isPublicationAlert("friend@gmail.com", null));
    }

    /**
     * Forwarding does not lower the bar: a forwarded chat about dinner is still not a paper alert, no
     * matter how many hops it went through.
     */
    @Test
    public void aForwardedOrdinaryMailIsNotAnAlertAtAnyDepth() {
        String body = "---------- Forwarded message ---------\n"
                + "From: A Labmate <labmate@example.edu>\n"
                + "Date: Tue, Sep 2, 2026 at 9:00 AM\n"
                + "Subject: Fwd: Dinner tonight?\n"
                + "To: <advisor@gmail.com>\n\n"
                + "---------- Forwarded message ---------\n"
                + "From: A Friend <friend@example.edu>\n"
                + "Date: Tue, Sep 1, 2026 at 4:57 PM\n"
                + "Subject: Dinner tonight?\n"
                + "To: <student@example.edu>\n\n"
                + "Want to grab dinner?";

        assertFalse(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: Fwd: Dinner tonight?", Optional.of(body)));
    }

    /**
     * The 2-argument check is envelope-only by design: M1 uses it before the body has been fetched,
     * and it must not be fooled into thinking it can see one.
     */
    @Test
    public void theTwoArgumentCheckNeverConsultsABody() {
        Sample sample = SampleMails.SPRINGER_RELEVANT;

        assertFalse(classifier.isPublicationAlert("labmate@example.edu", "FYI"));
        assertTrue("the same mail is recognized once the body is offered", classifier.isPublicationAlert(
                "labmate@example.edu", "FYI", Optional.of(sample.plainTextBody())));
    }

    /** With no body to inspect, the 3-argument check must fall back to the envelope and nothing else. */
    @Test
    public void withoutABodyTheThreeArgumentCheckIsJustTheEnvelopeCheck() {
        assertTrue(classifier.isPublicationAlert("no-reply@springeropen.com", "anything", Optional.empty()));
        assertFalse(classifier.isPublicationAlert("friend@gmail.com", "Dinner tonight?", Optional.empty()));
    }

    /**
     * A chain that passed through other mail clients: Apple Mail writes "Begin forwarded message:",
     * Outlook writes "-----Original Message-----" with a "Sent:" line, and each hop may re-quote the
     * ones before it with "&gt;" markers. The publisher's header block is found by its own
     * From/Subject pair, so none of that matters.
     */
    @Test
    public void quotedBlocksFromOtherMailClientsAndBehindQuoteMarkersStillCount() {
        String body = "Begin forwarded message:\n\n"
                + "From: A Labmate <labmate@example.edu>\n"
                + "Date: 2 September 2026 at 09:00\n"
                + "Subject: FW: Your weekly digest\n\n"
                + "> -----Original Message-----\n"
                + "> From: SpringerOpen <no-reply@springeropen.com>\n"
                + "> Sent: Monday, January 27, 2025 2:35 PM\n"
                + "> To: student@example.edu\n"
                + "> Subject: Your weekly digest\n";

        assertTrue(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: FYI", Optional.of(body)));
    }

    /** Extra publisher domains can be trusted without touching the classifier's own list. */
    @Test
    public void extraAllowedSenderDomainsAreHonoured() {
        AlertClassifier withExtra = new AlertClassifier(java.util.Set.of("journals.example.edu"));

        assertTrue(withExtra.isPublicationAlert("alerts@journals.example.edu", "no keyword here"));
        assertFalse(classifier.isPublicationAlert("alerts@journals.example.edu", "no keyword here"));
    }
}
