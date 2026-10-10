package com.litroute.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class VectorsTest {

    @Test
    public void cosineOfParallelOrthogonalAndOppositeVectors() {
        float[] x = {1, 0, 0};
        assertEquals(1.0, Vectors.cosine(x, new float[] {3, 0, 0}), 1e-9);
        assertEquals(0.0, Vectors.cosine(x, new float[] {0, 2, 0}), 1e-9);
        assertEquals(-1.0, Vectors.cosine(x, new float[] {-1, 0, 0}), 1e-9);
        assertEquals(1 / Math.sqrt(2), Vectors.cosine(x, new float[] {1, 1, 0}), 1e-9);
    }

    @Test
    public void zeroVectorIsSimilarToNothingInsteadOfNaN() {
        float[] zero = {0, 0, 0};
        assertEquals(0.0, Vectors.cosine(zero, new float[] {1, 2, 3}), 0);
        assertEquals(0.0, Vectors.cosine(zero, zero), 0);
        assertArrayEquals(zero, Vectors.normalize(zero), 0f);
    }

    @Test
    public void normalizeGivesUnitLengthWithoutTouchingTheInput() {
        float[] v = {3, 4};
        assertArrayEquals(new float[] {0.6f, 0.8f}, Vectors.normalize(v), 1e-6f);
        assertArrayEquals(new float[] {3, 4}, v, 0f);
        assertEquals(1.0, Vectors.norm(Vectors.normalize(v)), 1e-6);
    }

    @Test
    public void meanAndWeightedSum() {
        List<float[]> vectors = List.of(new float[] {1, 0}, new float[] {0, 1}, new float[] {1, 1});
        assertArrayEquals(new float[] {2f / 3, 2f / 3}, Vectors.mean(vectors), 1e-6f);
        assertArrayEquals(new float[] {1.0f, 2.5f}, Vectors.weightedSum(vectors, new double[] {0.5, 2, 0.5}), 1e-6f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void differentLengthsAreRejected() {
        Vectors.cosine(new float[] {1, 2}, new float[] {1, 2, 3});
    }
}
