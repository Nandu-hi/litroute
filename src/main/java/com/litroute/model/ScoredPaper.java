package com.litroute.model;

import com.litroute.util.Titles;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Output of M6 RelevanceScorer: how relevant one candidate paper is to one student, and why. The
 * review queue (M7e) holds these; the professor approves or drops each one (M8), and approved ones
 * are sent to the student (M10), which marks them sent.
 *
 * @param candidate   the deduplicated paper
 * @param profileName the student's profile, as M7d names it
 * @param score       0.0-1.0: the paper's relevance to the student (0.9 as close as the student's own
 *                    papers typically are, 0.1 as close as another student's papers) times the share of
 *                    its match that belongs to this student rather than to the others. A paper goes to
 *                    the student it scores highest for.
 * @param rationale   human-readable evidence for the score: the share, the relevance, the words the
 *                    paper shares with the student's papers, the closest of those papers, and what the
 *                    paper was scored from
 * @param status      the professor's decision so far
 * @param recommended whether M6 recommends the paper to the professor; the professor still decides
 * @param relevance   0.0-1.0, how close the paper is to the student's own work whoever else it suits:
 *                    0.9 as close as the student's own papers typically are, 0.1 as close as another
 *                    student's; empty when none was recorded (older queue entries)
 * @param listedAuthor the paper's author, as printed on it, who is on the professor's authors list; such a
 *                    paper is held to a lower cutoff and listed first. Empty for any other paper
 * @param fits        how M6 scored the paper for every student, best first, so the professor can move it to
 *                    another student with that student's own score; empty when not recorded (older entries)
 */
