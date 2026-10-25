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
     * Input: every field except keywords.
     * Output: a profile with no keywords.
     * Used by: older call sites and tests that predate keywords.
     * Example: {@code new Profile("alice", emails, papers, "specter2", now).keywords()} -> {@code []}
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
         * Input: every field except keywords.
         * Output: a paper whose keywords were not read ({@code keywords()} empty).
         * Used by: ProfileStore (M7d) for papers saved before keywords were kept.
         * Example: {@code new ProfilePaper(..., abstractText, bodyText).hasKeywords()} -> false
         */
        public ProfilePaper(String relativePath, String sha256, String title, float[] embedding, float[] abstractView,
                Optional<float[]> structureView, Optional<float[]> bodyView, Optional<String> abstractText,
                Optional<String> bodyText) {
            this(relativePath, sha256, title, embedding, abstractView, structureView, bodyView, abstractText, bodyText,
                    Optional.empty());
        }

        /**
         * Input: path, hash, title and vectors only.
         * Output: a paper with no text and no keywords.
         * Used by: ProfileStore (M7d) for papers saved before text was kept; tests.
         * Example: {@code new ProfilePaper(path, sha, title, emb, abs, s, b).hasText()} -> false
         */
        public ProfilePaper(String relativePath, String sha256, String title, float[] embedding, float[] abstractView,
                Optional<float[]> structureView, Optional<float[]> bodyView) {
            this(relativePath, sha256, title, embedding, abstractView, structureView, bodyView, Optional.empty(),
                    Optional.empty(), Optional.empty());
        }

        /**
         * Input: none.
         * Output: whether the abstract text was kept, so M6 can match its words without re-reading the PDF.
         * Used by: ProfileBuilder (M5) to back-fill text; RelevanceScorer (M6) lexical matching.
         * Example: a paper loaded from a pre-text store -> false
         */
        public boolean hasText() {
            return abstractText.isPresent();
        }

        /**
         * Input: none.
         * Output: whether the PDF was read for author keywords (true even when it listed none).
         * Used by: ProfileBuilder (M5) to decide whether to re-read a PDF.
         * Example: keywords {@code Optional.of([])} -> true; {@code Optional.empty()} -> false
         */
        public boolean hasKeywords() {
            return keywords.isPresent();
        }

        /**
         * Input: the abstract, body text and author keywords read from the PDF.
         * Output: a copy of this paper with those set; vectors unchanged.
         * Used by: ProfileBuilder (M5) when back-filling text into a stored profile.
         * Example: {@code p.withText("We study...", "1 Introduction ...", List.of("piles")).hasText()} -> true
         */
        public ProfilePaper withText(String abstractText, String bodyText, List<String> keywords) {
            return new ProfilePaper(relativePath, sha256, title, embedding, abstractView, structureView, bodyView,
                    Optional.of(abstractText), Optional.of(bodyText), Optional.of(keywords));
        }

        /**
         * Input: the PDF's new path inside the profile folder.
         * Output: a copy of this paper with only relativePath changed.
         * Used by: ProfileBuilder (M5) when a PDF was moved or renamed (same hash).
         * Example: {@code p.at("2024/rao.pdf").relativePath()} -> {@code "2024/rao.pdf"}
         */
        public ProfilePaper at(String relativePath) {
            return new ProfilePaper(relativePath, sha256, title, embedding, abstractView, structureView, bodyView,
                    abstractText, bodyText, keywords);
        }
    }
}
