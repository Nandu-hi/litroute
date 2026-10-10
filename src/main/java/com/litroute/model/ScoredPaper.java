package com.litroute.model;

import com.litroute.util.Titles;
import java.util.Locale;

/**
 * Output of M6 RelevanceScorer: how relevant one candidate paper is to one student, and why. The
 * review queue (M7e) holds these; the professor approves or drops each one (M8), and approved ones
 * are sent to the student (M10).
 *
 * @param candidate   the deduplicated paper
 * @param profileName the student's profile, as M7d names it
 * @param score       0.0-1.0: the paper's relevance to the student (0.9 as close as the student's own
 *                    papers typically are, 0.1 as close as another student's papers) times the share of
 *                    its match that belongs to this student rather than to the others. A paper goes to
 *                    the student it scores highest for, and each student's best scores are recommended.
 * @param rationale   human-readable evidence for the score: the share, the relevance, the words the
 *                    paper shares with the student's papers, the closest of those papers, and what the
 *                    paper was scored from
 * @param status      the professor's decision so far
 * @param recommended whether M6 picked the paper as one of the student's top 2-3 in its batch; the
 *                    professor still decides, and may approve papers that were not recommended
 */
public record ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
        ReviewStatus status, boolean recommended) {

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
    }

    /** A paper that was not recommended. */
    public ScoredPaper(PaperCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status) {
        this(candidate, profileName, score, rationale, status, false);
    }

    public ScoredPaper withStatus(ReviewStatus newStatus) {
        return new ScoredPaper(candidate, profileName, score, rationale, newStatus, recommended);
    }

    public ScoredPaper withRecommended(boolean isRecommended) {
        return new ScoredPaper(candidate, profileName, score, rationale, status, isRecommended);
    }

    /**
     * Identity in the review queue: the same paper for the same student. A paper is identified the
     * way M4 identifies it - by DOI when it has one, otherwise by its title.
     */
    public String queueKey() {
        String paper = candidate.doi()
                .map(doi -> "doi:" + doi.toLowerCase(Locale.ROOT))
                .orElseGet(() -> "title:" + Titles.compact(candidate.title()));
        return profileName + "\n" + paper;
    }
}
