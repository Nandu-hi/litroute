package com.litroute.embedding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.litroute.testing.TestModel;
import com.litroute.util.Vectors;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;

/** Runs the real exported SPECTER2 model, as scripts/model-export/verify_onnx.py does in Python. */
public class Specter2EmbeddingProviderTest {

    private static final String TRANSFORMER_TITLE = "Attention Is All You Need";
    private static final String TRANSFORMER_ABSTRACT = "We propose a new network architecture, the Transformer, "
            + "based solely on attention mechanisms, dispensing with recurrence and convolutions entirely.";
    private static final String BERT_TITLE = "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding";
    private static final String BERT_ABSTRACT = "We introduce a new language representation model called BERT, "
            + "using a masked language model pre-training objective.";
    private static final String CRISPR_TITLE = "CRISPR-Cas9 gene editing in mammalian cells";
    private static final String CRISPR_ABSTRACT = "We demonstrate targeted genome editing in mammalian cells using the "
            + "CRISPR-Cas9 system, achieving efficient double-strand breaks at specified loci.";

    private final EmbeddingProvider model = TestModel.provider();

    @Test
    public void embeddingIsA768DimensionalUnitVector() {
        float[] embedding = model.embed(TRANSFORMER_TITLE, TRANSFORMER_ABSTRACT);

        assertEquals(768, embedding.length);
        assertEquals(1.0, Vectors.norm(embedding), 1e-5);
        assertEquals(Specter2EmbeddingProvider.MODEL_ID, model.modelId());
    }

    @Test
    public void sameTextGivesTheSameVector() {
        assertArrayEquals(model.embed(BERT_TITLE, BERT_ABSTRACT), model.embed(BERT_TITLE, BERT_ABSTRACT), 0f);
    }

    @Test
    public void relatedPapersAreCloserThanUnrelatedOnes() {
        float[] transformer = model.embed(TRANSFORMER_TITLE, TRANSFORMER_ABSTRACT);
        float[] bert = model.embed(BERT_TITLE, BERT_ABSTRACT);
        float[] crispr = model.embed(CRISPR_TITLE, CRISPR_ABSTRACT);

        double related = Vectors.cosine(transformer, bert);
        assertTrue(related > Vectors.cosine(transformer, crispr));
        assertTrue(related > Vectors.cosine(bert, crispr));
    }

    @Test
    public void textLongerThanTheModelLimitIsTruncatedNotRejected() {
        float[] embedding = model.embed(TRANSFORMER_TITLE, TRANSFORMER_ABSTRACT.repeat(200));

        assertEquals(768, embedding.length);
        assertEquals(1.0, Vectors.norm(embedding), 1e-5);
    }

    @Test
    public void emptyTextStillEmbedsTheTitle() {
        assertEquals(768, model.embed(TRANSFORMER_TITLE, "").length);
    }

    @Test
    public void missingModelFilesNameTheSetupScript() throws IOException {
        try {
            Specter2EmbeddingProvider.open(Files.createTempDirectory("litroute-no-model"));
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("setup_model.sh"));
        }
    }
}
