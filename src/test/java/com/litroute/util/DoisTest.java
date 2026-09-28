package com.litroute.util;

import static org.junit.Assert.assertEquals;

import java.util.Optional;
import org.junit.Test;

public class DoisTest {

    @Test
    public void findsTheDoiInCommonPublisherUrlShapesStrippingQueriesAndPdfSuffixes() {
        assertEquals(Optional.of("10.1186/s43065-024-00115-x"),
                Dois.find("https://jipr.springeropen.com/articles/10.1186/s43065-024-00115-x?utm_source=alert&utm_medium=email"));
        assertEquals(Optional.of("10.1186/s43065-024-00115-x"),
                Dois.find("https://jipr.springeropen.com/counter/pdf/10.1186/s43065-024-00115-x.pdf"));
        assertEquals(Optional.of("10.1080/23789689.2026.2723803"), Dois.find("https://www.tandfonline.com/doi/full/10.1080/23789689.2026.2723803"));
        assertEquals(Optional.of("10.1002/eqe.70123"), Dois.find("https://onlinelibrary.wiley.com/doi/pdf/10.1002/eqe.70123"));
        assertEquals(Optional.of("10.1061/nhrefo.nheng-2502"), Dois.find("https://ascelibrary.org/doi/abs/10.1061/nhrefo.nheng-2502#abstract"));
    }

    @Test
    public void findsADoiInPlainTextAndDropsTrailingPunctuation() {
        assertEquals(Optional.of("10.1016/j.oceaneng.2024.118952"), Dois.find("DOI: 10.1016/j.oceaneng.2024.118952."));
        assertEquals(Optional.of("10.1016/j.oceaneng.2024.118952"), Dois.find("see (doi:10.1016/j.oceaneng.2024.118952), page 4"));
        assertEquals(Optional.of("10.1016/j.x"), Dois.find("https://doi.org/10.1016/j.x"));
    }

    @Test
    public void understandsAPercentEncodedDoi() {
        assertEquals(Optional.of("10.1016/j.x.2024.1"), Dois.find("https://host.test/lookup?doi=10.1016%2Fj.x.2024.1"));
    }

    @Test
    public void ignoresThingsThatMerelyLookNumeric() {
        assertEquals(Optional.empty(), Dois.find("https://www.sciencedirect.com/science/article/pii/S0000000000000001"));
        assertEquals(Optional.empty(), Dois.find("Reston, VA 20191-4400 703-295-6300 | 800-548-2723"));
        assertEquals(Optional.empty(), Dois.find("Subscription ID: a98cd847-4fe8-4479-b551-c4495182f8b4 Webuser ID: 25820359"));
        assertEquals(Optional.empty(), Dois.find("10.1186/"));
        assertEquals(Optional.empty(), Dois.find(null));
        assertEquals(Optional.empty(), Dois.find("  "));
    }
}
