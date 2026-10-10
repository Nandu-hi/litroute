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
     */
    String modelId();

    /**
     * @param title the paper's title
     * @param text  what describes the paper alongside its title: its abstract, its headings, or a
     *              passage of its body; may be empty. Over-long input is truncated, never rejected.
     * @return a unit-length vector
     */
    float[] embed(String title, String text);

    @Override
    void close();
}
