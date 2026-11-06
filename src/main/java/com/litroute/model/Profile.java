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
 * @param keywords  what the student works on, in a few words: the author keywords of their papers that
 *                  the most of their papers are about, most first (see {@code ProfileKeywords}); empty
 *                  if none of the papers lists keywords, or the profile was saved before they were kept
 * @param modelId   the {@code EmbeddingProvider} that produced every vector here; vectors from
 *                  different models are not comparable
 * @param updatedAt when the profile last changed (papers, addresses or model)
 */
public record Profile(
        String name,
        List<String> emails,
        List<ProfilePaper> papers,
        List<String> keywords,
        String modelId,
        Instant updatedAt) {

    /**
     * Input: every field; name non-blank, at least one paper.
     * Output: the profile, with immutable copies of its lists; throws on a blank name or no papers.
     * Used by: ProfileBuilder (M5) and ProfileStore (M7d) when loading.
     * Example: {@code new Profile("alice", List.of("a@x.edu"), papers, List.of("wave loads"), "specter2", now)} -> ok; papers [] -> throws
     */
    public Profile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (papers.isEmpty()) {
            throw new IllegalArgumentException("a profile needs at least one paper");
        }
        emails = List.copyOf(emails);
        papers = List.copyOf(papers);
        keywords = List.copyOf(keywords);
    }

    /**
     * A profile with no keywords, for call sites and tests that predate keywords.
     *
     * @param name      the profile folder's name
     * @param emails    the student's addresses
     * @param papers    the reference papers; never empty
     * @param modelId   the model that produced the vectors
     * @param updatedAt when the profile last changed
     */
    public Profile(String name, List<String> emails, List<ProfilePaper> papers, String modelId, Instant updatedAt) {
        this(name, emails, papers, List.of(), modelId, updatedAt);
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
     * @param keywords      the keywords its authors listed, possibly none; empty if the profile was saved
     *                      before keywords were kept
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
            Optional<String> bodyText,
            Optional<List<String>> keywords) {

        /**
         * Input: every field.
         * Output: the paper, with an immutable copy of its keywords (if read).
         * Used by: ProfileBuilder (M5) and ProfileStore (M7d).
         * Example: keywords {@code Optional.of(mutableList)} -> stored as an unmodifiable copy
         */
        public ProfilePaper {
            keywords = keywords.map(List::copyOf);
        }

        /**
         * A paper whose keywords were not read ({@code keywords()} empty), as saved before keywords were kept.
         *
         * @param relativePath  the PDF's path inside the profile folder
         * @param sha256        the PDF's hash
         * @param title         the paper's title
         * @param embedding     the combined vector
         * @param abstractView  the title + abstract vector
         * @param structureView the title + headings vector, if any
         * @param bodyView      the title + body passages vector, if any
         * @param abstractText  the abstract, if kept
         * @param bodyText      the body text, if kept
         */
        public ProfilePaper(String relativePath, String sha256, String title, float[] embedding, float[] abstractView,
                Optional<float[]> structureView, Optional<float[]> bodyView, Optional<String> abstractText,
                Optional<String> bodyText) {
            this(relativePath, sha256, title, embedding, abstractView, structureView, bodyView, abstractText, bodyText,
                    Optional.empty());
        }

        /**
         * A paper with no text and no keywords, as saved before text was kept.
         *
         * @param relativePath  the PDF's path inside the profile folder
         * @param sha256        the PDF's hash
         * @param title         the paper's title
         * @param embedding     the combined vector
         * @param abstractView  the title + abstract vector
         * @param structureView the title + headings vector, if any
         * @param bodyView      the title + body passages vector, if any
         */
        public ProfilePaper(String relativePath, String sha256, String title, float[] embedding, float[] abstractView,
                Optional<float[]> structureView, Optional<float[]> bodyView) {
            this(relativePath, sha256, title, embedding, abstractView, structureView, bodyView, Optional.empty(),
                    Optional.empty(), Optional.empty());
        }

        /**
         * {@return whether the abstract text was kept, so M6 can match its words without re-reading the PDF}
         * Used by: ProfileBuilder (M5) to back-fill text; RelevanceScorer (M6) lexical matching.
         */
        public boolean hasText() {
            return abstractText.isPresent();
        }

        /**
         * {@return whether the PDF was read for author keywords (true even when it listed none)}
         * Used by: ProfileBuilder (M5) to decide whether to re-read a PDF.
         */
        public boolean hasKeywords() {
            return keywords.isPresent();
        }

        /**
         * {@return a copy of this paper with its text and keywords set; vectors unchanged}
         * Used by: ProfileBuilder (M5) when back-filling text into a stored profile.
         *
         * @param abstractText the abstract read from the PDF
         * @param bodyText     the body text read from the PDF
         * @param keywords     the author keywords the PDF lists, possibly none
         */
        public ProfilePaper withText(String abstractText, String bodyText, List<String> keywords) {
            return new ProfilePaper(relativePath, sha256, title, embedding, abstractView, structureView, bodyView,
                    Optional.of(abstractText), Optional.of(bodyText), Optional.of(keywords));
        }

        /**
         * {@return a copy of this paper with only relativePath changed}
         * Used by: ProfileBuilder (M5) when a PDF was moved or renamed (same hash).
         *
         * @param relativePath the PDF's new path inside the profile folder
         */
        public ProfilePaper at(String relativePath) {
            return new ProfilePaper(relativePath, sha256, title, embedding, abstractView, structureView, bodyView,
                    abstractText, bodyText, keywords);
        }
    }
}
