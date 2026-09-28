package com.litroute.ingestion.email;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import org.junit.Test;

public class AlertClassifierTest {

    private final AlertClassifier classifier = new AlertClassifier();

    @Test
    public void recognizesKnownAlertSenderDomain() {
        assertTrue(classifier.isPublicationAlert("scholaralerts-noreply@google.com", "Random subject"));
        assertTrue(classifier.isPublicationAlert("no-reply@arxiv.org", "daily digest"));
    }

    /**
     * Confirmed against six real alert emails the client shared, one per mailing list: this
     * classifier only needs to recognize each mailing list's own sending domain when the alert
     * arrives directly in the inbox - no forwarding-related logic is involved.
     */
    @Test
    public void recognizesEveryMailingListDomainFromTheClientsReferenceEmails() {
        assertTrue(classifier.isPublicationAlert("no-reply@springeropen.com", "New Articles From Some Journal"));
        assertTrue(classifier.isPublicationAlert("alerts@tandfonline.com", "New articles for Some Journal are now available online"));
        assertTrue(classifier.isPublicationAlert("scholaralerts-noreply@google.com", "Recommended articles"));
        assertTrue(classifier.isPublicationAlert("sciencedirect@notification.elsevier.com", "New recommendations available on ScienceDirect"));
        assertTrue(classifier.isPublicationAlert("ascelibrary@asce.org", "New articles have been published Some Journal"));
    }

    @Test
    public void recognizesSubjectKeywordFromUnknownSender() {
        assertTrue(classifier.isPublicationAlert("editor@some-new-journal.example", "New citations to your articles"));
    }

    /**
     * Acceptance isn't limited to KNOWN_ALERT_SENDER_DOMAINS: a publisher never seen before,
     * sending mail with common alert phrasing, should still be recognized as a paper alert.
     */
    @Test
    public void recognizesCommonAlertPhrasingFromAPublisherNotOnTheKnownDomainList() {
        String unknownPublisher = "notifications@a-brand-new-open-access-publisher.example";
        assertTrue(classifier.isPublicationAlert(unknownPublisher, "New Issue Alert: Journal of Something, Volume 12"));
        assertTrue(classifier.isPublicationAlert(unknownPublisher, "Your Saved Search Alert Results"));
        assertTrue(classifier.isPublicationAlert(unknownPublisher, "New papers matching your interests"));
        assertTrue(classifier.isPublicationAlert(unknownPublisher, "Early Access: your article is now online"));
        assertTrue(classifier.isPublicationAlert(unknownPublisher, "The Latest Issue of Journal X is now available online"));
    }

    @Test
    public void rejectsOrdinaryMail() {
        assertFalse(classifier.isPublicationAlert("friend@gmail.com", "Dinner tonight?"));
    }

    @Test
    public void rejectsMissingFromAddress() {
        assertFalse(classifier.isPublicationAlert(null, "Dinner tonight?"));
    }

