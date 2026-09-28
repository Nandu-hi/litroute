package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * Reading a publisher's article page, against the pages the publishers really served for the papers
 * in the sample mails (see src/test/resources/http/README.md).
 */
public class ArticlePageReaderTest {

    private static final String SSI_PAGE =
            "https://jipr.springeropen.com/articles/10.1186/s43065-025-00118-2?utm_source=alert&utm_medium=email";

    private final RecordedHttp http = new RecordedHttp();
    private final ArticlePageReader reader = new ArticlePageReader(http);

    @Test
    public void readsTheCitationMetadataSpringerOpenPublishes() {
        PaperMetadata page = reader.read(SSI_PAGE).orElseThrow();

        assertEquals(Optional.of("10.1186/s43065-025-00118-2"), page.doi());
        assertEquals(Optional.of("Advancing soil-structure interaction (SSI): a comprehensive review of "
                + "current practices, challenges, and future directions"), page.title());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), page.venue());
        assertEquals("citation_publication_date is \"2025/12\"; only the year is kept",
                Optional.of(2025), page.year());
        assertEquals(List.of("https://link.springer.com/content/pdf/10.1186/s43065-025-00118-2.pdf"), page.pdfUrls());
        assertEquals(Optional.of(SSI_PAGE), page.landingUrl());
    }

    /**
     * Publishers write citation_author family-name-first. The reader keeps the page's own spelling
     * rather than guessing where to split a name, so these come back as the page states them.
     */
    @Test
    public void authorsComeBackExactlyAsThePageStatesThem() {
        PaperMetadata page = reader.read(SSI_PAGE).orElseThrow();

        assertEquals("Najar, Imtiyaz Akbar", page.authors().get(0));
        assertTrue("every author of a nine-author review is kept", page.authors().size() >= 8);
    }

    /**
     * ASCE serves a 403 to anything that isn't a browser. That is the normal case for a subscription
     * publisher, so it has to read as "nothing learned here", not as an error that stops the paper.
     */
    @Test
    public void aPublisherThatBlocksRobotsSimplyYieldsNothing() {
        assertEquals(Optional.empty(), reader.read("https://ascelibrary.org/doi/10.1061/nhrefo.nheng-2711?af=R"));
    }

    @Test
    public void aPageThatCannotBeReachedYieldsNothingRatherThanFailing() {
        ArticlePageReader offline = new ArticlePageReader(new RecordedHttp().offlineFor("springeropen.com"));

        assertEquals(Optional.empty(), offline.read(SSI_PAGE));
    }

    /** A malformed URL must not escape as an exception from what is a best-effort lookup. */
    @Test
    public void aMalformedUrlYieldsNothingRatherThanFailing() {
        assertEquals(Optional.empty(), reader.read("not a url at all"));
    }
}
