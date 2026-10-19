package com.litroute.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    public PaperCandidate {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        authors = List.copyOf(authors);
    }

    /** The same paper with its PDF saved elsewhere (or not at all). */
    public PaperCandidate withPdfPath(Optional<Path> newPdfPath) {
        return new PaperCandidate(sourceMessageId, title, authors, venue, year, articleType, doi, url, abstractSnippet,
                newPdfPath);
    }
}
