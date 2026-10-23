package com.litroute.delivery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.litroute.delivery.ApprovedPaperSender.Report;
import com.litroute.model.Profile;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.security.GmailCredentials;
import com.litroute.store.Candidates;
import com.litroute.store.ProfileStore;
import com.litroute.store.ScoredQueueStore;
import com.litroute.testing.TestProfiles;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * M10 against an embedded SMTP server (GreenMail), sending real papers with their recorded PDFs to two of
 * the students' real profiles: one given an address, one without. The server is local because sending
 * through a real Gmail account from a test would need real credentials and would mail real people.
 */
public class ApprovedPaperSenderTest {

    private static final String PROFESSOR = "professor@example.com";
    private static final String PASSWORD = "app-password";
    private static final String STUDENT_ADDRESS = "student@example.com";

    private GreenMail greenMail;
    private Path data;
    private ScoredQueueStore queue;
    private ProfileStore profiles;
    private String addressed;
    private String unaddressed;
    private final List<String> progress = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        TestProfiles.assumeAvailable();
        greenMail = new GreenMail(ServerSetup.SMTPS.dynamicPort());
        greenMail.start();
        greenMail.setUser(PROFESSOR, PASSWORD);

        data = Files.createTempDirectory("litroute-delivery");
        queue = new ScoredQueueStore(data.resolve("scored_queue.json"), data.resolve("matched"));
        profiles = new ProfileStore(data.resolve("profiles.json"));
        Iterator<Profile> real = TestProfiles.profiles().values().iterator();
        Profile first = real.next();
        Profile second = real.next();
        profiles.save(new Profile(first.name(), List.of(STUDENT_ADDRESS), first.papers(), first.keywords(),
                first.modelId(), first.updatedAt()));
        profiles.save(new Profile(second.name(), List.of(), second.papers(), second.keywords(), second.modelId(),
                second.updatedAt()));
        addressed = first.name();
        unaddressed = second.name();
    }

    @After
    public void stopServer() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    private ApprovedPaperSender sender(long attachmentBudget) {
        return new ApprovedPaperSender(queue, profiles,
                new GmailSmtpSender("127.0.0.1", greenMail.getSmtps().getPort(), true), attachmentBudget);
    }

    private ApprovedPaperSender sender() {
        return sender(DigestComposer.GMAIL_ATTACHMENT_BUDGET);
    }

    private static GmailCredentials professor() {
        return new GmailCredentials(PROFESSOR, PASSWORD.toCharArray());
    }

    private Path recordedPdf(String recorded, String savedAs) throws IOException {
        Path pdf = data.resolve("pdfs").resolve(savedAs);
        Files.createDirectories(pdf.getParent());
        return Files.copy(Path.of("src/test/resources/http", recorded), pdf);
    }

    /** Queues the two real papers (with PDFs) for a student and approves them; returns them as queued. */
    private List<ScoredPaper> approveTwoPapersFor(String student) throws IOException {
        ScoredPaper facades = new ScoredPaper(Candidates.full().withPdfPath(Optional.of(
                recordedPdf("e502fdcc276fe9dbf272.pdf", "10.1186_s43065-024-00115-x.pdf"))), student, 0.7, "...",
                ReviewStatus.PENDING);
        ScoredPaper ssi = new ScoredPaper(Candidates.ssiReview(
                recordedPdf("8d0d08f9b152568722f5.pdf", "10.1186_s43065-025-00118-2.pdf")), student, 0.6, "...",
                ReviewStatus.PENDING, true);
        queue.addAll(Map.of(student, List.of(facades, ssi)));
        List<ScoredPaper> queued = queue.load();
        for (ScoredPaper paper : queued) {
            assertTrue(queue.setStatus(paper, ReviewStatus.APPROVED));
        }
        return queued;
    }

    private ReviewStatus statusOf(ScoredPaper paper) throws IOException {
        return queue.load().stream().filter(p -> p.queueKey().equals(paper.queueKey())).findFirst().orElseThrow().status();
    }

    private static List<BodyPart> attachments(MimeMessage message) throws Exception {
        List<BodyPart> found = new ArrayList<>();
        Multipart parts = (Multipart) message.getContent();
        for (int i = 0; i < parts.getCount(); i++) {
            if (Part.ATTACHMENT.equalsIgnoreCase(parts.getBodyPart(i).getDisposition())) {
                found.add(parts.getBodyPart(i));
            }
        }
        return found;
    }

    @Test
    public void approvedPapersReachTheirStudentWithTheirPdfsAndAreMarkedSent() throws Exception {
        List<ScoredPaper> approved = approveTwoPapersFor(addressed);
        ScoredPaper pending = new ScoredPaper(Candidates.sparse(), addressed, 0.3, "...", ReviewStatus.PENDING);
        queue.addAll(List.of(pending));

        Report report = sender().send(professor(), progress::add);

        assertEquals(new Report(1, 2, 2, List.of(), List.of()), report);
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        MimeMessage email = received[0];
        assertEquals("LitRoute: 2 new papers for " + addressed, email.getSubject());
        assertEquals(PROFESSOR, email.getFrom()[0].toString());
        assertEquals(List.of(STUDENT_ADDRESS), Arrays.stream(email.getRecipients(Message.RecipientType.TO))
                .map(Address::toString).toList());
        List<BodyPart> pdfs = attachments(email);
        assertEquals(2, pdfs.size());
        for (int i = 0; i < 2; i++) {
            // the recommended SSI review leads, as it does in the review table
            Path filed = approved.get(1 - i).candidate().pdfPath().orElseThrow();
            assertEquals(filed.getFileName().toString(), pdfs.get(i).getFileName());
            assertArrayEquals(Files.readAllBytes(filed), pdfs.get(i).getInputStream().readAllBytes());
        }
        for (ScoredPaper paper : approved) {
            assertEquals(ReviewStatus.SENT, statusOf(paper));
        }
        assertEquals("only approved papers are sent", ReviewStatus.PENDING, statusOf(pending));
    }

    @Test
    public void aSecondSendFindsNothingLeftToSend() throws Exception {
        approveTwoPapersFor(addressed);
        sender().send(professor(), progress::add);

        Report again = sender().send(professor(), progress::add);

        assertEquals(new Report(0, 0, 0, List.of(), List.of()), again);
        assertEquals(1, greenMail.getReceivedMessages().length);
        assertTrue(progress.contains("No approved papers waiting to be sent."));
    }

    @Test
    public void aStudentWithoutAnAddressKeepsTheirPapersApproved() throws Exception {
        List<ScoredPaper> approved = approveTwoPapersFor(unaddressed);

        Report report = sender().send(professor(), progress::add);

        assertEquals(new Report(0, 0, 0, List.of(unaddressed + " (2 paper(s))"), List.of()), report);
        assertEquals(0, greenMail.getReceivedMessages().length);
        for (ScoredPaper paper : approved) {
            assertEquals(ReviewStatus.APPROVED, statusOf(paper));
        }
    }

    @Test
    public void pdfsTooLargeForOneEmailAreSentInSeveral() throws Exception {
        List<ScoredPaper> approved = approveTwoPapersFor(addressed);
        long onePdf = Files.size(approved.get(0).candidate().pdfPath().orElseThrow());

        Report report = sender(onePdf + 100).send(professor(), progress::add);

        assertEquals(new Report(2, 2, 2, List.of(), List.of()), report);
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(2, received.length);
        for (MimeMessage email : received) {
            assertTrue(email.getSubject(), email.getSubject().matches("LitRoute: 1 new paper for .+ \\(part [12] of 2\\)"));
            assertEquals(1, attachments(email).size());
        }
        for (ScoredPaper paper : approved) {
            assertEquals(ReviewStatus.SENT, statusOf(paper));
        }
    }

    @Test
    public void aRefusedLoginSendsNothingAndKeepsThePapersApproved() throws Exception {
        List<ScoredPaper> approved = approveTwoPapersFor(addressed);

        try {
            sender().send(new GmailCredentials(PROFESSOR, "wrong-password".toCharArray()), progress::add);
            fail("a refused login should reach the caller");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("refused the login"));
        }
        assertEquals(0, greenMail.getReceivedMessages().length);
        for (ScoredPaper paper : approved) {
            assertEquals(ReviewStatus.APPROVED, statusOf(paper));
        }
    }
}
