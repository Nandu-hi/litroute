package com.litroute.ingestion.enrich;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PdfDownloaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final FakeHttp http = new FakeHttp();

    private PdfDownloader downloader() {
        return new PdfDownloader(http, tmp.getRoot().toPath().resolve("pdfs"));
    }

    @Test
    public void savesARealPdfUnderTheNameOfItsDoi() throws IOException {
        http.on("oa.test/x.pdf", FakeHttp.pdf());

        Path saved = downloader().download(List.of("https://oa.test/x.pdf"), "10.9999_x.1").orElseThrow();

        assertEquals("10.9999_x.1.pdf", saved.getFileName().toString());
        assertArrayEquals(FakeHttp.PDF_BYTES, Files.readAllBytes(saved));
    }

    @Test
    public void aPaywallPageThatAnswers200WithHtmlIsRejectedAndTheNextUrlIsTried() throws IOException {
        http.on("pub.test/paywalled", FakeHttp.html("<html>Please log in to read this article</html>"));
        http.on("oa.test/x.pdf", FakeHttp.pdf());

        Path saved = downloader().download(List.of("https://pub.test/paywalled", "https://oa.test/x.pdf"), "x").orElseThrow();

        assertArrayEquals(FakeHttp.PDF_BYTES, Files.readAllBytes(saved));
        assertEquals(List.of("https://pub.test/paywalled", "https://oa.test/x.pdf"), http.requests);
    }

    @Test
    public void whenNoUrlServesAPdfNothingIsWritten() throws IOException {
        http.on("a.test", FakeHttp.status(403));
        http.on("b.test", FakeHttp.html("<html>login</html>"));
        http.on("c.test", FakeHttp.offline());
        http.on("d.test", FakeHttp.tooLarge());

        Optional<Path> saved = downloader().download(
                List.of("https://a.test/1", "https://b.test/2", "https://c.test/3", "https://d.test/4", "ftp://e.test/5"), "x");

        assertEquals(Optional.empty(), saved);
        Path directory = tmp.getRoot().toPath().resolve("pdfs");
        if (Files.exists(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals("no partial or fake file left behind", 0, files.count());
            }
        }
    }

    @Test
    public void aPdfAlreadyOnDiskIsReusedWithoutDownloadingAgain() throws IOException {
        http.on("oa.test/x.pdf", FakeHttp.pdf());
        PdfDownloader downloader = downloader();
        Path first = downloader.download(List.of("https://oa.test/x.pdf"), "10.9999_x.1").orElseThrow();
        http.requests.clear();

        Path second = downloader.download(List.of("https://oa.test/x.pdf"), "10.9999_x.1").orElseThrow();

        assertEquals(first, second);
        assertEquals(List.of(), http.requests);
    }

    @Test
    public void fileStemComesFromTheDoiOtherwiseAStableHashOfTheTitle() {
        assertEquals("10.1186_s43065-024-00115-x", PdfDownloader.fileStem(Optional.of("10.1186/s43065-024-00115-x"), "t"));
        assertEquals("doi with hostile characters can't escape the folder", "10.9999_.._.._etc_passwd",
                PdfDownloader.fileStem(Optional.of("10.9999/../../etc/passwd"), "t"));
        assertFalse(PdfDownloader.fileStem(Optional.of("10.9999/x"), "t").contains("/"));

        String stem = PdfDownloader.fileStem(Optional.empty(), "Roadway Traffic Assessment of the 2023 Maui Wildfires");
        assertTrue(stem.startsWith("title-"));
        assertEquals("punctuation/case differences in the same title don't change the name", stem,
                PdfDownloader.fileStem(Optional.empty(), "Roadway traffic assessment of the 2023 Maui wildfires."));
        assertNotEquals(stem, PdfDownloader.fileStem(Optional.empty(), "A different paper altogether"));
    }
}
