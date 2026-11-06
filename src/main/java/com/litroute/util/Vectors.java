package com.litroute.util;

import java.util.Arrays;
import java.util.List;

/**
 * The few vector operations the embedding code needs (M5 building profiles, M6 scoring against
 * them). Pure arithmetic with no policy of its own, like {@link Titles}.
 *
 * A zero vector has no direction, so it is treated as similar to nothing: cosine similarity with it
 * is 0 and normalizing it returns it unchanged, rather than producing NaN that would silently poison
 * every score computed from it.
 */
public final class Vectors {

    private Vectors() {
    }

    /**
     * {@return the cosine similarity, -1 to 1; 0 if either vector is all zeros}
     *
     * @param a a vector
     * @param b a vector of the same length
     * @throws IllegalArgumentException if the lengths differ
     */
    public static double cosine(float[] a, float[] b) {
        requireSameLength(a, b);
        // Accumulate in double so 768-dim float sums don't lose precision.
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * {@return the vector's Euclidean length}
     *
     * @param v a vector
     */
    public static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    /**
     * {@return a new unit-length copy, or an unchanged copy if the vector is all zeros}
     *
     * @param v a vector
     */
    public static float[] normalize(float[] v) {
        double norm = norm(v);
        float[] out = v.clone();
        if (norm == 0) {
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (out[i] / norm);
        }
        return out;
    }

    /**
     * {@return the element-wise mean}
     *
     * @param vectors one or more vectors of equal length
     * @throws IllegalArgumentException if the list is empty
     */
    public static float[] mean(List<float[]> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("mean of no vectors");
        }
        double[] weights = new double[vectors.size()];
        Arrays.fill(weights, 1.0 / vectors.size());
        return weightedSum(vectors, weights);
    }

    /**
     * {@return Σ weights[i] · vectors[i]}
     *
     * @param vectors vectors of equal length
     * @param weights one weight per vector
     * @throws IllegalArgumentException if the counts differ or there are no vectors
     */
    public static float[] weightedSum(List<float[]> vectors, double[] weights) {
        if (vectors.size() != weights.length || vectors.isEmpty()) {
            throw new IllegalArgumentException("need one weight per vector, and at least one vector");
        }
        int dimension = vectors.get(0).length;
        double[] sum = new double[dimension];
        for (int v = 0; v < vectors.size(); v++) {
            float[] vector = vectors.get(v);
            requireSameLength(vectors.get(0), vector);
            for (int i = 0; i < dimension; i++) {
                sum[i] += weights[v] * vector[i];
            }
        }
        float[] out = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            out[i] = (float) sum[i];
        }
        return out;
    }

    /**
     * Input: two vectors.
     * Output: none; throws IllegalArgumentException if their lengths differ.
     * Used by: {@link #cosine} and {@link #weightedSum}.
     * Example: {@code requireSameLength({1}, {1,2})} -> throws "vector lengths differ: 1 vs 2"
     */
    private static void requireSameLength(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vector lengths differ: " + a.length + " vs " + b.length);
        }
    }
}
