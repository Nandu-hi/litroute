package com.litroute.scoring;

import com.litroute.model.PaperCandidate;
import com.litroute.model.ScoredPaper;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * How well a batch's matches agree with where the professor filed the papers by hand: the measure
 * {@link RelevanceScorerCli} reports and the M6 tests hold the scorer to.
 */
final class MatchEvaluation {

    private MatchEvaluation() {
    }

    /**
     * How many of the filed papers were assigned to the student they are filed under.
     * Input: a matcher's output, and the student each test paper is filed under.
     * Output: how many matches went to the student the paper is filed under.
     * Used by: RelevanceScorerCli, RelevanceScorerTest.
     * Example: {alice=[P1, P2]}, filed {P1=alice, P2=bob} -> 1
     */
    static int assignedCorrectly(Map<String, List<ScoredPaper>> matches, Map<PaperCandidate, String> filedUnder) {
        int correct = 0;
        for (List<ScoredPaper> student : matches.values()) {
            for (ScoredPaper match : student) {
                if (match.profileName().equals(filedUnder.get(match.candidate()))) {
                    correct++;
                }
            }
        }
        return correct;
    }

    /**
     * Precision of the students' recommendations, averaged over the students papers are filed under.
     * Of a student's best-scored recommended papers, the first k count, where k is the smaller of
     * {@code perStudent} and the number of papers filed under the student - so a student with one
     * filed paper is judged on one recommendation. A missing recommendation counts as a miss.
     * Input: a matcher's output, the filed students, and k per student.
     * Output: precision@min(k, filed) of each student's best recommendations, averaged over filed students.
     * Used by: RelevanceScorerCli (k=3), RelevanceScorerTest.
     * Example: alice filed 2, top-2 recommended [P1 alice, P3 bob] -> alice 0.5; averaged with the others
     */
    static double recommendationPrecision(Map<String, List<ScoredPaper>> matches, Map<PaperCandidate, String> filedUnder,
            int perStudent) {
        Map<String, Integer> filedCount = new HashMap<>();
        filedUnder.values().forEach(name -> filedCount.merge(name, 1, Integer::sum));
        double total = 0;
        for (String student : new HashSet<>(filedUnder.values())) {
            int k = Math.min(perStudent, filedCount.get(student));
            List<ScoredPaper> recommended = matches.getOrDefault(student, List.of()).stream()
                    .filter(ScoredPaper::recommended)
                    .sorted(Comparator.comparingDouble(ScoredPaper::score).reversed())
                    .limit(k)
                    .toList();
            long correct = recommended.stream().filter(match -> student.equals(filedUnder.get(match.candidate()))).count();
            total += correct / (double) k;
        }
        return filedCount.isEmpty() ? 0 : total / filedCount.size();
    }
}
