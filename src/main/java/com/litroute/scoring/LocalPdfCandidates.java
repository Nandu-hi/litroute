package com.litroute.scoring;

import com.litroute.model.PaperCandidate;
import com.litroute.profile.PdfTextExtractor;
import com.litroute.profile.UnreadablePdfException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Candidates for PDFs that did not arrive in an alert email - a paper dropped in by hand, to see which
 * students it would be routed to before it ever shows up in the pipeline.
 */
public final class LocalPdfCandidates {

    private static final PdfTextExtractor EXTRACTOR = new PdfTextExtractor();

    private LocalPdfCandidates() {
    }

    /**
     * Input: a PDF on disk.
     * Output: a candidate titled as the PDF reads (its file name if unreadable), with the PDF attached.
     * Used by: RelevanceScorerCli (score a dropped-in PDF), RelevanceScorerTest.
     * Example: ~/x/wave_loads.pdf (unreadable) -> PaperCandidate("wave loads", pdfPath ~/x/wave_loads.pdf)
     */
    public static PaperCandidate of(Path pdf) {
        String title;
        try {
            title = EXTRACTOR.extract(pdf).title();
        } catch (UnreadablePdfException e) {
            String name = pdf.getFileName().toString();
            title = name.replaceFirst("(?i)\\.pdf$", "").replace('_', ' ').strip();
        }
        return new PaperCandidate("<" + pdf.toAbsolutePath().toUri() + ">", title, List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(pdf));
    }
}
