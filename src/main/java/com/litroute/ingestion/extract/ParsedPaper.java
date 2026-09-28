package com.litroute.ingestion.extract;

import com.litroute.model.PaperCandidate;

/** A paper as read from the email's HTML, plus how usable its link was (decides how it gets enriched). */
record ParsedPaper(PaperCandidate candidate, LinkKind linkKind) {
}
