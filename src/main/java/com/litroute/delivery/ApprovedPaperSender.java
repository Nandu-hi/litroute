package com.litroute.delivery;

import com.litroute.model.Profile;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.profile.ProfileBuilder;
import com.litroute.security.GmailCredentials;
import com.litroute.store.ProfileStore;
import com.litroute.store.ScoredQueueStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * M10: emails every approved paper in the review queue (M7e) to its student, at the addresses in the
 * student's profile (M7d, read from the folder's {@value ProfileBuilder#EMAIL_FILE}). Each student gets
 * one email listing their papers with the PDFs attached, or several when the PDFs are too large for one
 * (see {@link DigestComposer}).
 *
 * A paper is marked {@link ReviewStatus#SENT} as soon as the email carrying it has gone, so a crash or a
 * failure part-way leaves the rest approved for the next send; a crash between sending and marking sends
 * that one email again rather than losing it. Papers of a student with no address stay approved until
 * one is added.
 */
public final class ApprovedPaperSender {

    /**
     * What a send did.
     *
     * @param emails      emails sent
     * @param papers      papers sent (and marked sent)
     * @param attachments PDFs attached across those emails
     * @param unaddressed students whose approved papers could not be sent for want of an address, each
     *                    with how many papers wait
     * @param failures    emails that could not be sent, each with the reason; their papers stay approved
     */
    public record Report(int emails, int papers, int attachments, List<String> unaddressed, List<String> failures) {

        public Report {
            unaddressed = List.copyOf(unaddressed);
            failures = List.copyOf(failures);
        }
    }

    private final ScoredQueueStore queue;
    private final ProfileStore profiles;
    private final GmailSmtpSender smtp;
    private final long attachmentBudget;

    public ApprovedPaperSender(ScoredQueueStore queue, ProfileStore profiles, GmailSmtpSender smtp) {
        this(queue, profiles, smtp, DigestComposer.GMAIL_ATTACHMENT_BUDGET);
    }

    /** As above, attaching at most {@code attachmentBudget} bytes of PDF per email. */
    ApprovedPaperSender(ScoredQueueStore queue, ProfileStore profiles, GmailSmtpSender smtp, long attachmentBudget) {
        this.queue = queue;
        this.profiles = profiles;
        this.smtp = smtp;
        this.attachmentBudget = attachmentBudget;
    }

    /** The review queue and profiles under {@code ~/.litroute/data}, sent through Gmail. */
    public static ApprovedPaperSender atDefaultLocations() {
        return new ApprovedPaperSender(ScoredQueueStore.atDefaultLocation(), ProfileStore.atDefaultLocation(),
                new GmailSmtpSender());
    }

    /**
     * Sends every approved paper in the queue.
     *
     * @param credentials the professor's Gmail account, which the emails come from; not cleared
     * @param progress    told what is being sent, as it happens
     * @throws IOException if a store cannot be read or written, or Gmail cannot be reached, refuses the login
     *         or drops the connection; the emails already sent stay marked sent
     */
    public Report send(GmailCredentials credentials, Consumer<String> progress) throws IOException {
        Map<String, List<ScoredPaper>> approved = new LinkedHashMap<>();
        for (ScoredPaper paper : queue.load()) {
            if (paper.status() == ReviewStatus.APPROVED) {
                approved.computeIfAbsent(paper.profileName(), name -> new ArrayList<>()).add(paper);
            }
        }
        if (approved.isEmpty()) {
            progress.accept("No approved papers waiting to be sent.");
            return new Report(0, 0, 0, List.of(), List.of());
        }

        Map<String, Profile> students = profiles.loadAll();
        List<DigestEmail> outgoing = new ArrayList<>();
        List<String> unaddressed = new ArrayList<>();
        for (Map.Entry<String, List<ScoredPaper>> entry : approved.entrySet()) {
            Profile student = students.get(entry.getKey());
            if (student == null || student.emails().isEmpty()) {
                unaddressed.add(entry.getKey() + " (" + entry.getValue().size() + " paper(s))");
                progress.accept("Not sending " + entry.getValue().size() + " paper(s) for " + entry.getKey()
                        + ": add their address to " + ProfileBuilder.EMAIL_FILE + " in the profile folder and "
                        + "rebuild the profiles.");
            } else {
                outgoing.addAll(DigestComposer.compose(entry.getKey(), student.emails(), credentials.emailAddress(),
                        entry.getValue(), attachmentBudget));
            }
        }
        if (outgoing.isEmpty()) {
            return new Report(0, 0, 0, unaddressed, List.of());
        }

        progress.accept("Sending " + outgoing.size() + " email(s) from " + credentials.emailAddress() + "...");
        int emails = 0;
        int papers = 0;
        int attachments = 0;
        List<String> failures = new ArrayList<>();
        try (GmailSmtpSender.Connection connection = smtp.connect(credentials)) {
            for (DigestEmail email : outgoing) {
                try {
                    connection.send(email);
                } catch (IOException e) {
                    if (!connection.isOpen()) {
                        throw e;
                    }
                    failures.add(e.getMessage());
                    progress.accept("  " + e.getMessage());
                    continue;
                }
                for (ScoredPaper paper : email.papers()) {
                    queue.setStatus(paper, ReviewStatus.SENT);
                }
                emails++;
                papers += email.papers().size();
                attachments += email.attachments().size();
                progress.accept("  " + email.profileName() + " (" + String.join(", ", email.recipients()) + "): "
                        + email.papers().size() + " paper(s), " + email.attachments().size() + " PDF(s) attached");
            }
        }
        return new Report(emails, papers, attachments, unaddressed, failures);
    }
}
