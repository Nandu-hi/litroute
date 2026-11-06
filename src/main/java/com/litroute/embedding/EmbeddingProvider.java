package com.litroute.embedding;

/**
 * Turns a paper's text into a semantic vector. M5 uses it to embed a student's reference PDFs and
 * M6 to embed candidate papers, so both sides of a comparison always come from the same model.
 *
 * This is the Strategy seam the design doc names for M6: the local SPECTER2 model today, a public
 * embedding API as the alternative, with no change elsewhere in the pipeline.
 */
public interface EmbeddingProvider extends AutoCloseable {

    /**
     * {@return an id for the model and its input convention}. Stored with every vector, so vectors from another
     * model are recognized as incomparable and recomputed instead of mixed.
     * Example: Specter2 -> {@code "specter2-proximity-onnx/cls/512"}
     */
    String modelId();

    /**
     * {@return a unit-length vector for the paper}
     * Example: {@code embed("Wave loads on piles", "We study...")} -> float[768] with norm 1
     *
     * @param title the paper's title
     * @param text  its abstract, headings or a body passage; may be empty, and over-long input is truncated
     */
    float[] embed(String title, String text);

    /**
     * Input: none.
     * Output: none; the model's native resources are released.
     * Used by: try-with-resources in Session (M8), PipelineRunnerCli, ProfileBuilderCli, RelevanceScorerCli.
     * Example: {@code try (var p = Specter2EmbeddingProvider.atDefaultLocation()) {...}}
     */
    @Override
    void close();
}
