package com.litroute.delivery;

import com.litroute.model.ScoredPaper;
import java.nio.file.Path;
import java.util.List;

/**
 * One email of M10: approved papers for one student, ready for {@link GmailSmtpSender}.
 *
 * @param profileName the student's profile
 * @param recipients  the profile's addresses, from its folder's email.txt; never empty
 * @param subject     the subject line
 * @param body        the plain-text body listing every paper in {@code papers}
 * @param attachments the PDFs attached, a subset of the papers' own PDFs
 * @param papers      the queue entries this email delivers; they are marked sent once it has gone
 */
public record DigestEmail(String profileName, List<String> recipients, String subject, String body,
        List<Path> attachments, List<ScoredPaper> papers) {

    /**
     * Input: every field; at least one recipient.
     * Output: the email, with immutable copies of its lists; throws if there is no recipient.
     * Used by: DigestComposer.compose (M10).
     * Example: {@code new DigestEmail("alice", List.of(), ...)} -> throws "a digest needs at least one recipient"
     */
    public DigestEmail {
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("a digest needs at least one recipient");
        }
        recipients = List.copyOf(recipients);
        attachments = List.copyOf(attachments);
        papers = List.copyOf(papers);
    }
}
