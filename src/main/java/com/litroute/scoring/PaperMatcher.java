package com.litroute.scoring;

import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * M6's output: {@link RelevanceScorer} matches the papers by SPECTER2 and their words, one student per
 * paper. The review queue (M7e) stores the result as it is.
 */
public interface PaperMatcher {

    /**
     * Matches a batch of deduplicated papers (M4/M7c output) to the students.
     * Example: [P1 (wave loads), P2 (soil creep)] -> {alice=[P1 0.82 recommended], bob=[P2 0.74]}
     *
     * @param batch deduplicated papers
     * @return every student, in profile order, mapped to their matched papers, best first ([] if none)
     * @throws IOException if a needed service failed
     */
    Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch) throws IOException;

    /**
     * Judges a batch for the review queue: every paper once, the matched ones as {@link #match} gives them
     * (pending), every other under its closest student as {@link ReviewStatus#UNMATCHED}, saying why. This
     * default returns only the matched ones.
     * Example: [P1 (wave loads), P2 (off topic)] -> [alice/P1 PENDING, bob/P2 UNMATCHED "Not matched: relevance 0.31 ..."]
     *
     * @param batch deduplicated papers
     * @return one scored paper per paper judged
     * @throws IOException if a needed service failed
     */
    default List<ScoredPaper> matchAll(List<PaperCandidate> batch) throws IOException {
        return match(batch).values().stream().flatMap(List::stream).toList();
    }
}
