package com.litroute.ingestion.extract;

import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M2: turns one alert email into zero or more {@link PaperCandidate}s, in two stages.
 *
 * <ol>
 *   <li>Find the papers, by one of two means ({@link ExtractionMode}), chosen per run:
 *       <ul>
 *         <li>Pattern matching: read each paper's title, authors, venue and links out of the email's HTML, and
 *             classify each paper's link as readable (direct or decodable) or opaque (encrypted tracking token).
 *         <li>Local AI model ({@link AiTitleExtractor}): the model names the titles, and what pattern matching read
 *             about them supplies their details. A title pattern matching doesn't know is kept only once
 *             enrichment gives it a DOI, which is what shows it to be a paper and not a heading.
 *       </ul>
 *   <li>Enrich (via the {@link PaperEnricher}): find the DOI and missing metadata and save the PDF locally.
 *       An opaque link can only be resolved by title through Crossref/OpenAlex; a readable link can
 *       additionally be checked against the publisher's own page.
 * </ol>
 *
 * Never throws for ordinary failures: a paper whose enrichment fails is returned as parsed, and an
 * email with no papers in it yields an empty list. A model that can't be reached is not ordinary, since the
 * run was asked to use it: see {@link #extract}.
 */
public final class PaperExtractor {

    private static final Logger LOG = Logger.getLogger(PaperExtractor.class.getName());

    private final AlertHtmlParser parser = new AlertHtmlParser();
    private final PaperEnricher enricher;
    private final Optional<AiTitleExtractor> aiTitles;

    /**
     * A {@link ExtractionMode#PATTERN} extractor.
     *
     * @param enricher the M3 enricher applied to each paper
     */
    public PaperExtractor(PaperEnricher enricher) {
        this.enricher = enricher;
        this.aiTitles = Optional.empty();
    }

    /**
     * A {@link ExtractionMode#LLM} extractor.
     *
     * @param enricher the M3 enricher applied to each paper
     * @param aiTitles the local model's title reader
     */
    public PaperExtractor(PaperEnricher enricher, AiTitleExtractor aiTitles) {
        this.enricher = enricher;
        this.aiTitles = Optional.of(aiTitles);
    }

    /** {@return a parse-only extractor: no network access and no downloads} */
    public static PaperExtractor offline() {
        return new PaperExtractor(PaperEnricher.NONE);
    }

    /**
     * {@return the email's papers, enriched where possible; [] if none}. A paper whose enrichment throws is kept
     * as parsed.
     * Example: SpringerOpen alert listing 3 papers -> 3 candidates with DOI, abstract and pdfPath filled
     *
     * @param email one alert email
     * @throws java.io.UncheckedIOException if the local model can't be reached, so the email is not recorded
     */
    public List<PaperCandidate> extract(RawEmail email) {
        List<ParsedPaper> blocks = parser.blocks(email);
        if (aiTitles.isEmpty()) {
            return List.copyOf(enriched(AlertHtmlParser.research(blocks)));
        }
        AiTitleExtractor.ModelPapers found;
        try {
            found = aiTitles.get().papersIn(email, blocks);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<PaperCandidate> candidates = enriched(found.known());
        for (ParsedPaper named : found.unknown()) {
            confirmed(named).ifPresent(candidates::add);
        }
        return List.copyOf(candidates);
    }

    /**
     * Input: parsed papers.
     * Output: each one enriched, or as parsed if its enrichment threw.
     * Used by: {@link #extract}.
     * Example: [paper A] -> [paper A with DOI and pdfPath]
     */
    private List<PaperCandidate> enriched(List<ParsedPaper> papers) {
        List<PaperCandidate> candidates = new ArrayList<>();
        for (ParsedPaper parsed : papers) {
            try {
                candidates.add(enricher.enrich(parsed.candidate(), parsed.linkKind()));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Enrichment failed for \"" + parsed.candidate().title()
                        + "\"; keeping what the email itself says", e);
                candidates.add(parsed.candidate());
            }
        }
        return candidates;
    }

    /**
     * Input: a title only the model named.
     * Output: the enriched paper if it has a DOI; else empty and a log line (no lookup knows it, so it may be a heading, not a paper).
     * Used by: {@link #extract}.
     * Example: "Wave loads on piles" with a DOI from Crossref -> the paper; "Journal news and updates" -> empty
     */
    private Optional<PaperCandidate> confirmed(ParsedPaper named) {
        String title = named.candidate().title();
        try {
            PaperCandidate enriched = enricher.enrich(named.candidate(), named.linkKind());
            if (enriched.doi().isPresent()) {
                return Optional.of(enriched);
            }
            LOG.info("Left out \"" + title + "\": only the local model named it, and no lookup found its DOI.");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Left out \"" + title + "\": only the local model named it, and its lookup failed", e);
        }
        return Optional.empty();
    }
}
