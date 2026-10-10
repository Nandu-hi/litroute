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

    public PaperEmbedder(EmbeddingProvider provider) {
        this.provider = provider;
    }

    public Embedding embed(PaperText text) {
        float[] abstractView = provider.embed(text.title(), text.abstractText());
        Optional<float[]> structureView = text.headings().isEmpty()
                ? Optional.empty()
                : Optional.of(provider.embed(text.title(), String.join("; ", text.headings())));
        Optional<float[]> bodyView = Optional.empty();
        if (!text.bodyChunks().isEmpty()) {
            List<float[]> chunks = new ArrayList<>();
            for (String chunk : text.bodyChunks()) {
                chunks.add(provider.embed(text.title(), chunk));
            }
            bodyView = Optional.of(Vectors.normalize(Vectors.mean(chunks)));
        }
        return new Embedding(combine(abstractView, structureView, bodyView), abstractView, structureView, bodyView);
    }

    /** A profile's reference paper: its views and text, plus where it came from. */
    public ProfilePaper embed(PaperText text, String relativePath, String sha256) {
        Embedding embedding = embed(text);
        return new ProfilePaper(relativePath, sha256, text.title(), embedding.combined(), embedding.abstractView(),
                embedding.structureView(), embedding.bodyView(), Optional.of(text.abstractText()),
                Optional.of(text.bodyText()));
    }

    /** Weighted blend of the views, unit length; a missing view's weight goes proportionally to the others. */
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
