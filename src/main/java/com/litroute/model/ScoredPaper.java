package com.litroute.model;

import com.litroute.util.Titles;
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
 *                    student's; empty when the matcher does not measure it (the AI matcher)
 */
public record ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
        ReviewStatus status, boolean recommended, Optional<Double> relevance) {

    /**
     * Input: every field; score in 0-1, profileName non-blank, relevance non-null.
     * Output: the scored paper; throws if any of those rules is broken.
     * Used by: RelevanceScorer and AiPaperMatcher (M6), ScoredQueueStore/SentPaperStore (M7e) when loading.
     * Example: {@code new ScoredPaper(c, "alice", 0.82, "Relevance 0.82 ...", PENDING, true, Optional.of(0.82))} -> ok; score 1.2 -> throws
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
        if (relevance == null) {
            throw new IllegalArgumentException("relevance must be present or empty, not null");
        }
    }

    /**
     * Input: every field except relevance.
     * Output: a scored paper with no relevance.
     * Used by: AiPaperMatcher (M6), which does not measure relevance.
     * Example: {@code new ScoredPaper(c, "alice", 0.7, "why", PENDING, true).relevance()} -> {@code Optional.empty()}
     */
    public ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status, boolean recommended) {
        this(candidate, profileName, score, rationale, status, recommended, Optional.empty());
    }

    /**
     * Input: candidate, profile, score, rationale, status.
     * Output: a scored paper that is not recommended and has no relevance.
     * Used by: tests and older call sites.
     * Example: {@code new ScoredPaper(c, "alice", 0.4, "why", PENDING).recommended()} -> false
     */
    public ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status) {
        this(candidate, profileName, score, rationale, status, false);
    }

    /**
     * Input: the professor's new decision.
     * Output: a copy with only the status changed.
     * Used by: ScoredQueueStore.setStatus (M7e), SentPaperStore (status SENT), ReviewPane (M8).
     * Example: {@code p.withStatus(APPROVED).status()} -> {@code APPROVED}
     */
    public ScoredPaper withStatus(ReviewStatus newStatus) {
        return new ScoredPaper(candidate, profileName, score, rationale, newStatus, recommended, relevance);
    }

    /**
     * Input: whether M6 recommends the paper.
     * Output: a copy with only the recommendation changed.
     * Used by: tests (the matchers construct the flag directly).
     * Example: {@code p.withRecommended(true).recommended()} -> true
     */
    public ScoredPaper withRecommended(boolean isRecommended) {
        return new ScoredPaper(candidate, profileName, score, rationale, status, isRecommended, relevance);
    }

    /**
     * Identity in the review queue: the same paper for the same student.
     * Input: none.
     * Output: {@code profileName + "\n" + paperKey(candidate)}.
     * Used by: ScoredQueueStore/SentPaperStore (M7e), ApprovedPaperSender (M10), PipelineRunner (M9), ReviewPane (M8).
     * Example: alice + DOI "10.1/ABC" -> {@code "alice\ndoi:10.1/abc"}
     */
    public String queueKey() {
        return profileName + "\n" + paperKey(candidate);
    }

    /**
     * Input: a candidate.
     * Output: its identity regardless of student: {@code doi:<lowercased DOI>}, else {@code title:<compacted title>}.
     * Used by: {@link #queueKey}, ScoredPaperLog and PipelineRunner (M9) to skip papers already scored.
     * Example: no DOI, title "Wave Loads: A Review" -> {@code "title:waveloadsareview"}
     */
    public static String paperKey(PaperCandidate candidate) {
        return candidate.doi()
                .map(doi -> "doi:" + doi.toLowerCase(Locale.ROOT))
                .orElseGet(() -> "title:" + Titles.compact(candidate.title()));
    }
}
