package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class CompositeMetadataLookupTest {

    /** Answers from a script: each call either returns the answer or, for "down" steps, throws like an offline API. */
    private static final class FakeSource implements MetadataSource {
        private final String name;
        private final Optional<PaperMetadata> answer;
        private final boolean[] downSteps;
        final List<PaperQuery> queries = new ArrayList<>();

        FakeSource(String name, Optional<PaperMetadata> answer, boolean... downSteps) {
            this.name = name;
            this.answer = answer;
            this.downSteps = downSteps;
        }

        static FakeSource offline(String name) {
            return new FakeSource(name, Optional.empty(), true, true, true, true, true, true);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<PaperMetadata> lookup(PaperQuery query) throws IOException {
            int call = queries.size();
            queries.add(query);
            if (call < downSteps.length && downSteps[call]) {
                throw new IOException(name + " is down");
            }
            return answer;
        }
    }

    private static final PaperQuery QUERY = new PaperQuery("Some paper title here", List.of(), Optional.empty());

    private static PaperMetadata crossrefSays() {
        return new PaperMetadata(Optional.of("10.9999/x"), Optional.of("Some paper title here"), List.of("A One", "B Two"),
                Optional.of("Journal J"), Optional.empty(), Optional.of("https://doi.org/10.9999/x"), List.of());
    }

    private static PaperMetadata openAlexSays() {
        return new PaperMetadata(Optional.of("10.9999/x"), Optional.empty(), List.of("Different Author"),
                Optional.of("Other Name"), Optional.of(2025), Optional.empty(), List.of("https://oa.test/x.pdf"));
    }

    @Test
    public void mergesBothSourcesEarlierOnesWinAndLaterOnesFillGaps() {
        FakeSource crossref = new FakeSource("Crossref", Optional.of(crossrefSays()));
        FakeSource openAlex = new FakeSource("OpenAlex", Optional.of(openAlexSays()));

        PaperMetadata merged = new CompositeMetadataLookup(List.of(crossref, openAlex)).find(QUERY).orElseThrow();

        assertEquals(List.of("A One", "B Two"), merged.authors());
        assertEquals(Optional.of("Journal J"), merged.venue());
        assertEquals("gap filled by the second source", Optional.of(2025), merged.year());
        assertEquals("PDF location only the second source knew", List.of("https://oa.test/x.pdf"), merged.pdfUrls());
    }

    @Test
    public void aDoiFoundByTheFirstSourceIsUsedToQueryTheSecondExactly() {
        FakeSource crossref = new FakeSource("Crossref", Optional.of(crossrefSays()));
        FakeSource openAlex = new FakeSource("OpenAlex", Optional.of(openAlexSays()));

        new CompositeMetadataLookup(List.of(crossref, openAlex)).find(QUERY);

        assertEquals(Optional.empty(), crossref.queries.get(0).doi());
        assertEquals(Optional.of("10.9999/x"), openAlex.queries.get(0).doi());
    }

    @Test
    public void oneSourceOfflineTheOtherStillAnswers() {
        FakeSource crossref = FakeSource.offline("Crossref");
        FakeSource openAlex = new FakeSource("OpenAlex", Optional.of(openAlexSays()));

        PaperMetadata found = new CompositeMetadataLookup(List.of(crossref, openAlex)).find(QUERY).orElseThrow();
        assertEquals(List.of("https://oa.test/x.pdf"), found.pdfUrls());

        // ...and the other way round.
        FakeSource crossref2 = new FakeSource("Crossref", Optional.of(crossrefSays()));
        FakeSource openAlex2 = FakeSource.offline("OpenAlex");
        assertEquals(Optional.of("10.9999/x"),
                new CompositeMetadataLookup(List.of(crossref2, openAlex2)).find(QUERY).orElseThrow().doi());
    }

    @Test
    public void bothSourcesOfflineGivesNoResultInsteadOfAnException() {
        CompositeMetadataLookup lookup = new CompositeMetadataLookup(
                List.of(FakeSource.offline("Crossref"), FakeSource.offline("OpenAlex")));

        assertEquals(Optional.empty(), lookup.find(QUERY));
    }

    @Test
    public void aSourceThatKeepsFailingIsSkippedForTheRestOfTheRun() {
        FakeSource crossref = FakeSource.offline("Crossref");
        FakeSource openAlex = new FakeSource("OpenAlex", Optional.of(openAlexSays()));
        CompositeMetadataLookup lookup = new CompositeMetadataLookup(List.of(crossref, openAlex));

        for (int paper = 0; paper < 6; paper++) {
            lookup.find(QUERY);
        }

        assertEquals("stops being asked after MAX_CONSECUTIVE_FAILURES", CompositeMetadataLookup.MAX_CONSECUTIVE_FAILURES,
                crossref.queries.size());
        assertEquals("the healthy source is asked every time", 6, openAlex.queries.size());
    }

    @Test
    public void aSuccessResetsTheFailureCountSoOccasionalBlipsNeverDisableASource() {
        // fails, fails, works, fails, fails, works ... never 3 failures in a row
        FakeSource flaky = new FakeSource("Crossref", Optional.of(crossrefSays()), true, true, false, true, true, false);
        CompositeMetadataLookup lookup = new CompositeMetadataLookup(List.of(flaky));

        for (int paper = 0; paper < 6; paper++) {
            lookup.find(QUERY);
        }

        assertEquals(6, flaky.queries.size());
    }
}
