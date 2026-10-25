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

    /**
     * Input: the embedding model.
     * Output: an embedder using it.
     * Used by: ProfileBuilder (M5), RelevanceScorer (M6).
     * Example: {@code new PaperEmbedder(specter2)}
     */
    public PaperEmbedder(EmbeddingProvider provider) {
        this.provider = provider;
    }

    /**
     * Input: a paper's text.
     * Output: its abstract, structure and body views plus their weighted blend, all unit length.
     * Used by: {@link #embed(PaperText, String, String)}; RelevanceScorer (M6) for candidates with a PDF.
     * Example: text with no headings -> structureView empty, combined = 0.75 abstract + 0.25 body (renormalized)
     */
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
            // One vector per sampled passage, averaged into the body view.
            bodyView = Optional.of(Vectors.normalize(Vectors.mean(chunks)));
        }
        return new Embedding(combine(abstractView, structureView, bodyView), abstractView, structureView, bodyView);
    }

    /**
     * A profile's reference paper: its views, text and author keywords, plus where it came from.
     * Input: a reference paper's text, its path in the profile folder and the PDF's hash.
     * Output: a ProfilePaper with every view, its text and its author keywords.
     * Used by: ProfileBuilder.syncAll (M5) for each new or changed PDF.
     * Example: {@code embed(text, "rao2024.pdf", "ab12..")} -> ProfilePaper(rao2024.pdf, ab12.., "Wave loads..", ...)
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
