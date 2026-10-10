package com.litroute.profile;

import com.litroute.model.Profile.InterestCluster;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.util.Vectors;
import java.util.ArrayList;
import java.util.List;

/**
 * Groups a profile's papers into the student's distinct interests, so a student working on two
 * topics is represented by two directions instead of one average that matches neither.
 *
 * Average-linkage agglomerative clustering on cosine similarity: start with one cluster per paper
 * and repeatedly merge the two clusters whose papers are most similar on average, until no pair
 * reaches {@link #MERGE_SIMILARITY}. Deterministic: ties go to the earlier clusters, and clusters are
 * returned in order of their first paper.
 */
public final class EmbeddingClusterer {

    /**
     * SPECTER2 similarities are compressed into the upper range, and more so within one field. On the
     * eight reference folders this was calibrated on (292 structural- and hazard-engineering papers),
     * papers in the same folder have a median similarity of 0.91 and papers in different folders 0.88.
     * At 0.89 each folder splits into one to four interests, and routing a held-out paper to the
     * nearest interest picks its own folder for 80% of papers (within the top two for 94%) - as
     * good as any threshold tried, without fragmenting folders into near-singleton interests.
     */
    public static final double MERGE_SIMILARITY = 0.89;

    private final double mergeSimilarity;

    public EmbeddingClusterer() {
        this(MERGE_SIMILARITY);
    }

    public EmbeddingClusterer(double mergeSimilarity) {
        this.mergeSimilarity = mergeSimilarity;
    }

    public List<InterestCluster> cluster(List<ProfilePaper> papers) {
        int n = papers.size();
        double[][] similarity = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                similarity[i][j] = Vectors.cosine(papers.get(i).embedding(), papers.get(j).embedding());
                similarity[j][i] = similarity[i][j];
            }
        }
        List<List<Integer>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            clusters.add(new ArrayList<>(List.of(i)));
        }
        while (clusters.size() > 1) {
            int bestA = -1;
            int bestB = -1;
            double best = Double.NEGATIVE_INFINITY;
            for (int a = 0; a < clusters.size(); a++) {
                for (int b = a + 1; b < clusters.size(); b++) {
                    double linkage = averageLinkage(clusters.get(a), clusters.get(b), similarity);
                    if (linkage > best) {
                        best = linkage;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (best < mergeSimilarity) {
                break;
            }
            clusters.get(bestA).addAll(clusters.remove(bestB));
        }
        List<InterestCluster> result = new ArrayList<>();
        for (List<Integer> members : clusters) {
            List<float[]> vectors = members.stream().map(i -> papers.get(i).embedding()).toList();
            List<String> hashes = members.stream().sorted().map(i -> papers.get(i).sha256()).toList();
            result.add(new InterestCluster(Vectors.normalize(Vectors.mean(vectors)), hashes));
        }
        return result;
    }

    private static double averageLinkage(List<Integer> a, List<Integer> b, double[][] similarity) {
        double sum = 0;
        for (int i : a) {
            for (int j : b) {
                sum += similarity[i][j];
            }
        }
        return sum / (a.size() * b.size());
    }
}
