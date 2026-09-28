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

    PaperCandidate enrich(PaperCandidate extracted, LinkKind linkKind);
}
