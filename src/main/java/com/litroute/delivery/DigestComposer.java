package com.litroute.delivery;

import com.litroute.model.PaperCandidate;
import com.litroute.model.ScoredPaper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Writes one student's approved papers as plain-text emails, recommended papers first and then by score.
 * Each paper's PDF is attached while the email's attachments stay within the budget; when the next PDF
 * would not fit, the papers continue in another email ("part 2 of 3"). A PDF larger than the whole
 * budget, or one that is gone from disk, is not attached, and the paper is listed with its link.
 */
final class DigestComposer {

    /**
     * What one email's PDFs may add up to. Gmail refuses messages over 25 MB, and base64 makes an
     * attachment about 37% larger, so 15 MiB of PDFs leaves room for the encoding and the text.
     */
    static final long GMAIL_ATTACHMENT_BUDGET = 15L * 1024 * 1024;

    private static final Comparator<ScoredPaper> RECOMMENDED_THEN_SCORE = Comparator
            .comparing(ScoredPaper::recommended).reversed()
            .thenComparing(Comparator.comparingDouble(ScoredPaper::score).reversed());

    private DigestComposer() {
    }

    /** A paper as it goes into an email: with its PDF if attached, else why not. */
    private record Item(ScoredPaper paper, Optional<Path> pdf, String pdfNote) {
    }

    /**
     * @param professor        the Gmail address the emails are sent from
     * @param papers           the student's approved papers, at least one
     * @param attachmentBudget the most bytes of PDF one email carries
     * @return the emails, in the order to send them; together they list every paper once
     */
    static List<DigestEmail> compose(String profileName, List<String> recipients, String professor,
            List<ScoredPaper> papers, long attachmentBudget) {
        List<List<Item>> parts = new ArrayList<>();
        List<Item> current = new ArrayList<>();
        long attached = 0;
        for (ScoredPaper paper : papers.stream().sorted(RECOMMENDED_THEN_SCORE).toList()) {
            Optional<Path> pdf = paper.candidate().pdfPath();
            long size = pdf.map(DigestComposer::sizeOrMinusOne).orElse(-1L);
            if (pdf.isEmpty()) {
                current.add(new Item(paper, Optional.empty(), "No PDF was freely available; use the link above."));
            } else if (size < 0) {
                current.add(new Item(paper, Optional.empty(), "The PDF could not be found; use the link above."));
            } else if (size > attachmentBudget) {
                current.add(new Item(paper, Optional.empty(), "The PDF is too large to attach; use the link above."));
            } else {
                if (attached + size > attachmentBudget) {
                    parts.add(current);
                    current = new ArrayList<>();
                    attached = 0;
                }
                current.add(new Item(paper, pdf, "PDF attached: " + pdf.get().getFileName()));
                attached += size;
            }
        }
        parts.add(current);

        List<DigestEmail> emails = new ArrayList<>();
        int number = 1;
        for (List<Item> part : parts) {
            String subject = "LitRoute: " + count(part.size(), "new paper") + " for " + profileName
                    + (parts.size() > 1 ? " (part " + number + " of " + parts.size() + ")" : "");
            emails.add(new DigestEmail(profileName, recipients, subject, body(profileName, professor, part),
                    part.stream().flatMap(item -> item.pdf().stream()).toList(),
                    part.stream().map(Item::paper).toList()));
            number++;
        }
        return emails;
    }

    private static String body(String profileName, String professor, List<Item> items) {
        StringBuilder text = new StringBuilder("Hello,\n\n")
                .append(professor).append(" has approved ").append(items.size() == 1 ? "this paper" : "these papers")
                .append(" for your research profile \"").append(profileName).append("\". ")
                .append("They come from the publication alerts LitRoute watches; the PDFs that were freely ")
                .append("available are attached.\n");
        int number = 1;
        for (Item item : items) {
            PaperCandidate paper = item.paper().candidate();
            text.append("\n").append(number++).append(". ").append(paper.title()).append("\n");
            if (!paper.authors().isEmpty()) {
                text.append("   ").append(String.join(", ", paper.authors())).append("\n");
            }
            List<String> where = new ArrayList<>();
            paper.venue().ifPresent(where::add);
            paper.year().map(String::valueOf).ifPresent(where::add);
            paper.articleType().ifPresent(where::add);
            if (!where.isEmpty()) {
                text.append("   ").append(String.join(" · ", where)).append("\n");
            }
            paper.url().or(() -> paper.doi().map("https://doi.org/"::concat))
                    .ifPresent(link -> text.append("   ").append(link).append("\n"));
            text.append("   ").append(item.pdfNote()).append("\n");
            paper.abstractSnippet().ifPresent(summary -> text.append("   Abstract: ").append(summary).append("\n"));
        }
        return text.append("\n-- \nSent by LitRoute on behalf of ").append(professor)
                .append(". Reply to this email to reach them.\n").toString();
    }

    private static long sizeOrMinusOne(Path pdf) {
        try {
            return Files.isRegularFile(pdf) ? Files.size(pdf) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
