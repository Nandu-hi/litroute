package com.litroute.profile;

import com.litroute.embedding.EmbeddingProvider;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.util.Vectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Embeds one paper as three views and blends them into the vector that represents it.
 * SPECTER2 was trained on title + abstract, so that view dominates; the headings and body views add
 * what the abstract leaves out (methods, materials, the paper's structure), which sharpens the
 * profile for the narrow, technical matches M6 has to make.
 *
 * Every view keeps the title in front ({@code title[SEP]text}), the input shape SPECTER2 expects.
 * M5 embeds a profile's reference papers this way and M6 embeds candidate papers that come with a
 * PDF the same way, so the two are compared like for like.
 */
public final class PaperEmbedder {

    static final double ABSTRACT_WEIGHT = 0.6;
    static final double STRUCTURE_WEIGHT = 0.2;
    static final double BODY_WEIGHT = 0.2;
    /**
     * Experimental, off by default ({@code -Dlitroute.semantic=abstract}): SPECTER2 represents a paper by its
     * title + abstract alone, for reference papers and PDF candidates alike; no heading or body views are
     * embedded. The body still reaches M6 through its words (TF-IDF). Profiles built one way must be rebuilt
     * (a fresh store) before matching the other way: a stored vector is reused by its PDF's hash.
     */
    public static final boolean SEMANTIC_ABSTRACT_ONLY = "abstract".equals(System.getProperty("litroute.semantic"));

    /**
     * One paper's views, all unit length.
     *
     * @param combined      the weighted blend of the views - what represents the paper
     * @param abstractView  title + abstract
     * @param structureView title + section headings; empty if none were recognized
     * @param bodyView      mean of title + sampled body passages; empty if there was no body text
     */
    public record Embedding(float[] combined, float[] abstractView, Optional<float[]> structureView,
            Optional<float[]> bodyView) {
    }

    private final EmbeddingProvider provider;

    /**
     * An embedder using {@code provider} for every view.
     *
     * @param provider the embedding model
     */
    public PaperEmbedder(EmbeddingProvider provider) {
        this.provider = provider;
    }

    /**
     * {@return the paper's abstract, structure and body views plus their weighted blend, all unit length}
     * A missing view drops out and the other weights rescale.
     *
     * @param text a paper's text
     */
    public Embedding embed(PaperText text) {
        float[] abstractView = provider.embed(text.title(), text.abstractText());
        if (SEMANTIC_ABSTRACT_ONLY) {
            return new Embedding(abstractView, abstractView, Optional.empty(), Optional.empty());
        }
        Optional<float[]> structureView = text.headings().isEmpty()
                ? Optional.empty()
                : Optional.of(provider.embed(text.title(), String.join("; ", text.headings())));
        Optional<float[]> bodyView = Optional.empty();
        if (!text.bodyChunks().isEmpty()) {
            List<float[]> chunks = new ArrayList<>();
            for (String chunk : text.bodyChunks()) {
                chunks.add(provider.embed(text.title(), chunk));
            }
            // One vector per sampled passage, averaged into the body view.
            bodyView = Optional.of(Vectors.normalize(Vectors.mean(chunks)));
        }
        return new Embedding(combine(abstractView, structureView, bodyView), abstractView, structureView, bodyView);
    }

    /**
     * {@return a profile's reference paper: every view, its text and author keywords, and where it came from}
     * Used by: ProfileBuilder.syncAll (M5) for each new or changed PDF.
     *
     * @param text         the paper's text
     * @param relativePath its path in the profile folder
     * @param sha256       the PDF's hash
     */
    public ProfilePaper embed(PaperText text, String relativePath, String sha256) {
        Embedding embedding = embed(text);
        return new ProfilePaper(relativePath, sha256, text.title(), embedding.combined(), embedding.abstractView(),
                embedding.structureView(), embedding.bodyView(), Optional.of(text.abstractText()),
                Optional.of(text.bodyText()), Optional.of(text.keywords()));
    }

    /**
     * Weighted blend of the views, unit length; a missing view's weight goes proportionally to the others.
     * Input: the abstract view and the optional structure and body views.
     * Output: 0.6/0.2/0.2 weighted sum, unit length; a missing view's weight is shared out proportionally.
     * Used by: {@link #embed(PaperText)}.
     * Example: only abstract present -> the abstract view itself
     */
    static float[] combine(float[] abstractView, Optional<float[]> structureView, Optional<float[]> bodyView) {
        List<float[]> views = new ArrayList<>(List.of(abstractView));
        List<Double> weights = new ArrayList<>(List.of(ABSTRACT_WEIGHT));
        structureView.ifPresent(view -> {
            views.add(view);
            weights.add(STRUCTURE_WEIGHT);
        });
        bodyView.ifPresent(view -> {
            views.add(view);
            weights.add(BODY_WEIGHT);
        });
        double[] w = weights.stream().mapToDouble(Double::doubleValue).toArray();
        return Vectors.normalize(Vectors.weightedSum(views, w));
    }
}
