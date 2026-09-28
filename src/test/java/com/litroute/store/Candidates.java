package com.litroute.store;

import com.litroute.model.PaperCandidate;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Shared sample candidates for the store tests: one with every field set, one with almost none.
 * The full one is a real paper (DOI, title, authors and venue as Crossref holds them), so the
 * fixture exercises the field shapes the pipeline actually produces rather than invented ones.
 */
final class Candidates {

    private Candidates() {
    }

    static PaperCandidate full() {
        return new PaperCandidate("<m1@mail.gmail.com>",
                "Hygrothermal analysis of ceramic coating durability under thermal fluctuations "
                        + "on building facades in Brasília",
                List.of("Gilberto Gomes", "Luis Silva", "Elton Bauer"),
                Optional.of("Journal of Infrastructure Preservation and Resilience"), Optional.of(2025),
                Optional.of("Research article"), Optional.of("10.1186/s43065-024-00115-x"),
                Optional.of("https://doi.org/10.1186/s43065-024-00115-x"),
                Optional.of("Building facades are continuously subjected to various external agents, such as "
                        + "solar radiation and temperature fluctuations, which significantly contribute to the "
                        + "degradation of building materials."),
                Optional.of(Path.of("/home/user/.litroute/data/pdfs/10.1186_s43065-024-00115-x.pdf")));
    }

    static PaperCandidate sparse() {
        return new PaperCandidate("<m2@mail.gmail.com>", "A paper the email told us almost nothing about",
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