    /**
     * The plain 2-arg check has no forwarding awareness at all: an outer subject/sender that
     * doesn't match is rejected on its own, regardless of what the body contains. Forwarding
     * support only exists on the 3-arg overload below, which does look at the body.
     */
    @Test
    public void twoArgCheckDoesNotLookAtBodyAtAll() {
        assertFalse(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: thought you'd find this useful"));
    }

    /**
     * The client's reference forwards (see AlertClassifier's javadoc) all have the same shape:
     * Gmail's own "---------- Forwarded message ---------" marker followed by the quoted
     * original From/Date/Subject/To headers. This reproduces that shape verbatim from one of
     * the six samples (SpringerOpen), to prove a forwarded alert is fetched based purely on
     * whether it looks like a paper alert, not on who forwarded it.
     */
    @Test
    public void recognizesForwardedAlertByItsQuotedOriginalHeaders() {
        String body = "This one, on the other hand, published in the same journal is highly relevant.\n\n"
                + "Best regards,\nSome Advisor\n\n"
                + "---------- Forwarded message ---------\n"
                + "From: SpringerOpen <no-reply@springeropen.com>\n"
                + "Date: Mon, Jan 27, 2025 at 2:35 PM\n"
                + "Subject: New Articles From Journal of Infrastructure Preservation and Resilience\n"
                + "To: <someone@example.com>\n\n"
                + "The following new articles have just been published...";

        assertTrue(classifier.isPublicationAlert(
                "advisor@gmail.com", "Fwd: New Articles From Journal of Infrastructure Preservation and Resilience",
                Optional.of(body)));
    }

    @Test
    public void recognizesForwardedAlertWhenOnlyTheQuotedSubjectMatchesAKeyword() {
        String body = "---------- Forwarded message ---------\n"
                + "From: Google Scholar Alerts <scholaralerts-noreply@google.com>\n"
                + "Date: Tue, Sep 1, 2026 at 4:57 PM\n"
                + "Subject: Recommended articles\n"
                + "To: <someone@example.com>\n\n"
                + "[PDF] An Intensity Measure-Based Methodology for Ground Motion Orientation...";

        // Outer subject "Fwd: FYI" wouldn't match anything on its own - only the quoted
        // original sender's known domain should catch this.
        assertTrue(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: FYI", Optional.of(body)));
    }

    @Test
    public void doesNotTreatAnOrdinaryForwardOfNonAlertMailAsAnAlert() {
        String body = "---------- Forwarded message ---------\n"
                + "From: A Friend <friend@gmail.com>\n"
                + "Date: Tue, Sep 1, 2026 at 4:57 PM\n"
                + "Subject: Dinner tonight?\n"
                + "To: <someone@example.com>\n\n"
                + "Want to grab dinner?";

        assertFalse(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: Dinner tonight?", Optional.of(body)));
    }

    /**
     * An alert rarely arrives one hop from the publisher: a student forwards it to a labmate, who
     * forwards it on again. Only the innermost block still names the publisher, so checking just
     * the outermost one (the previous forwarder) would drop the alert. The subject here matches no
     * keyword on purpose - springeropen.com is the only thing identifying it.
     */
    @Test
    public void recognizesAnAlertForwardedSeveralTimesByReadingEveryQuotedBlockNotJustTheFirst() {
        String body = "Passing this along.\n\n"
                + "---------- Forwarded message ---------\n"
                + "From: A Labmate <labmate@example.edu>\n"
                + "Date: Tue, Sep 2, 2026 at 9:00 AM\n"
                + "Subject: Fwd: Your weekly digest\n"
                + "To: <advisor@gmail.com>\n\n"
                + "FYI\n\n"
                + "---------- Forwarded message ---------\n"
                + "From: SpringerOpen <no-reply@springeropen.com>\n"
                + "Date: Mon, Jan 27, 2025 at 2:35 PM\n"
                + "Subject: Your weekly digest\n"
                + "To: <student@example.edu>\n\n"
                + "The following new articles have just been published...";

        assertTrue(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: Fwd: Your weekly digest", Optional.of(body)));
    }

    /** Depth isn't capped: four hops is no different from one. */
    @Test
    public void recognizesAnAlertAfterFourHops() {
        StringBuilder body = new StringBuilder("Thought you'd want to see this.\n\n");
        for (int hop = 3; hop >= 1; hop--) {
            body.append("---------- Forwarded message ---------\n")
                    .append("From: Person ").append(hop).append(" <person").append(hop).append("@example.edu>\n")
                    .append("Date: Tue, Sep 2, 2026 at 9:0").append(hop).append(" AM\n")
                    .append("Subject: Your weekly digest\n")
                    .append("To: <person").append(hop + 1).append("@example.edu>\n\n");
        }
        body.append("---------- Forwarded message ---------\n")
                .append("From: SpringerOpen <no-reply@springeropen.com>\n")
                .append("Date: Mon, Jan 27, 2025 at 2:35 PM\n")
                .append("Subject: Your weekly digest\n")
                .append("To: <person1@example.edu>\n\n")
                .append("The following new articles have just been published...");

        assertTrue(classifier.isPublicationAlert("person4@example.edu", "Fwd: Your weekly digest",
                Optional.of(body.toString())));
    }

    /**
     * A long chain usually passes through more than one mail client, and each hop may re-quote the
     * ones before it with "&gt;" markers. Blocks are found by their From/Subject header pair, so none
     * of that matters.
     */
    @Test
    public void recognizesQuotedBlocksFromOtherMailClientsAndBehindQuoteMarkers() {
        String appleThenOutlook = "Begin forwarded message:\n\n"
                + "From: A Labmate <labmate@example.edu>\n"
                + "Date: 2 September 2026 at 09:00\n"
                + "Subject: FW: Your weekly digest\n\n"
                + "> -----Original Message-----\n"
                + "> From: SpringerOpen <no-reply@springeropen.com>\n"
                + "> Sent: Monday, January 27, 2025 2:35 PM\n"
                + "> To: student@example.edu\n"
                + "> Subject: Your weekly digest\n";

        assertTrue(classifier.isPublicationAlert("advisor@gmail.com", "Fwd: FYI", Optional.of(appleThenOutlook)));
    }

    /** Depth doesn't make ordinary mail an alert either - no block in the chain looks like one. */
    @Test
    public void doesNotTreatARepeatedlyForwardedOrdinaryMailAsAnAlert() {
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

    @Test
    public void threeArgCheckFallsBackToEnvelopeCheckWhenNoQuotedHeaderBlockIsPresent() {
        assertTrue(classifier.isPublicationAlert("no-reply@arxiv.org", "daily digest", Optional.of("Just a plain body, no forward here.")));
        assertFalse(classifier.isPublicationAlert("friend@gmail.com", "Dinner tonight?", Optional.empty()));
    }
}
