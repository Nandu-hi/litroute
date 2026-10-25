package com.litroute.delivery;

import com.litroute.model.Profile;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.profile.ProfileBuilder;
import com.litroute.security.GmailCredentials;
import com.litroute.store.ProfileStore;
import com.litroute.store.ScoredQueueStore;
import com.litroute.store.SentPaperStore;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * M10: emails every approved paper in the review queue (M7e) to its student, at the addresses in the
 * student's profile (M7d, read from the folder's {@value ProfileBuilder#EMAIL_FILE}). Each student gets
 * one email listing their papers with the PDFs attached, or several when the PDFs are too large for one
 * (see {@link DigestComposer}).
 *
 * As soon as the email carrying a paper has gone, the paper is recorded in the sent papers
 * ({@link SentPaperStore}) and then taken out of the queue, so a crash or a failure part-way leaves the
 * rest approved for the next send. Recorded first: a crash between the two leaves the paper in both, and
 * the next send drops it from the queue before looking for approved papers, so it is never sent twice;
 * a crash between sending and recording sends that one email again rather than losing it. Papers of a
 * student with no address stay approved until one is added.
 */
public final class ApprovedPaperSender {

    /**
     * What a send did.
     *
     * @param emails      emails sent
     * @param papers      papers sent (and moved from the queue to the sent papers)
     * @param attachments PDFs attached across those emails
     * @param unaddressed students whose approved papers could not be sent for want of an address, each
     *                    with how many papers wait
     * @param failures    emails that could not be sent, each with the reason; their papers stay approved
     */
    public record Report(int emails, int papers, int attachments, List<String> unaddressed, List<String> failures) {

        /**
         * Input: what a send did.
         * Output: the report, with immutable copies of its lists.
         * Used by: {@link #send}.
         * Example: {@code new Report(2, 5, 3, List.of(), List.of())}
         */
        public Report {
            unaddressed = List.copyOf(unaddressed);
            failures = List.copyOf(failures);
        }
    }

    private final ScoredQueueStore queue;
    private final SentPaperStore sent;
    private final ProfileStore profiles;
    private final GmailSmtpSender smtp;
    private final long attachmentBudget;

    /**
     * Input: the M7e queue, the sent-papers store, the M7d profiles and the SMTP sender.
     * Output: a sender with Gmail's attachment budget.
     * Used by: ReviewPane (M8) Send approved, {@link #atDefaultLocations}.
     * Example: {@code new ApprovedPaperSender(queue, sent, ProfileStore.atDefaultLocation(), new GmailSmtpSender())}
     */
    public ApprovedPaperSender(ScoredQueueStore queue, SentPaperStore sent, ProfileStore profiles, GmailSmtpSender smtp) {
        this(queue, sent, profiles, smtp, DigestComposer.GMAIL_ATTACHMENT_BUDGET);
    }

    /**
     * As above, attaching at most {@code attachmentBudget} bytes of PDF per email.
     * Input: as above, plus the PDF bytes per email.
     * Output: a sender with that attachment budget.
     * Used by: the public constructor; ApprovedPaperSenderTest (small budget to force parts).
     * Example: attachmentBudget 1 MB -> big digests split into several emails
     */
    ApprovedPaperSender(ScoredQueueStore queue, SentPaperStore sent, ProfileStore profiles, GmailSmtpSender smtp,
            long attachmentBudget) {
        this.queue = queue;
        this.sent = sent;
        this.profiles = profiles;
        this.smtp = smtp;
        this.attachmentBudget = attachmentBudget;
    }

    /**
     * The review queue and profiles under {@code ~/.litroute/data}, sent through Gmail.
     * Input: none.
     * Output: a sender over the default stores in ~/.litroute/data, sending through Gmail.
     * Used by: PipelineRunnerCli --send-approved.
     * Example: {@code atDefaultLocations().send(creds, System.out::println)}
     */
    public static ApprovedPaperSender atDefaultLocations() {
        return new ApprovedPaperSender(ScoredQueueStore.atDefaultLocation(), SentPaperStore.atDefaultLocation(),
                ProfileStore.atDefaultLocation(), new GmailSmtpSender());
    }

    /**
     * Sends every approved paper in the queue.
     * Input: the professor's Gmail credentials (not cleared) and a progress sink.
     * Output: what was sent; each sent paper recorded in SentPaperStore then removed from M7e; IOException if a store or Gmail fails.
     * Used by: ReviewPane (M8), PipelineRunnerCli.
     * Example: alice 2 approved (has email), bob 1 approved (no email) -> Report(1 email, 2 papers, ..., unaddressed [bob (1 paper(s))])
     */
    public Report send(GmailCredentials credentials, Consumer<String> progress) throws IOException {
        settle();
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
                sent.addAll(email.papers(), Instant.now());
                queue.remove(email.papers());
                emails++;
                papers += email.papers().size();
                attachments += email.attachments().size();
                progress.accept("  " + email.profileName() + " (" + String.join(", ", email.recipients()) + "): "
                        + email.papers().size() + " paper(s), " + email.attachments().size() + " PDF(s) attached");
            }
        }
        return new Report(emails, papers, attachments, unaddressed, failures);
    }

    /**
     * Takes out of the queue what has already gone: entries recorded as sent (left there by a crash
     * between recording and removing) and entries marked {@link ReviewStatus#SENT} by older versions,
     * which kept sent papers in the queue; the latter are recorded as sent first, at the time of this move.
     * Input: none.
     * Output: none; queue entries already sent (or marked SENT by older versions) are recorded and taken out of M7e.
     * Used by: {@link #send}, first.
     * Example: crash after sent.addAll but before queue.remove -> the paper is removed now, never sent twice
     */
    private void settle() throws IOException {
        List<ScoredPaper> queued = queue.load();
        List<ScoredPaper> markedSent = queued.stream().filter(paper -> paper.status() == ReviewStatus.SENT).toList();
        if (!markedSent.isEmpty()) {
            sent.addAll(markedSent, Instant.now());
        }
        Set<String> gone = sent.sentKeys();
        List<ScoredPaper> leftOver = queued.stream().filter(paper -> gone.contains(paper.queueKey())).toList();
        if (!leftOver.isEmpty()) {
            queue.remove(leftOver);
        }
    }
}
