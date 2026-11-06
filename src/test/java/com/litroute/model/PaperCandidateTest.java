package com.litroute.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** Which alert entries are research papers and which are notes about them, which M2 drops and M6 never matches. */
public class PaperCandidateTest {

    private static PaperCandidate titled(String title, String articleType) {
        return new PaperCandidate("<id@test>", title, List.of(), Optional.empty(), Optional.empty(),
                Optional.ofNullable(articleType), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Titles and sections from the Bulletin of Earthquake Engineering and Engineering Structures alerts of November 2026. */
    @Test
    public void aSpecialIssueIntroductionOrACorrigendumIsNotResearch() {
        assertFalse(titled("Special issue: On the seismic behavior of masonry structures: recent advances. Selection of "
                + "expanded papers presented at the 18th International Brick and Block Masonry Conference IB2MaC held in "
                + "Birmingham, UK (July 21–24, 2024)", null).isResearch());
        assertFalse(titled("Corrigendum to “Eccentric compression behavior of filament-wound GFRP-steel composite "
                + "tube-confined ultra-high strength concrete short columns” [Eng Struct 367 (2026) 123614]", "Erratum")
                .isResearch());
        assertTrue(titled("Cumulative fragility curves for seismic vulnerability assessment of damaged masonry buildings "
                + "subjected to differential settlements", "Research article").isResearch());
    }
}