public record ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
        ReviewStatus status, boolean recommended, Optional<Double> relevance, Optional<String> listedAuthor,
        List<Fit> fits) {

    /**
     * How M6 scored a paper for one student, whether or not it went to them.
     *
     * @param profileName the student
     * @param score       relevance times share, as {@link ScoredPaper#score}
     * @param relevance   as {@link ScoredPaper#relevance}
     * @param rationale   M6's reasoning for this student
     */
    public record Fit(String profileName, double score, double relevance, String rationale) {

        /**
         * Input: every field; profileName non-blank, score and relevance in 0-1, rationale non-null.
         * Output: the fit; throws if a rule is broken.
         * Used by: RelevanceScorer (M6), ScoredQueueStore (M7e) when loading.
         * Example: {@code new Fit("bob", 0.21, 0.55, "37% of the paper's match goes to bob ...")}
         */
        public Fit {
            if (profileName == null || profileName.isBlank() || rationale == null) {
                throw new IllegalArgumentException("a fit needs a profile name and a rationale");
            }
            if (!(score >= 0.0 && score <= 1.0) || !(relevance >= 0.0 && relevance <= 1.0)) {
                throw new IllegalArgumentException("score and relevance must be within 0.0-1.0");
            }
        }
    }

    /**
     * Input: every field; score in 0-1, profileName non-blank, relevance, listedAuthor and fits non-null.
     * Output: the scored paper (fits copied); throws if any of those rules is broken.
     * Used by: RelevanceScorer (M6), ScoredQueueStore/SentPaperStore (M7e) when loading.
     * Example: {@code new ScoredPaper(c, "alice", 0.82, "Relevance 0.82 ...", PENDING, true, Optional.of(0.82), Optional.empty(), List.of())} -> ok; score 1.2 -> throws
     */
    public ScoredPaper {
        if (candidate == null || status == null || rationale == null) {
            throw new IllegalArgumentException("candidate, rationale and status are required");
        }
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be blank");
        }
        if (!(score >= 0.0 && score <= 1.0)) {
            throw new IllegalArgumentException("score must be within 0.0-1.0, was " + score);
        }
        if (relevance == null || listedAuthor == null || fits == null) {
            throw new IllegalArgumentException("relevance, listedAuthor and fits must be present or empty, not null");
        }
        fits = List.copyOf(fits);
    }

    /**
     * A scored paper with no per-student fits recorded.
     *
     * @param candidate    the paper
     * @param profileName  the student
     * @param score        relevance times share, 0-1
     * @param rationale    why
     * @param status       the professor's decision
     * @param recommended  whether M6 recommends it
     * @param relevance    0-1, if recorded
     * @param listedAuthor the listed author it is by, if any
     */
    public ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status, boolean recommended, Optional<Double> relevance, Optional<String> listedAuthor) {
        this(candidate, profileName, score, rationale, status, recommended, relevance, listedAuthor, List.of());
    }

    /**
     * A scored paper by no listed author and with no fits.
     *
     * @param candidate   the paper
     * @param profileName the student
     * @param score       relevance times share, 0-1
     * @param rationale   why
     * @param status      the professor's decision
     * @param recommended whether M6 recommends it
     * @param relevance   0-1, if recorded
     */
    public ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status, boolean recommended, Optional<Double> relevance) {
        this(candidate, profileName, score, rationale, status, recommended, relevance, Optional.empty());
    }

    /**
     * A scored paper with no relevance recorded.
     *
     * @param candidate   the paper
     * @param profileName the student
     * @param score       relevance times share, 0-1
     * @param rationale   why
     * @param status      the professor's decision
     * @param recommended whether M6 recommends it
     */
    public ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status, boolean recommended) {
        this(candidate, profileName, score, rationale, status, recommended, Optional.empty());
    }

    /**
     * A scored paper that is not recommended and has no relevance.
     *
     * @param candidate   the paper
     * @param profileName the student
     * @param score       relevance times share, 0-1
     * @param rationale   why
     * @param status      the professor's decision
     */
    public ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status) {
        this(candidate, profileName, score, rationale, status, false);
    }

    /**
     * {@return a copy with only the status changed}
     * Used by: ScoredQueueStore.setStatus (M7e), SentPaperStore (status SENT), ReviewPane (M8).
     *
     * @param newStatus the professor's new decision
     */
    public ScoredPaper withStatus(ReviewStatus newStatus) {
        return new ScoredPaper(candidate, profileName, score, rationale, newStatus, recommended, relevance, listedAuthor,
                fits);
    }

    /**
     * {@return a copy with only the recommendation changed}
     *
     * @param isRecommended whether M6 recommends the paper
     */
    public ScoredPaper withRecommended(boolean isRecommended) {
        return new ScoredPaper(candidate, profileName, score, rationale, status, isRecommended, relevance, listedAuthor,
                fits);
    }

    /**
     * {@return a copy whose candidate points at that PDF, nothing else changed}
     * Used by: ScoredQueueStore (M7e), filing the PDF in the student's folder.
     *
     * @param pdfPath the new PDF location
     */
    public ScoredPaper withCandidatePdf(Optional<Path> pdfPath) {
        return new ScoredPaper(candidate.withPdfPath(pdfPath), profileName, score, rationale, status, recommended,
                relevance, listedAuthor, fits);
    }

    /**
     * {@return how M6 scored this paper for {@code student}; empty if not recorded}
     * Used by: {@link #movedTo}, ReviewPane (M8) when moving a paper.
     *
     * @param student a student's profile name
     */
    public Optional<Fit> fitFor(String student) {
        return fits.stream().filter(fit -> fit.profileName().equals(student)).findFirst();
    }

    /**
     * {@return this paper given to another student}: it takes M6's score, relevance and reasoning for that
     * student, when recorded, and is no longer M6's recommendation. A dropped or unmatched paper becomes
     * pending; a pending or approved one keeps its status.
     * Used by: ScoredQueueStore.move (M7e), from the Review tab's Move (M8).
     * Example: alice/X DROPPED with fit bob 0.21 / 0.55 -> {@code movedTo("bob")} -> bob/X PENDING, score 0.21, relevance 0.55
     *
     * @param student another student's profile name
     * @throws IllegalStateException if the paper has been sent
     * @throws IllegalArgumentException if {@code student} is blank or already its student
     */
    public ScoredPaper movedTo(String student) {
        if (status == ReviewStatus.SENT) {
            throw new IllegalStateException("A sent paper can't be moved to another student");
        }
        if (student == null || student.isBlank() || student.equals(profileName)) {
            throw new IllegalArgumentException("Not another student: " + student);
        }
        ReviewStatus moved = status == ReviewStatus.DROPPED || status == ReviewStatus.UNMATCHED
                ? ReviewStatus.PENDING : status;
        String note = "Moved by you from " + profileName + " to " + student + ". ";
        Optional<Fit> fit = fitFor(student);
        if (fit.isPresent()) {
            return new ScoredPaper(candidate, student, fit.get().score(), note + fit.get().rationale(), moved, false,
                    Optional.of(fit.get().relevance()), listedAuthor, fits);
        }
        return new ScoredPaper(candidate, student, 0.0, note + "M6 has no score for " + student + " (the paper was "
                + "scored before that profile existed, or before every student's score was kept). For " + profileName
                + ": " + rationale, moved, false, Optional.empty(), listedAuthor, fits);
    }

    /**
     * {@return the identity in the review queue: {@code profileName + "\n" + paperKey(candidate)}}
     * Used by: the M7e stores, ApprovedPaperSender (M10), PipelineRunner (M9), ReviewPane (M8).
     * Example: alice + DOI "10.1/ABC" -> {@code "alice\ndoi:10.1/abc"}
     */
    public String queueKey() {
        return profileName + "\n" + paperKey(candidate);
    }

    /**
     * {@return the paper's identity regardless of student: {@code doi:<lowercased DOI>}, else
     * {@code title:<compacted title>}}
     * Used by: {@link #queueKey}, PipelineRunner (M9) to skip papers already scored, PriorityAuthors, ReviewPane.
     * Example: no DOI, title "Wave Loads: A Review" -> {@code "title:waveloadsareview"}
     *
     * @param candidate a candidate
     */
    public static String paperKey(PaperCandidate candidate) {
        return candidate.doi()
                .map(doi -> "doi:" + doi.toLowerCase(Locale.ROOT))
                .orElseGet(() -> "title:" + Titles.compact(candidate.title()));
    }
}
