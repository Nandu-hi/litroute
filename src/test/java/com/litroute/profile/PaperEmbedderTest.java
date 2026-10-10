package com.litroute.profile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.litroute.model.Profile.ProfilePaper;
import com.litroute.testing.TestModel;
import com.litroute.testing.TestProfiles;
import com.litroute.util.Vectors;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Test;

/** Embeds a real test-profile paper with the real model. */
public class PaperEmbedderTest {

    @Test
    public void paperGetsUnitLengthViewsForWhatItsTextHas() throws UnreadablePdfException {
        PaperText text = firstReadablePaper();

        ProfilePaper paper = new PaperEmbedder(TestModel.provider()).embed(text, "paper.pdf", "abc123");

        assertEquals("paper.pdf", paper.relativePath());
        assertEquals("abc123", paper.sha256());
        assertEquals(text.title(), paper.title());
        assertEquals(text.headings().isEmpty(), paper.structureView().isEmpty());
        assertEquals(text.bodyChunks().isEmpty(), paper.bodyView().isEmpty());
        for (float[] view : new float[][] {paper.embedding(), paper.abstractView(),
                paper.structureView().orElse(paper.abstractView()), paper.bodyView().orElse(paper.abstractView())}) {
            assertEquals(768, view.length);
            assertEquals(1.0, Vectors.norm(view), 1e-5);
        }
    }

    @Test
    public void missingViewsLeaveTheAbstractAlone() {
        float[] abstractView = TestModel.provider().embed("Attention Is All You Need",
                "We propose a new network architecture, the Transformer, based solely on attention mechanisms.");

        float[] combined = PaperEmbedder.combine(abstractView, Optional.empty(), Optional.empty());

        assertArrayEquals(abstractView, combined, 1e-6f);
    }

    private static PaperText firstReadablePaper() {
        PdfTextExtractor extractor = new PdfTextExtractor();
        for (Path pdf : TestProfiles.allPdfs()) {
            try {
                return extractor.extract(pdf);
            } catch (UnreadablePdfException e) {
                // try the next one
            }
        }
        throw new AssertionError("no test PDF was readable");
    }
}
