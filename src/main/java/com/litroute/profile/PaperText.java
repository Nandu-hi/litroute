package com.litroute.profile;

import java.util.List;

/**
 * What M5 reads out of one reference PDF: the parts of the paper that say what it is about, already
 * cleaned of page furniture (running headers, page numbers) and end matter (references,
 * acknowledgements).
 *
 * @param title        the paper's title; never blank (falls back to the file name)
 * @param abstractText the abstract, or - when no abstract heading was found - the opening text of
 *                     the first page; never blank
 * @param headings     section and subsection headings in reading order, e.g. "1 Introduction"
 * @param bodyChunks   passages of the body, each short enough for one embedding, sampled evenly
 *                     across the paper
 * @param keywords     the keywords the authors listed ("Keywords: ..."), in their order; empty if the
 *                     paper lists none
 */
public record PaperText(String title, String abstractText, List<String> headings, List<String> bodyChunks,
        List<String> keywords) {

    /**
     * Input: every field; title and abstractText non-blank.
     * Output: the record, with immutable copies of the lists; throws on a blank title or abstract.
     * Used by: PdfTextExtractor (M5), RelevanceScorer (M6) for a candidate's PDF.
     * Example: {@code new PaperText("Wave loads", "", ...)} -> throws "abstractText must not be blank"
     */
    public PaperText {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (abstractText == null || abstractText.isBlank()) {
            throw new IllegalArgumentException("abstractText must not be blank");
        }
        headings = List.copyOf(headings);
        bodyChunks = List.copyOf(bodyChunks);
        keywords = List.copyOf(keywords);
    }

    /**
     * A paper's text without author keywords.
     *
     * @param title        the title
     * @param abstractText the abstract
     * @param headings     the section headings
     * @param bodyChunks   the sampled body passages
     */
    public PaperText(String title, String abstractText, List<String> headings, List<String> bodyChunks) {
        this(title, abstractText, headings, bodyChunks, List.of());
    }

    /**
     * {@return the headings joined by "; ", then ". ", then the body passages}: what the paper says beyond its
     * title and abstract.
     * Example: headings [1 Intro, 2 Method], chunks [a, b] -> "1 Intro; 2 Method. a b"
     */
    public String bodyText() {
        return String.join("; ", headings) + ". " + String.join(" ", bodyChunks);
    }
}
