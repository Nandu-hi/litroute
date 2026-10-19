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

    /** How many of the filed papers were assigned to the student they are filed under. */
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
