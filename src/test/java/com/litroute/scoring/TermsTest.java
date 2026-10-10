package com.litroute.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/** The word matcher's terms, read from real paper titles: the client's sample alerts and a paper filed for RP7. */
public class TermsTest {

    /** Taylor & Francis alert (src/test/resources/samples/tandfonline_alert.html). */
    private static final String TANDF = "Readiness for sensing technologies and data analytics in multi-hazard disaster "
            + "risk reduction: a case study from Greater Bandung, Indonesia";
    /** SpringerOpen alert the professor marked irrelevant (springeropen_irrelevant.html). */
    private static final String SPRINGER = "Hygrothermal analysis of ceramic coating durability under thermal fluctuations "
            + "on building facades in Brasília";
    /** Betzler, Bredereck and Niedermeier, IPEC 2010, doi:10.1007/978-3-642-17493-3_5. */
    private static final String BETZLER = "Partial Kernelization for Rank Aggregation: Theory and Experiments";

    @Test
    public void functionWordsAndBoilerplateAreDropped() {
        Map<String, Integer> terms = Terms.counts(TANDF);

        for (String dropped : new String[] {"for", "and", "in", "a", "from", "data", "case", "study"}) {
            assertFalse(dropped, terms.containsKey(dropped));
        }
        assertTrue(terms.containsKey("disaster"));
        assertTrue(terms.containsKey("bandung"));
    }

    @Test
    public void hyphenatedWordsStayWholeAndAdjacentWordsPairUp() {
        Map<String, Integer> terms = Terms.counts(TANDF);

        assertTrue(terms.containsKey("multi-hazard"));
        assertTrue(terms.containsKey("multi-hazard" + Terms.PAIR + "disaster"));
        assertTrue(terms.containsKey("disaster" + Terms.PAIR + "risk"));
        assertTrue(terms.containsKey("risk" + Terms.PAIR + "reduction"));
    }

    @Test
    public void aDroppedWordSeparatesItsNeighbours() {
        Map<String, Integer> terms = Terms.counts(SPRINGER);

        assertTrue(terms.containsKey("thermal" + Terms.PAIR + "fluctuation"));
        // "durability under thermal": "under" is dropped, so no pair spans it
        assertFalse(terms.containsKey("durability" + Terms.PAIR + "thermal"));
    }

    @Test
    public void pluralsAndAccentsFoldTogether() {
        Map<String, Integer> terms = Terms.counts(SPRINGER);

        assertTrue(terms.containsKey("facade"));
        assertTrue(terms.containsKey("fluctuation"));
        assertTrue(terms.containsKey("brasilia"));
        assertEquals(Terms.stem("hazard"), Terms.stem("hazards"));
        assertEquals(Terms.stem("process"), Terms.stem("processes"));
        assertEquals("analysis", Terms.stem("analysis"));
    }

    @Test
    public void rankingAndRankShareAStemAndPairsKeepTheirWrittenForm() {
        Map<String, Integer> terms = Terms.counts(BETZLER);
        Map<String, String> written = Terms.surfaceForms(BETZLER);

        assertTrue(terms.containsKey("rank" + Terms.PAIR + "aggregation"));
        assertEquals("rank aggregation", written.get("rank" + Terms.PAIR + "aggregation"));
        assertEquals(Terms.stem("rank"), Terms.stem("ranking"));
        assertEquals("kernelization", written.get("kernelization"));
    }
}
