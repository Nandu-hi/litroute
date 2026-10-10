package com.litroute.scoring;

import com.litroute.model.Profile.ProfilePaper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The wording half of M6: how much a text shares the words and word pairs ({@link Terms}) of each
 * student's reference papers, weighted so that what is particular to a few papers counts and what
 * every paper says does not.
 *
 * TF-IDF over the students' papers: a term's weight in a paper grows with the log of how often it
 * occurs there and with how few papers use it ({@code ln((N+1)/(df+1)) + 1}). Terms found in only one
 * paper (often extraction noise) or in more than half of them carry no information about who a paper
 * belongs to and are left out. Each paper is a unit-length vector, compared by cosine.
 *
 * A text's closeness to a student is the same blend the SPECTER2 side uses: half its similarity to
 * the student's papers taken together, half its mean similarity to the closest three of them.
 */
final class LexicalIndex {

    static final int MIN_PAPERS = 2;
    static final double MAX_PAPER_SHARE = 0.5;

    /** One student's papers compared with one text; the closest papers come best first. */
    record Closeness(double similarity, List<Neighbor> closest) {
    }

    record Neighbor(ProfilePaper paper, double similarity) {
    }

    private record Doc(String profileName, ProfilePaper paper, Map<String, Double> vector) {
    }

    private record Posting(int doc, double weight) {
    }

    private final Map<String, Double> idf = new HashMap<>();
    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, int[]> docsByProfile = new LinkedHashMap<>();
    private final Map<String, List<Posting>> postings = new HashMap<>();
    private final double[][] gram;
    /** Per student, the sum of its papers' vectors: what the student's papers say, term by term. */
    private final Map<String, Map<String, Double>> profileTerms = new HashMap<>();

    /** @param papersByProfile each student's reference papers, in a stable order; papers without text count as empty */
    LexicalIndex(Map<String, List<ProfilePaper>> papersByProfile) {
        List<Map<String, Integer>> counts = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        List<ProfilePaper> papers = new ArrayList<>();
        papersByProfile.forEach((name, profilePapers) -> {
            for (ProfilePaper paper : profilePapers) {
                owners.add(name);
                papers.add(paper);
                counts.add(Terms.counts(documentText(paper)));
            }
        });
        Map<String, Integer> paperFrequency = new HashMap<>();
        counts.forEach(c -> c.keySet().forEach(term -> paperFrequency.merge(term, 1, Integer::sum)));
        int n = counts.size();
        paperFrequency.forEach((term, df) -> {
            if (df >= MIN_PAPERS && df <= MAX_PAPER_SHARE * n) {
                idf.put(term, Math.log((n + 1.0) / (df + 1.0)) + 1);
            }
        });
        for (int i = 0; i < n; i++) {
            Map<String, Double> vector = weigh(counts.get(i));
            docs.add(new Doc(owners.get(i), papers.get(i), vector));
            for (Map.Entry<String, Double> term : vector.entrySet()) {
                postings.computeIfAbsent(term.getKey(), t -> new ArrayList<>()).add(new Posting(i, term.getValue()));
            }
        }
        Map<String, List<Integer>> members = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            members.computeIfAbsent(docs.get(i).profileName(), name -> new ArrayList<>()).add(i);
        }
        members.forEach((name, list) -> docsByProfile.put(name, list.stream().mapToInt(Integer::intValue).toArray()));
        gram = new double[n][];
        for (int i = 0; i < n; i++) {
            gram[i] = row(docs.get(i).vector());
        }
        docsByProfile.forEach((name, list) -> {
            Map<String, Double> sum = new HashMap<>();
            for (int doc : list) {
                docs.get(doc).vector().forEach((term, weight) -> sum.merge(term, weight, Double::sum));
            }
            profileTerms.put(name, sum);
        });
    }

    /** The words a lexical comparison reads for a paper: its title, abstract and body, as one text. */
    static String text(String title, String abstractText, String bodyText) {
        return title + ". " + abstractText + ". " + bodyText;
    }

    static String documentText(ProfilePaper paper) {
        return text(paper.title(), paper.abstractText().orElse(""), paper.bodyText().orElse(""));
    }

    /** The text as a unit-length vector over this index's terms; empty if it shares none of them. */
    Map<String, Double> vector(String text) {
        return weigh(Terms.counts(text));
    }

    /**
     * The text's closeness to every student, leaving out the papers {@code heldOut} accepts (a paper
     * being checked against the others, and any copy of it). A student none of whose papers remain is
     * left out of the result.
     */
    Map<String, Closeness> closeness(Map<String, Double> vector, Predicate<ProfilePaper> heldOut) {
        double[] row = row(vector);
        Map<String, Closeness> result = new LinkedHashMap<>();
        docsByProfile.forEach((name, members) -> {
            List<Integer> kept = new ArrayList<>();
            for (int doc : members) {
                if (!heldOut.test(docs.get(doc).paper())) {
                    kept.add(doc);
                }
            }
            if (kept.isEmpty()) {
                return;
            }
            double together = 0;
            double selfSimilarity = 0;
            for (int j : kept) {
                together += row[j];
                for (int k : kept) {
                    selfSimilarity += gram[j][k];
                }
            }
            double centroid = selfSimilarity > 0 ? together / Math.sqrt(selfSimilarity) : 0;
            List<Neighbor> neighbors = new ArrayList<>();
            for (int j : kept) {
                neighbors.add(new Neighbor(docs.get(j).paper(), row[j]));
            }
            neighbors.sort(Comparator.comparingDouble(Neighbor::similarity).reversed());
            List<Neighbor> closest = List.copyOf(neighbors.subList(0, Math.min(RelevanceScorer.CLOSEST_PAPERS, neighbors.size())));
            double closestMean = closest.stream().mapToDouble(Neighbor::similarity).average().orElse(0);
            double similarity = RelevanceScorer.INTEREST_WEIGHT * centroid + (1 - RelevanceScorer.INTEREST_WEIGHT) * closestMean;
            result.put(name, new Closeness(similarity, closest));
        });
        return result;
    }

    /** The terms that contribute most to the text's similarity with the student's papers, most first. */
    List<String> sharedTerms(Map<String, Double> vector, String profileName, int limit) {
        Map<String, Double> student = profileTerms.getOrDefault(profileName, Map.of());
        return vector.entrySet().stream()
                .filter(term -> student.containsKey(term.getKey()))
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> term) ->
                        term.getValue() * student.get(term.getKey())).reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, Double> weigh(Map<String, Integer> counts) {
        Map<String, Double> vector = new HashMap<>();
        double sumOfSquares = 0;
        for (Map.Entry<String, Integer> term : counts.entrySet()) {
            Double weight = idf.get(term.getKey());
            if (weight != null) {
                double w = (1 + Math.log(term.getValue())) * weight;
                vector.put(term.getKey(), w);
                sumOfSquares += w * w;
            }
        }
        double norm = Math.sqrt(sumOfSquares);
        if (norm > 0) {
            vector.replaceAll((term, w) -> w / norm);
        }
        return vector;
    }

    /** Cosine similarity of the vector with every paper of the index. */
    private double[] row(Map<String, Double> vector) {
        double[] row = new double[docs.size()];
        for (Map.Entry<String, Double> term : vector.entrySet()) {
            for (Posting posting : postings.getOrDefault(term.getKey(), List.of())) {
                row[posting.doc()] += term.getValue() * posting.weight();
            }
        }
        return row;
    }
}
