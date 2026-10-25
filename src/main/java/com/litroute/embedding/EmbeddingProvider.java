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
     * Identifies the model (and its input convention). Stored with every embedding, so vectors from
     * a different model are recognized as incomparable and recomputed instead of mixed.
     * Input: none.
     * Output: an id for the model and its input convention, stored with every vector.
     * Used by: ProfileBuilder (M5) to stamp profiles; RelevanceScorer (M6) to refuse profiles from another model.
     * Example: Specter2 -> {@code "specter2-proximity-onnx/cls/512"}
     */
    String modelId();

    /**
     * Input: the paper's title, and its abstract, headings or a body passage (may be empty; over-long input is truncated).
     * Output: a unit-length vector.
     * Used by: PaperEmbedder (M5 and M6 full text), RelevanceScorer (M6 title/abstract-only candidates).
     * Example: {@code embed("Wave loads on piles", "We study...")} -> float[768] with norm 1
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
