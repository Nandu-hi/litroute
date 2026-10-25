package com.litroute.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.litroute.model.PaperCandidate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSON shape of a {@link PaperCandidate} as M7c stores it: absent optionals are simply left out
 * of the file, and the PDF path is kept as a plain string. Plain nullable fields mean Jackson needs no
 * extra module for Optional/Path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record StoredCandidate(
        String sourceMessageId,
        String title,
        List<String> authors,
        String venue,
        Integer year,
        String articleType,
        String doi,
        String url,
        String abstractSnippet,
        String pdfPath) {

    /**
     * Input: a candidate.
     * Output: its JSON shape: empty optionals as null (left out of the file), pdfPath as a string.
     * Used by: EnrichmentCacheStore (M7c) and ScoredQueueStore/SentPaperStore (M7e) when saving.
     * Example: candidate without a DOI -> StoredCandidate with {@code doi == null}
     */
    static StoredCandidate from(PaperCandidate candidate) {
        return new StoredCandidate(
                candidate.sourceMessageId(),
                candidate.title(),
                candidate.authors(),
                candidate.venue().orElse(null),
                candidate.year().orElse(null),
                candidate.articleType().orElse(null),
                candidate.doi().orElse(null),
                candidate.url().orElse(null),
                candidate.abstractSnippet().orElse(null),
                candidate.pdfPath().map(Path::toString).orElse(null));
    }

    /**
     * Input: none (this stored entry).
     * Output: the candidate; IOException if it is no longer valid (e.g. a hand-edited file lost its title).
     * Used by: EnrichmentCacheStore (M7c) and the M7e stores when loading.
     * Example: {@code {title:"X", pdfPath:"/p/x.pdf"}} -> PaperCandidate("X", pdfPath=Optional[/p/x.pdf])
     */
    PaperCandidate toCandidate() throws IOException {
        try {
            return new PaperCandidate(
                    sourceMessageId,
                    title,
                    authors == null ? List.of() : authors,
                    Optional.ofNullable(venue),
                    Optional.ofNullable(year),
                    Optional.ofNullable(articleType),
                    Optional.ofNullable(doi),
                    Optional.ofNullable(url),
                    Optional.ofNullable(abstractSnippet),
                    Optional.ofNullable(pdfPath).map(Path::of));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Corrupt candidate entry \"" + title + "\": " + e.getMessage(), e);
        }
    }

    /**
     * Input: stored entries.
     * Output: the candidates, in order; IOException at the first invalid one.
     * Used by: EnrichmentCacheStore.load (M7c).
     * Example: {@code toCandidates([s1, s2])} -> {@code [c1, c2]}
     */
    static List<PaperCandidate> toCandidates(List<StoredCandidate> stored) throws IOException {
        List<PaperCandidate> candidates = new ArrayList<>();
        for (StoredCandidate entry : stored) {
            candidates.add(entry.toCandidate());
        }
        return candidates;
    }
}
