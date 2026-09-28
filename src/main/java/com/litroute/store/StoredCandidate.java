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

    /** @throws IOException if the stored entry is not a valid candidate (e.g. a hand-edited file lost its title) */
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

    static List<PaperCandidate> toCandidates(List<StoredCandidate> stored) throws IOException {
        List<PaperCandidate> candidates = new ArrayList<>();
        for (StoredCandidate entry : stored) {
            candidates.add(entry.toCandidate());
        }
        return candidates;
    }
}
