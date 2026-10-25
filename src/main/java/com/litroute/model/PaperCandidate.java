package com.litroute.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Output of M2 PaperExtractor: one paper mentioned in an alert email. Anything the alert doesn't
 * state (or states only in an unusable link) starts out empty and is filled in from Crossref,
 * OpenAlex and the publisher's article page when those are reachable; whatever still can't be
 * found stays empty rather than being guessed.
 *
 * title is the only required field. Text taken from the email is extracted faithfully (whitespace
 * collapsed, nothing else rewritten) and is never overwritten by looked-up data; looked-up data
 * only fills fields the email left empty.
 *
 * @param sourceMessageId RFC 822 Message-ID of the {@link RawEmail} this came from
 * @param authors         visible author names in order; placeholders such as "others" or "..." are
 *                        dropped, never emitted as names
 * @param venue           journal/conference name, without volume/issue/date decorations
 * @param year            publication year stated for the paper - never the email's own date
 * @param articleType     the alert's own label ("Research article", "Technical Papers", ...)
 * @param doi             bare DOI (no URL prefix, query string, or ".pdf" suffix)
 * @param url             best link to the paper, with any redirect wrapper already unwrapped
 * @param abstractSnippet abstract excerpt shown in the alert; if the alert shows none, the abstract
 *                        Crossref or OpenAlex hold for the paper, if any
 * @param pdfPath         where the paper's PDF was saved locally; empty if no downloadable PDF was
 *                        found (paywalled, no open-access copy, or every lookup source offline)
 */
public record PaperCandidate(
        String sourceMessageId,
        String title,
        List<String> authors,
        Optional<String> venue,
        Optional<Integer> year,
        Optional<String> articleType,
        Optional<String> doi,
        Optional<String> url,
        Optional<String> abstractSnippet,
        Optional<Path> pdfPath) {

    /**
     * Input: every field; title must be non-blank.
     * Output: the candidate, with an immutable copy of the authors; throws if the title is blank.
     * Used by: AlertHtmlParser (M2), the M3 enrichers, Deduplicator (M4), StoredCandidate (M7c/M7e).
     * Example: {@code new PaperCandidate("<id@x>", "Wave Loads", List.of("A. Rao"), ...)} -> candidate; title {@code " "} -> throws
     */
    public PaperCandidate {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        authors = List.copyOf(authors);
    }

    /**
     * Alert sections that hold notes rather than research papers, as publishers label them (ASCE's
     * "Discussions and Closures", "Book Reviews", "Corrections", ...).
     */
    private static final Pattern NON_RESEARCH_TYPE = Pattern.compile("(?i)^(?:book reviews?|discussions?(?: and closures?)?"
            + "|closures?|errat(?:a|um)|corrections?|corrigend(?:a|um)|retractions?(?: notes?)?|editorials?"
            + "|obituar(?:y|ies)|letters? to the editor|comments?(?: and repl(?:y|ies))?|repl(?:y|ies))$");
    /** The same notes recognized by their titles, for alerts that do not label their sections. */
    private static final Pattern NON_RESEARCH_TITLE = Pattern.compile("(?i)^\\s*(?:errat(?:a|um)\\b|corrigend(?:a|um)\\b"
            + "|correction(?:\\s+to\\b|\\s*:)|retraction\\b|discussion\\s+of\\b|closure\\s+to\\b|comment\\s+on\\b"
            + "|reply\\s+to\\b)");

    /**
     * Whether this is a research paper rather than a note about one (book review, discussion, erratum,
     * editorial...), by section label or title. A book review can read like a student's papers, so notes never reach M6.
     * Input: none (this candidate's articleType and title).
     * Output: true for a research paper, false for a note.
     * Used by: AlertHtmlParser (M2) to drop notes; RelevanceScorer (M6) as a guard.
     * Example: articleType "Book Reviews" -> false; title "Erratum: Wave loads" -> false; "Wave loads on piles" -> true
     */
    public boolean isResearch() {
        boolean noteSection = articleType.map(type -> NON_RESEARCH_TYPE.matcher(type.strip()).matches()).orElse(false);
        return !noteSection && !NON_RESEARCH_TITLE.matcher(title).find();
    }

    /**
     * Input: the new PDF location, or empty for none.
     * Output: a copy of this candidate with only pdfPath changed.
     * Used by: ScoredQueueStore (M7e) to point an entry at the student's copy of the PDF.
     * Example: {@code withPdfPath(Optional.of(matched/alice/x.pdf))} -> same paper, pdfPath = matched/alice/x.pdf
     */
    public PaperCandidate withPdfPath(Optional<Path> newPdfPath) {
        return new PaperCandidate(sourceMessageId, title, authors, venue, year, articleType, doi, url, abstractSnippet,
                newPdfPath);
    }
}
