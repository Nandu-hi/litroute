package com.litroute.ingestion.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.litroute.model.PaperCandidate;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * M4 deduplication. The papers are real ones from the sample mails, because the case this stage
 * exists for is real: the same paper reaches the inbox twice, once from the publisher's own alert
 * and once from a Google Scholar recommendation, written differently each time.
 *
 * The message IDs stand for the mail each copy arrived in.
 */
public class DeduplicatorTest {

    private static final String HYGRO_TITLE = "Hygrothermal analysis of ceramic coating durability "
            + "under thermal fluctuations on building facades in Brasília";
    private static final String HYGRO_DOI = "10.1186/s43065-024-00115-x";
    private static final String SSI_TITLE = "Advancing soil-structure interaction (SSI): a comprehensive "
            + "review of current practices, challenges, and future directions";
    private static final String SSI_DOI = "10.1186/s43065-025-00118-2";
    private static final String MAUI_TITLE = "Roadway Traffic Assessment of the 2023 Maui Wildfires";
    private static final String MAUI_DOI = "10.1061/nhrefo.nheng-2502";

    private final Deduplicator deduplicator = new Deduplicator();

    private static PaperCandidate paper(String messageId, String title, List<String> authors, String doi) {
        return new PaperCandidate(messageId, title, authors, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.ofNullable(doi), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    public void emptyInputGivesEmptyOutput() {
        assertTrue(deduplicator.deduplicate(List.of()).isEmpty());
    }

    /**
     * The publisher's alert and a Scholar recommendation for one paper: same DOI, but Scholar shouts
     * the title and lists no authors. One paper must come out, keeping the fuller first copy.
     */
    @Test
    public void thesameDoiFromTwoDifferentAlertsMergesIntoOne() {
        PaperCandidate fromSpringer = paper("<springeropen@mail>", HYGRO_TITLE,
                List.of("Gilberto Gomes", "Luis Silva", "Elton Bauer"), HYGRO_DOI);
        PaperCandidate fromScholar = paper("<scholar@mail>", HYGRO_TITLE.toUpperCase() + "!", List.of(), HYGRO_DOI);

        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(fromSpringer, fromScholar));

        assertEquals(1, merged.size());
        assertEquals(fromSpringer, merged.get(0));
    }

    /** Publishers are inconsistent about DOI case; the same DOI in two casings is one paper. */
    @Test
    public void doisAreComparedIgnoringCase() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", MAUI_TITLE, List.of(), "10.1061/NHREFO.NHENG-2502"),
                paper("<b@mail>", MAUI_TITLE, List.of(), MAUI_DOI)));

        assertEquals(1, merged.size());
    }

    /**
     * Two different DOIs are two different records, whatever the titles say. (A shared title happens
     * for real between a discussion and the paper it discusses, and between a preprint and the
     * published version - merging those would lose one of them.)
     */
    @Test
    public void differentDoisAreNeverMergedEvenWhenTheTitlesMatch() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", HYGRO_TITLE, List.of(), HYGRO_DOI),
                paper("<b@mail>", HYGRO_TITLE, List.of(), SSI_DOI)));

        assertEquals("a shared title cannot override two authoritative, different DOIs", 2, merged.size());
    }

    /** With no DOI on either side - both alerts opaque, both lookups down - the title has to carry it. */
    @Test
    public void doilessCandidatesMergeOnTitleAlone() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", MAUI_TITLE, List.of(), null),
                paper("<b@mail>", "Roadway traffic assessment of the 2023 Maui wildfires.", List.of(), null)));

        assertEquals(1, merged.size());
    }

    /** One copy was enriched and one was not; the order they arrive in must not change the answer. */
    @Test
    public void aDoilessCandidateMergesIntoTheEnrichedOneThatHasADoi() {
        PaperCandidate enriched = paper("<a@mail>", MAUI_TITLE, List.of(), MAUI_DOI);
        PaperCandidate sparse = paper("<b@mail>", MAUI_TITLE, List.of(), null);

        assertEquals(1, deduplicator.deduplicate(List.of(enriched, sparse)).size());
        assertEquals(1, deduplicator.deduplicate(List.of(sparse, enriched)).size());
    }

    @Test
    public void differentPapersAreBothKept() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", MAUI_TITLE, List.of(), null),
                paper("<b@mail>", SSI_TITLE, List.of(), null)));

        assertEquals(2, merged.size());
    }

    /**
     * Review titles repeat across journals. With no DOI and no author in common, two papers that
     * merely share a title are a coincidence, not a duplicate.
     */
    @Test
    public void twoPapersSharingOnlyATitleAndNoAuthorAreNotMerged() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "A review of concrete durability", List.of("Gilberto Gomes"), null),
                paper("<b@mail>", "A review of concrete durability", List.of("Shanshan Tang"), null)));

        assertEquals("no shared author, so the matching titles are treated as a coincidence", 2, merged.size());
    }

    /**
     * Google Scholar abbreviates given names ("G Gomes") where the publisher spells them out. The
     * surname is what survives both renderings, so it is what the fuzzy path matches on.
     */
    @Test
    public void aSharedSurnameIsEnoughForTheFuzzyPathToMerge() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<scholar@mail>", HYGRO_TITLE, List.of("G. Gomes", "L. Silva"), null),
                paper("<springeropen@mail>", HYGRO_TITLE, List.of("Gilberto Gomes"), null)));

        assertEquals(1, merged.size());
    }

    /** The first copy owns every field it has; the duplicate may only fill what the first left empty. */
    @Test
    public void theFirstCandidateWinsEveryFieldItHasAndTheDuplicateFillsTheRest() {
        PaperCandidate first = new PaperCandidate("<springeropen@mail>", HYGRO_TITLE,
                List.of("Gilberto Gomes"), Optional.of("Journal of Infrastructure Preservation and Resilience"),
                Optional.empty(), Optional.empty(), Optional.of(HYGRO_DOI), Optional.empty(), Optional.empty(),
                Optional.empty());
        PaperCandidate second = new PaperCandidate("<scholar@mail>", HYGRO_TITLE, List.of("G Gomes"),
                Optional.of("J. Infrastruct. Preserv. Resil."), Optional.of(2025), Optional.of("RESEARCH"),
                Optional.of(HYGRO_DOI), Optional.of("https://doi.org/" + HYGRO_DOI),
                Optional.of("Building facades are continuously subjected to various external agents."),
                Optional.of(Path.of("/home/user/.litroute/data/pdfs/10.1186_s43065-024-00115-x.pdf")));

        PaperCandidate merged = deduplicator.deduplicate(List.of(first, second)).get(0);

        assertEquals("<springeropen@mail>", merged.sourceMessageId());
        assertEquals(List.of("Gilberto Gomes"), merged.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), merged.venue());
        assertEquals(Optional.of(2025), merged.year());
        assertEquals(Optional.of("RESEARCH"), merged.articleType());
        assertEquals(Optional.of("https://doi.org/" + HYGRO_DOI), merged.url());
        assertTrue(merged.abstractSnippet().orElseThrow().startsWith("Building facades"));
        assertEquals(Optional.of(Path.of("/home/user/.litroute/data/pdfs/10.1186_s43065-024-00115-x.pdf")),
                merged.pdfPath());
    }

    @Test
    public void aThirdCopyMergesIntoTheAlreadyMergedRecord() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", HYGRO_TITLE, List.of(), HYGRO_DOI),
                paper("<b@mail>", HYGRO_TITLE, List.of(), HYGRO_DOI),
                paper("<c@mail>", HYGRO_TITLE, List.of(), HYGRO_DOI)));

        assertEquals(1, merged.size());
        assertEquals("<a@mail>", merged.get(0).sourceMessageId());
    }

    /** Papers keep the order they were first seen in, so the digest reads in the order mail arrived. */
    @Test
    public void firstSeenOrderIsPreserved() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", HYGRO_TITLE, List.of(), HYGRO_DOI),
                paper("<b@mail>", SSI_TITLE, List.of(), SSI_DOI),
                paper("<c@mail>", HYGRO_TITLE, List.of(), HYGRO_DOI)));

        assertEquals(List.of(HYGRO_TITLE, SSI_TITLE), merged.stream().map(PaperCandidate::title).toList());
    }
}
