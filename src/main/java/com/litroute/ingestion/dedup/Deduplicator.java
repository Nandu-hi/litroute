package com.litroute.ingestion.dedup;

import com.litroute.model.PaperCandidate;
import com.litroute.util.Titles;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * M4: collapses candidates that are the same paper so the professor never reviews one twice. The same
 * paper reaches the pipeline more than once routinely - two publishers alert on it, a digest repeats
 * last week's entry, or a batch is re-processed after a crash.
 *
 * Two candidates are the same paper when
 *
 * <ol>
 *   <li>they carry the same DOI (case-insensitive; DOIs are the authoritative identity), or
 *   <li>neither carries a DOI that contradicts the other, and their titles match closely enough - the
 *       fuzzy fallback for papers M3 could not resolve, guarded by an author check so that two
 *       different groups' papers with near-identical titles are not merged.
 * </ol>
 *
 * Merging is a union, never a replacement: the first candidate seen wins every field it has, and the
 * later one only fills fields the first left empty. Enrichment is therefore never undone by a sparser
 * duplicate arriving afterwards.
 */
public final class Deduplicator {

    /** Minimum title similarity (0-1) for two DOI-less candidates to be considered the same paper. */
    static final double SAME_TITLE_THRESHOLD = 0.92;

    /**
     * Input: candidates in priority order (earlier entries win conflicting fields).
     * Output: one entry per distinct paper, in first-seen order, duplicates merged in.
     * Used by: AlertBatchProcessor.process (M4), on M7c's papers plus the new batch, before saving M7c.
     * Example: [A(doi 10.1/X, no abstract), B(doi 10.1/x, abstract "..")] -> [A with abstract ".."]
     */
    public List<PaperCandidate> deduplicate(List<PaperCandidate> candidates) {
        List<PaperCandidate> merged = new ArrayList<>();
        for (PaperCandidate candidate : candidates) {
            int existing = indexOfSamePaper(merged, candidate);
            if (existing < 0) {
                merged.add(candidate);
            } else {
                merged.set(existing, merge(merged.get(existing), candidate));
            }
        }
        return List.copyOf(merged);
    }

    /**
     * Input: the papers merged so far and one more candidate.
     * Output: the index of the merged paper it duplicates, or -1.
     * Used by: {@link #deduplicate}.
     * Example: merged [A, B], candidate same DOI as B -> 1
     */
    private static int indexOfSamePaper(List<PaperCandidate> merged, PaperCandidate candidate) {
        for (int i = 0; i < merged.size(); i++) {
            if (samePaper(merged.get(i), candidate)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Input: two candidates.
     * Output: true if both DOIs match (ignoring case), or, with at most one DOI, titles are &gt;= 0.92 similar and authors agree.
     * Used by: {@link #indexOfSamePaper}; DeduplicatorTest.
     * Example: ("Wave loads: a review", no DOI) vs ("Wave Loads - A Review", doi 10.1/x) -> true; DOIs 10.1/a vs 10.1/b -> false
     */
    static boolean samePaper(PaperCandidate a, PaperCandidate b) {
        Optional<String> doiA = a.doi().map(doi -> doi.toLowerCase(Locale.ROOT));
        Optional<String> doiB = b.doi().map(doi -> doi.toLowerCase(Locale.ROOT));
        if (doiA.isPresent() && doiB.isPresent()) {
            return doiA.equals(doiB);
        }
        // At most one DOI is known, so the DOIs cannot disagree; fall back to title and authors.
        return Titles.similarity(a.title(), b.title()) >= SAME_TITLE_THRESHOLD && authorsAgree(a, b);
    }

    /**
     * Empty author lists agree with anything (most alerts list none); two non-empty lists must share a surname.
     * Input: two candidates.
     * Output: whether their author lists agree.
     * Used by: {@link #samePaper}.
     * Example: [A. Rao, B. Li] vs [Bo Li] -> true; [A. Rao] vs [C. Wu] -> false; [] vs [C. Wu] -> true
     */
    private static boolean authorsAgree(PaperCandidate a, PaperCandidate b) {
        if (a.authors().isEmpty() || b.authors().isEmpty()) {
            return true;
        }
        return a.authors().stream().map(Deduplicator::surname)
                .anyMatch(surname -> b.authors().stream().map(Deduplicator::surname).anyMatch(surname::equals));
    }

    /**
     * Input: an author name.
     * Output: its last word, compacted.
     * Used by: {@link #authorsAgree}.
     * Example: "Maria de la Cruz" -> "cruz"
     */
    private static String surname(String author) {
        String[] parts = author.trim().split("\\s+");
        return Titles.compact(parts[parts.length - 1]);
    }

    /**
     * The winner keeps everything it has; the duplicate only contributes what the winner is missing.
     * Input: the first-seen paper and its duplicate.
     * Output: the union, winner's fields first.
     * Used by: {@link #deduplicate}.
     * Example: winner(venue empty, doi X) + dup(venue "Ocean Eng.", doi Y) -> venue "Ocean Eng.", doi X
     */
    private static PaperCandidate merge(PaperCandidate winner, PaperCandidate duplicate) {
        return new PaperCandidate(
                winner.sourceMessageId(),
                winner.title(),
                winner.authors().isEmpty() ? duplicate.authors() : winner.authors(),
                winner.venue().or(duplicate::venue),
                winner.year().or(duplicate::year),
                winner.articleType().or(duplicate::articleType),
                winner.doi().or(duplicate::doi),
                winner.url().or(duplicate::url),
                winner.abstractSnippet().or(duplicate::abstractSnippet),
                winner.pdfPath().or(duplicate::pdfPath));
    }
}
