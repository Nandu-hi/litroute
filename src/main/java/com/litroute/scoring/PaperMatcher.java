package com.litroute.scoring;

import com.litroute.model.PaperCandidate;
import com.litroute.model.ScoredPaper;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * M6's output, whichever way the papers are matched: {@link RelevanceScorer} (SPECTER2 and the papers'
 * words, one student per paper) or {@code AiPaperMatcher} (a language model, which may give a paper to
 * several students). The review queue (M7e) stores the result as it is.
 */
public interface PaperMatcher {

    /**
     * Matches a batch of deduplicated papers (M4's output) to the students.
     * Input: a batch of deduplicated papers (M4/M7c output).
     * Output: every student's name, in profile order, mapped to their matched papers, best first ([] if none); IOException if a needed service failed.
     * Used by: PipelineRunner.run (M9) step 5; the result goes to ScoredQueueStore.addAll (M7e).
     * Example: [P1 (wave loads), P2 (soil creep)] -> {alice=[P1 0.82 recommended], bob=[P2 0.74]}
     */
    Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch) throws IOException;
}
