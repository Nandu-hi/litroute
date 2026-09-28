package com.litroute.ingestion.extract;

import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M2: turns one alert email into zero or more {@link PaperCandidate}s, in two stages.
 *
 * <ol>
 *   <li>Parse (offline): read each paper's title, authors, venue and links out of the email's HTML, and
 *       classify each paper's link as readable (direct or decodable) or opaque (encrypted tracking token).
 *   <li>Enrich (via the {@link PaperEnricher}): find the DOI and missing metadata and save the PDF locally.
 *       An opaque link can only be resolved by title through Crossref/OpenAlex; a readable link can
 *       additionally be checked against the publisher's own page.
 * </ol>
 *
 * Never throws for ordinary failures: a paper whose enrichment fails is returned as parsed, and an
 * email with no papers in it yields an empty list.
 */
public final class PaperExtractor {

    private static final Logger LOG = Logger.getLogger(PaperExtractor.class.getName());

    private final AlertHtmlParser parser = new AlertHtmlParser();
    private final PaperEnricher enricher;

    public PaperExtractor(PaperEnricher enricher) {
        this.enricher = enricher;
    }

    /** Parse-only extractor: no network access and no downloads. */
    public static PaperExtractor offline() {
        return new PaperExtractor(PaperEnricher.NONE);
    }

    public List<PaperCandidate> extract(RawEmail email) {
        List<PaperCandidate> candidates = new ArrayList<>();
        for (ParsedPaper parsed : parser.parse(email)) {
            try {
                candidates.add(enricher.enrich(parsed.candidate(), parsed.linkKind()));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Enrichment failed for \"" + parsed.candidate().title()
                        + "\"; keeping what the email itself says", e);
                candidates.add(parsed.candidate());
            }
        }
        return List.copyOf(candidates);
    }
}
