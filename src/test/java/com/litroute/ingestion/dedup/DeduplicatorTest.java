package com.litroute.ingestion.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.litroute.model.PaperCandidate;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class DeduplicatorTest {

    private final Deduplicator deduplicator = new Deduplicator();

    private static PaperCandidate paper(String messageId, String title, List<String> authors, String doi) {
        return new PaperCandidate(messageId, title, authors, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.ofNullable(doi), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    public void emptyInputGivesEmptyOutput() {
        assertTrue(deduplicator.deduplicate(List.of()).isEmpty());
    }

    @Test
    public void twoCandidatesWithTheSameDoiMergeIntoOne() {
        PaperCandidate fromSpringer = paper("<a@mail>", "Hygrothermal analysis of ceramic coatings",
                List.of("Gilberto Gomes"), "10.1186/s43065-024-00115-x");
        PaperCandidate fromScholar = paper("<b@mail>", "HYGROTHERMAL ANALYSIS OF CERAMIC COATINGS!",
                List.of(), "10.1186/s43065-024-00115-x");

        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(fromSpringer, fromScholar));

        assertEquals(1, merged.size());
        assertEquals(fromSpringer, merged.get(0));
    }

    @Test
    public void doisAreComparedIgnoringCase() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "One title", List.of(), "10.1234/AbC"),
                paper("<b@mail>", "A completely different title", List.of(), "10.1234/abc")));

        assertEquals(1, merged.size());
    }

    @Test
    public void differentDoisAreNeverMergedEvenWhenTheTitlesMatch() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "Seismic retrofitting of masonry walls", List.of(), "10.1234/one"),
                paper("<b@mail>", "Seismic retrofitting of masonry walls", List.of(), "10.1234/two")));

        assertEquals("a shared title cannot override two authoritative, different DOIs", 2, merged.size());
    }

    @Test
    public void doilessCandidatesMergeOnTitleAlone() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "Seismic retrofitting of masonry walls", List.of(), null),
                paper("<b@mail>", "Seismic Retrofitting of Masonry Walls", List.of(), null)));

        assertEquals(1, merged.size());
    }

    @Test
    public void aDoilessCandidateMergesIntoTheEnrichedOneThatHasADoi() {
        PaperCandidate enriched = paper("<a@mail>", "Seismic retrofitting of masonry walls", List.of(), "10.1234/one");
        PaperCandidate sparse = paper("<b@mail>", "Seismic retrofitting of masonry walls", List.of(), null);

        assertEquals(1, deduplicator.deduplicate(List.of(enriched, sparse)).size());
        assertEquals(1, deduplicator.deduplicate(List.of(sparse, enriched)).size());
    }

    @Test
    public void differentPapersAreBothKept() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "Seismic retrofitting of masonry walls", List.of(), null),
                paper("<b@mail>", "Deep learning for traffic flow prediction", List.of(), null)));

        assertEquals(2, merged.size());
    }

    @Test
    public void twoGroupsWithTheSameTitleAndNoDoiAreNotMerged() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "A review of concrete durability", List.of("Gilberto Gomes"), null),
                paper("<b@mail>", "A review of concrete durability", List.of("Hiroshi Tanaka"), null)));

        assertEquals("no shared author, so the matching titles are treated as a coincidence", 2, merged.size());
    }

    @Test
    public void aSharedSurnameIsEnoughForTheFuzzyPathToMerge() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "A review of concrete durability", List.of("G. Gomes", "Ana Souza"), null),
                paper("<b@mail>", "A review of concrete durability", List.of("Gilberto Gomes"), null)));

        assertEquals(1, merged.size());
    }

    @Test
    public void theFirstCandidateWinsEveryFieldItHasAndTheDuplicateFillsTheRest() {
        PaperCandidate first = new PaperCandidate("<a@mail>", "Concrete durability", List.of("Gilberto Gomes"),
                Optional.of("Journal of Infrastructure"), Optional.empty(), Optional.empty(),
                Optional.of("10.1234/one"), Optional.empty(), Optional.empty(), Optional.empty());
        PaperCandidate second = new PaperCandidate("<b@mail>", "Concrete durability", List.of("Someone Else"),
                Optional.of("A worse venue string"), Optional.of(2025), Optional.of("Research article"),
                Optional.of("10.1234/one"), Optional.of("https://doi.org/10.1234/one"),
                Optional.of("An abstract."), Optional.of(Path.of("/pdfs/one.pdf")));

        PaperCandidate merged = deduplicator.deduplicate(List.of(first, second)).get(0);

        assertEquals("<a@mail>", merged.sourceMessageId());
        assertEquals(List.of("Gilberto Gomes"), merged.authors());
        assertEquals(Optional.of("Journal of Infrastructure"), merged.venue());
        assertEquals(Optional.of(2025), merged.year());
        assertEquals(Optional.of("Research article"), merged.articleType());
        assertEquals(Optional.of("https://doi.org/10.1234/one"), merged.url());
        assertEquals(Optional.of("An abstract."), merged.abstractSnippet());
        assertEquals(Optional.of(Path.of("/pdfs/one.pdf")), merged.pdfPath());
    }

    @Test
    public void aThirdCopyMergesIntoTheAlreadyMergedRecord() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "Concrete durability", List.of(), "10.1234/one"),
                paper("<b@mail>", "Concrete durability", List.of(), "10.1234/one"),
                paper("<c@mail>", "Concrete durability", List.of(), "10.1234/one")));

        assertEquals(1, merged.size());
        assertEquals("<a@mail>", merged.get(0).sourceMessageId());
    }

    @Test
    public void firstSeenOrderIsPreserved() {
        List<PaperCandidate> merged = deduplicator.deduplicate(List.of(
                paper("<a@mail>", "First paper", List.of(), "10.1234/one"),
                paper("<b@mail>", "Second paper", List.of(), "10.1234/two"),
                paper("<c@mail>", "First paper again", List.of(), "10.1234/one")));

        assertEquals(List.of("First paper", "Second paper"), merged.stream().map(PaperCandidate::title).toList());
    }
}
