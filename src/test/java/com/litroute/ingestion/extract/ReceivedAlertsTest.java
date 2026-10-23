package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.testing.SampleMails;
import com.litroute.testing.SampleMails.Sample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * M2 on the six alerts exactly as they reached the pipeline's inbox: the HTML parts of the real messages
 * (forwarded by Prof. Badal, then forwarded on again), in {@code src/test/resources/samples/received/}.
 * {@link SampleMails} holds tidied copies of the same alerts, with headings for section labels, direct DOI
 * links and no Gmail-added links; the real ones turned up papers the tidied ones never showed: a footer
 * address, the journal's own name, the email's banner as an article type, and lost papers. Each real
 * email must give the papers its tidied copy gives.
 */
public class ReceivedAlertsTest {

    private final PaperExtractor extractor = PaperExtractor.offline();

    private List<PaperCandidate> received(Sample sample) throws IOException {
        String html = Files.readString(Path.of("src/test/resources/samples/received", sample.file()));
        return extractor.extract(new RawEmail("<received-" + sample.file() + ">", sample.subject(),
                SampleMails.FORWARDER, sample.receivedAt(), Optional.of(html), Optional.empty()));
    }

    /** What the email states about a paper; the links (tracking tokens in the real ones) are left out. */
    private record Stated(String title, List<String> authors, Optional<String> venue, Optional<Integer> year,
            Optional<String> articleType) {

        static Stated of(PaperCandidate paper) {
            return new Stated(paper.title(), paper.authors(), paper.venue(), paper.year(),
                    paper.articleType().map(type -> type.toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    public void eachReceivedAlertGivesThePapersOfItsTidiedCopy() throws IOException {
        for (Sample sample : SampleMails.ALL) {
            List<Stated> tidied = extractor.extract(sample.email()).stream().map(Stated::of).toList();

            List<Stated> real = received(sample).stream().map(Stated::of).toList();

            assertEquals(sample.file(), sample.papers(), real.size());
            assertEquals(sample.file(), tidied, real);
        }
    }

    /** "London, N1 9XW," is a Google Maps link Gmail made of the footer address, over a "© 2025" line. */
    @Test
    public void gmailsAddressLinksAndCopyrightLinesAreNotPapers() throws IOException {
        for (Sample sample : List.of(SampleMails.SPRINGER_RELEVANT, SampleMails.SPRINGER_IRRELEVANT, SampleMails.ASCE)) {
            for (PaperCandidate paper : received(sample)) {
                assertTrue(paper.title(), paper.venue().map(venue -> !venue.startsWith("©")).orElse(true));
                assertTrue(paper.title(), !paper.title().startsWith("London") && !paper.title().contains("Alexander Bell"));
            }
        }
    }

    @Test
    public void theJournalsOwnNameInTheAsceHeaderIsNotAPaper() throws IOException {
        List<PaperCandidate> papers = received(SampleMails.ASCE);

        assertTrue(papers.stream().noneMatch(paper -> paper.title().equals("Natural Hazards Review")));
        assertTrue(papers.stream().allMatch(paper -> paper.venue().equals(Optional.of("Natural Hazards Review"))));
    }

    /**
     * ASCE's section labels are plain lines: each heads every paper under it, the last paper of a section
     * does not take the next section's label, and the email's banner is never a type.
     */
    @Test
    public void asceSectionLabelsHeadEveryPaperUnderThem() throws IOException {
        Map<String, String> types = received(SampleMails.ASCE).stream()
                .collect(Collectors.toMap(PaperCandidate::title, paper -> paper.articleType().orElse("")));

        assertTrue(types.values().stream().noneMatch(type -> type.startsWith("New articles")));
        assertEquals("Technical Papers", byTitleStart(types, "Mapping and Prioritizing Barriers"));
        assertEquals("Technical Papers", byTitleStart(types, "Urban Waterlogging Risk Measurement"));
        assertEquals("Case Studies", byTitleStart(types, "Climate-Adaptive Cost–Benefit Analysis"));
        assertEquals("Technical Notes", byTitleStart(types, "Preparing for Inevitable Surprise"));
    }

    /**
     * The issue's discussion, closure, book reviews and erratum are notes, not papers: they never leave
     * M2, whether their section says so or only their title does. Footer menu links are not papers either.
     */
    @Test
    public void asceNotesNeverLeaveExtraction() throws IOException {
        List<PaperCandidate> papers = received(SampleMails.ASCE);

        assertEquals(17, papers.size());
        assertTrue(papers.stream().allMatch(PaperCandidate::isResearch));
        for (String note : List.of("Discussion of “Revisions", "Closure to “Revisions", "Review of U.S. Emergency Management",
                "Review of Fire and Flood", "Erratum for “Advances")) {
            assertTrue(note, papers.stream().noneMatch(paper -> paper.title().startsWith(note)));
        }
        assertTrue("footer menu links are not papers",
                papers.stream().noneMatch(paper -> paper.title().equals("ACCESSIBILITY & DEI")));
    }

    /** Google Scholar sets "[PDF]" in a span of its own before the title link. */
    @Test
    public void aScholarTitleWithItsFormatTagBesideTheLinkIsFound() throws IOException {
        List<PaperCandidate> papers = received(SampleMails.GOOGLE_SCHOLAR);

        assertEquals(1, papers.size());
        assertEquals("An Intensity Measure‐Based Methodology for Ground Motion Orientation in Seismic Structural "
                + "Assessment", papers.get(0).title());
        assertEquals(Optional.of("10.1002/eqe.70286"), papers.get(0).doi());
    }

    private static <T> T byTitleStart(Map<String, T> byTitle, String titleStart) {
        Function<String, Boolean> matches = title -> title.startsWith(titleStart);
        return byTitle.entrySet().stream().filter(entry -> matches.apply(entry.getKey())).findFirst()
                .orElseThrow(() -> new AssertionError("no paper titled " + titleStart + "...")).getValue();
    }
}
