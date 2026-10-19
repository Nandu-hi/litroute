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
     *
     * @return every student's profile name, in the order the matcher was given the profiles, mapped to
     *         the papers matched to that student, highest score first; a student nothing matched maps to
     *         an empty list. Recommended papers have {@link ScoredPaper#recommended()} set.
     * @throws IOException if the matcher depends on a service that could not answer
     */
    Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch) throws IOException;
}
