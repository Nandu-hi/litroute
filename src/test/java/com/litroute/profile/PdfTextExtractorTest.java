package com.litroute.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.litroute.testing.TestProfiles;
import com.litroute.util.Titles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.Test;

public class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    /** Real PDF downloads recorded by the M3 tests, cut off after their first 8 KB. */
    @Test
    public void truncatedDownloadsAreUnreadable() throws IOException {
        for (String file : List.of("8d0d08f9b152568722f5.pdf", "e502fdcc276fe9dbf272.pdf")) {
            Path pdf = Path.of("src/test/resources/http", file);
            assertTrue(Files.readString(pdf, StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
            assertUnreadable(pdf, "not a readable PDF");
        }
    }

    @Test
    public void passwordProtectedPdfIsUnreadable() throws IOException {
        Path original = TestProfiles.allPdfs().get(0);
        Path locked = Files.createTempDirectory("litroute-locked").resolve("locked.pdf");
        try (PDDocument document = Loader.loadPDF(original.toFile())) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            document.protect(policy);
            document.save(locked.toFile());
        }

        assertUnreadable(locked, "password-protected");
    }

    /** What a scan without OCR looks like to a text extractor: pages, but no text on them. */
    @Test
    public void pdfWithoutTextIsUnreadable() throws IOException {
        Path blank = Files.createTempDirectory("litroute-blank").resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(blank.toFile());
        }

        assertUnreadable(blank, "no extractable text");
    }

    @Test
    public void everyReadableTestPdfYieldsTitleAbstractAndBody() {
        List<Path> pdfs = TestProfiles.allPdfs();
        // In parallel: the test folders can hold hundreds of PDFs at about a second each.
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger read = new AtomicInteger();
        AtomicInteger withKeywords = new AtomicInteger();
        pdfs.parallelStream().forEach(pdf -> {
            PaperText text;
            try {
                text = extractor.extract(pdf);
            } catch (UnreadablePdfException e) {
                return; // the builder tests check unreadable files are reported, not dropped silently
            }
            read.incrementAndGet();
            if (text.title().length() > 300) {
                failures.add(pdf.getFileName() + ": title runs to " + text.title().length() + " chars");
            }
            if (text.abstractText().length() < 100) {
                failures.add(pdf.getFileName() + ": abstract only \"" + text.abstractText() + "\"");
            }
            if (text.bodyChunks().size() > PdfTextExtractor.MAX_BODY_CHUNKS) {
                failures.add(pdf.getFileName() + ": " + text.bodyChunks().size() + " body chunks");
            }
            for (String chunk : text.bodyChunks()) {
                if (chunk.length() > PdfTextExtractor.CHUNK_CHARS) {
                    failures.add(pdf.getFileName() + ": body chunk of " + chunk.length() + " chars");
                }
            }
            for (String heading : text.headings()) {
                if (heading.matches("(?i)(?:\\d+\\.?\\s+)?references")) {
                    failures.add(pdf.getFileName() + ": references section not cut off");
                }
            }
            if (!text.keywords().isEmpty()) {
                withKeywords.incrementAndGet();
            }
            if (text.keywords().size() > PdfTextExtractor.MAX_KEYWORDS) {
                failures.add(pdf.getFileName() + ": " + text.keywords().size() + " keywords");
            }
            for (String keyword : text.keywords()) {
                if (keyword.split("\\s+").length > PdfTextExtractor.MAX_KEYWORD_WORDS || keyword.matches(".*[;,·•|:].*")
                        || !keyword.equals(keyword.strip())) {
                    failures.add(pdf.getFileName() + ": keyword \"" + keyword + "\" is not one keyword");
                }
            }
        });
        assertTrue("no test PDF was readable", read.get() > 0);
        assertTrue(String.join("\n", failures), failures.isEmpty());
        // About half of the students' papers list keywords; reports, theses and books don't.
        assertTrue(withKeywords + " of " + read + " PDFs gave keywords", withKeywords.get() >= read.get() / 3);
    }

    /**
     * The metadata title is trusted when it appears on the first pages, allowing for what text
     * extraction loses - here the first letter of each line, as some PDF producers draw it apart
     * (Engineering Structures 343 (2025) 121106, as PDFBox reads its first page).
     */
    @Test
    public void metadataTitleMatchesPageTextDespiteSmallDifferences() {
        String metadataTitle = Titles.compact(
                "Beyond the sum: Multi-source uncertainty and its impact on structural fragility and risk");
        String pageText = Titles.compact("Engineering Structures 343 (2025) 121106 "
                + "eyond the sum: Multi-source uncertainty and its impact on structural ragility and risk");

        assertTrue(PdfTextExtractor.appearsIn(metadataTitle, pageText));
        assertFalse(PdfTextExtractor.appearsIn(Titles.compact("Multiple stripe analysis for rapid failure "
                + "probability analysis in support of performance-based wind engineering"), pageText));
        assertFalse(PdfTextExtractor.appearsIn(Titles.compact("Seismic Meta-Fragility Functions"), pageText));
    }

    /** The keywords exactly as the authors listed them, in three layouts journals use. */
    @Test
    public void authorKeywordsAreReadAsListed() throws UnreadablePdfException {
        // ASCE: run in after the abstract, separated by semicolons, and wrapped inside the last keyword.
        assertKeywords("Yu et al (2021) Probabilistic Calibration of Stress-Strain Models for Confined Normal-Strength "
                + "Concrete.pdf", List.of("Confined concrete", "Probabilistic calibration", "Peak stress", "Peak strain",
                        "Stress-strain curve", "Bayesian theory", "Markov chain Monte Carlo"));
        // Elsevier: "Keywords:" on a line of its own, then one keyword per line.
        assertKeywords("Kwag et al. - 2021 - Significance of multi-hazard risk in design of buildings under earthquake "
                + "and wind loads.pdf", List.of("Earthquake and wind hazards", "Performance-based design",
                        "Risk-based multi-hazard approach", "Multi-hazard risk map", "Multi-hazard scenario",
                        "Magneto-rheological damper", "Adjacent buildings"));
        // "Key words and phrases." then commas, ending in a full stop.
        assertKeywords("_Fernandez-Godino (2020) review of mf models.pdf", List.of("Multi-fidelity", "variable-complexity",
                "variable-fidelity", "surrogate models", "optimization", "uncertainty quantification", "review", "survey"));
    }

    private void assertKeywords(String fileName, List<String> expected) throws UnreadablePdfException {
        Path pdf = TestProfiles.allPdfs().stream().filter(p -> p.getFileName().toString().equals(fileName)).findFirst()
                .orElse(null);
        assumeTrue(fileName + " is not among the test PDFs", pdf != null);
        assertEquals(expected, extractor.extract(pdf).keywords());
    }

    @Test
    public void extractionIsDeterministic() throws UnreadablePdfException {
        for (Path pdf : TestProfiles.allPdfs()) {
            try {
                assertEquals(extractor.extract(pdf), extractor.extract(pdf));
                return;
            } catch (UnreadablePdfException e) {
                // try the next one
            }
        }
        fail("no test PDF was readable");
    }

    @Test
    public void bodySamplingKeepsChunksSpreadAcrossTheText() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            body.append("word").append(i).append(' ');
        }
        List<String> chunks = PdfTextExtractor.sampleChunks(body.toString());

        assertEquals(PdfTextExtractor.MAX_BODY_CHUNKS, chunks.size());
        assertTrue(chunks.get(0).startsWith("word0 "));
        assertTrue(chunks.get(chunks.size() - 1).contains("word1999"));
        assertFalse(chunks.stream().anyMatch(c -> c.length() > PdfTextExtractor.CHUNK_CHARS));
    }

    private void assertUnreadable(Path pdf, String reason) {
        try {
            extractor.extract(pdf);
            fail("expected UnreadablePdfException for " + pdf);
        } catch (UnreadablePdfException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(reason));
        }
    }
}
