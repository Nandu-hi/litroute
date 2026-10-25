package com.litroute.ingestion.extract;

import com.litroute.model.PaperCandidate;

/**
 * Second stage of M2: takes what the email itself says about a paper and fills in what it doesn't
 * (DOI, missing metadata, a locally saved PDF). Implementations must not throw for ordinary
 * failures such as being offline - they return the candidate unchanged instead.
 */
public interface PaperEnricher {

    /** Leaves every candidate exactly as extracted from the email (no network, no downloads). */
    PaperEnricher NONE = (candidate, linkKind) -> candidate;

    /**
     * Input: a paper as parsed from the email, and how usable its link is.
     * Output: the paper with DOI, missing metadata and pdfPath filled where found; unchanged if nothing could be.
     * Used by: PaperExtractor.extract (M2); CachingPaperEnricher wraps OnlinePaperEnricher (M3).
     * Example: ("Wave loads on piles", OPAQUE) -> same paper + doi "10.1061/x", abstract, pdfPath pdfs/x.pdf
     */
    PaperCandidate enrich(PaperCandidate extracted, LinkKind linkKind);
}
