package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Saving a paper's PDF, against the replies the publishers really gave. The two open-access PDFs the
 * sample mails lead to are SpringerOpen's, served from link.springer.com; ASCE and ScienceDirect
 * answer a robot with 403 or with an HTML page, which is what the rejection paths here exercise.
 */
public class PdfDownloaderTest {

    private static final String SSI_PDF = "https://link.springer.com/content/pdf/10.1186/s43065-025-00118-2.pdf";
    private static final String HYGRO_PDF = "https://link.springer.com/content/pdf/10.1186/s43065-024-00115-x.pdf";
    private static final String AN_ARTICLE_PAGE =
            "https://jipr.springeropen.com/articles/10.1186/s43065-025-00118-2?utm_source=alert&utm_medium=email";
    private static final String A_BLOCKED_PAGE = "https://ascelibrary.org/doi/10.1061/nhrefo.nheng-2711?af=R";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final RecordedHttp http = new RecordedHttp();

    private PdfDownloader downloader() {
        return new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs"));
    }

    private Path directory() {
        return tmp.getRoot().toPath().resolve("pdfs");
    }

    @Test
    public void savesARealPdfUnderTheNameOfItsDoi() throws IOException {
        Path saved = downloader().download(List.of(SSI_PDF), "10.1186_s43065-025-00118-2").orElseThrow();

        assertEquals("10.1186_s43065-025-00118-2.pdf", saved.getFileName().toString());
        assertTrue("what was saved really is a PDF",
                new String(Files.readAllBytes(saved), StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
    }

    /**
     * An article page answers 200 with HTML, not a PDF. Accepting it would put a login or abstract
     * page on disk under a .pdf name, so it must be rejected and the next URL tried.
     */
    @Test
    public void anHtmlPageThatAnswers200IsRejectedAndTheNextUrlIsTried() throws IOException {
        Path saved = downloader().download(List.of(AN_ARTICLE_PAGE, SSI_PDF), "ssi").orElseThrow();

        assertTrue(new String(Files.readAllBytes(saved), StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
        assertEquals("both URLs were tried, in order", List.of(AN_ARTICLE_PAGE, SSI_PDF), http.requests);
    }

    /** Nothing serves a PDF: no file may be left behind, not even an empty or partial one. */
    @Test
    public void whenNoUrlServesAPdfNothingIsWritten() throws IOException {
        Optional<Path> saved = downloader().download(
                List.of(A_BLOCKED_PAGE, AN_ARTICLE_PAGE, "ftp://example.test/paper.pdf"), "nothing");

        assertEquals(Optional.empty(), saved);
        if (Files.exists(directory())) {
            try (Stream<Path> files = Files.list(directory())) {
                assertEquals("no partial or fake file left behind", 0, files.count());
            }
        }
    }

    @Test
    public void aPublisherThatCannotBeReachedIsJustAnotherUrlThatDidNotWork() {
        PdfDownloader offline = new PdfDownloader(
                new RecordedHttp().offlineFor("link.springer.com"), directory());

        assertEquals(Optional.empty(), offline.download(List.of(SSI_PDF), "ssi"));
    }

    /** Re-running the pipeline must reuse the file already on disk rather than fetch it again. */
    @Test
    public void aPdfAlreadyOnDiskIsReusedWithoutDownloadingAgain() {
        PdfDownloader downloader = downloader();
        Path first = downloader.download(List.of(HYGRO_PDF), "10.1186_s43065-024-00115-x").orElseThrow();
        http.requests.clear();

        Path second = downloader.download(List.of(HYGRO_PDF), "10.1186_s43065-024-00115-x").orElseThrow();

        assertEquals(first, second);
        assertEquals(List.of(), http.requests);
    }

    /**
     * The file name is derived from the DOI, which is attacker-influenced data in the sense that it
     * comes out of an email: it must not be able to steer the write out of the PDF folder.
     */
    @Test
    public void fileStemComesFromTheDoiOtherwiseAStableHashOfTheTitle() {
        assertEquals("10.1186_s43065-025-00118-2",
                PdfDownloader.fileStem(Optional.of("10.1186/s43065-025-00118-2"), "t"));
        assertEquals("a DOI cannot walk out of the folder", "10.1061_.._.._etc_passwd",
                PdfDownloader.fileStem(Optional.of("10.1061/../../etc/passwd"), "t"));
        assertFalse(PdfDownloader.fileStem(Optional.of("10.1061/nhrefo.nheng-2502"), "t").contains("/"));

        String stem = PdfDownloader.fileStem(Optional.empty(), "Roadway Traffic Assessment of the 2023 Maui Wildfires");
        assertTrue(stem.startsWith("title-"));
        assertEquals("punctuation and case differences in one title must not change the name", stem,
                PdfDownloader.fileStem(Optional.empty(), "Roadway traffic assessment of the 2023 Maui wildfires."));
        assertNotEquals(stem, PdfDownloader.fileStem(Optional.empty(),
                "Multidimensional Fragility Analysis of Earth Slopes under Pulse-Like Ground Motions"));
    }
}
