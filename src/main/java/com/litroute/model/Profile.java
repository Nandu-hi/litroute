package com.litroute.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Output of M5 ProfileBuilder: what one student works on, as SPECTER2 vectors computed from the
 * reference papers in the student's profile folder. M6 compares candidate papers against it and M10
 * sends the approved ones to its email addresses.
 *
 * Every paper's own vectors and text are kept, not just a summary, so M6 can score papers by meaning
 * and by wording - and compare strategies - without re-reading any PDF. A profile is one uniform body
 * of work: M6 compares a paper with all of its papers together and with the closest few.
 *
 * @param name      the profile folder's name
 * @param emails    addresses from the folder's email.txt, in file order; empty if there is none
 * @param papers    one entry per readable PDF in the folder; never empty
 * @param modelId   the {@code EmbeddingProvider} that produced every vector here; vectors from
 *                  different models are not comparable
 * @param updatedAt when the profile last changed (papers, addresses or model)
 */
public record Profile(
        String name,
        List<String> emails,
        List<ProfilePaper> papers,
        String modelId,
        Instant updatedAt) {

    public Profile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (papers.isEmpty()) {
            throw new IllegalArgumentException("a profile needs at least one paper");
        }
        emails = List.copyOf(emails);
        papers = List.copyOf(papers);
    }

    /**
     * One reference paper. All vectors are unit length. Arrays are shared, not copied: treat them as
     * read-only.
     *
     * @param relativePath  the PDF's path inside the profile folder
     * @param sha256        hash of the PDF's bytes; an unchanged file is never embedded twice
     * @param title         title as extracted from the PDF
     * @param embedding     the paper's combined vector - what scoring uses
     * @param abstractView  embedding of title + abstract (SPECTER2's native input)
     * @param structureView embedding of title + section headings; empty if none were recognized
     * @param bodyView      mean embedding of title + sampled body passages; empty if no body text
     * @param abstractText  the abstract as read from the PDF (see {@code PaperText#abstractText}); M6
     *                      matches its words. Empty if the profile was saved before text was kept.
     * @param bodyText      the headings and sampled body passages as read from the PDF (see
     *                      {@code PaperText#bodyText}), possibly blank; empty exactly when abstractText is
     */
    public record ProfilePaper(
            String relativePath,
            String sha256,
            String title,
            float[] embedding,
            float[] abstractView,
            Optional<float[]> structureView,
            Optional<float[]> bodyView,
            Optional<String> abstractText,
            Optional<String> bodyText) {

        /** A paper whose text was not kept: its vectors only. */
        public ProfilePaper(String relativePath, String sha256, String title, float[] embedding, float[] abstractView,
                Optional<float[]> structureView, Optional<float[]> bodyView) {
            this(relativePath, sha256, title, embedding, abstractView, structureView, bodyView, Optional.empty(),
                    Optional.empty());
        }

        /** Whether the paper's text was kept, so M6 can match its words without reading the PDF again. */
        public boolean hasText() {
            return abstractText.isPresent();
        }
    }
}
