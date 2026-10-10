package com.litroute.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.litroute.util.Vectors;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * SPECTER2 (allenai/specter2_base with the "proximity" adapter, the paper-to-paper similarity mode)
 * running locally: HuggingFace tokenizer through DJL, the model through ONNX Runtime, no Python.
 *
 * The model files are produced once per machine by {@code scripts/model-export/setup_model.sh}
 * into {@code ~/.litroute/models}. Input follows SPECTER2's convention, {@code title[SEP]text},
 * truncated to the model's 512-token limit; the embedding is the [CLS] vector the export graph
 * returns, L2-normalized here.
 *
 * Thread-safe: ONNX Runtime sessions and DJL tokenizers may be shared between threads.
 */
public final class Specter2EmbeddingProvider implements EmbeddingProvider {

    public static final String MODEL_ID = "specter2-proximity-onnx/cls/512";

    static final int MAX_TOKENS = 512;
    private static final String SEPARATOR = "[SEP]";
    private static final String MODEL_FILE = "specter2.onnx";
    private static final String TOKENIZER_FILE = "tokenizer.json";

    private final HuggingFaceTokenizer tokenizer;
    private final OrtEnvironment environment;
    private final OrtSession session;

    private Specter2EmbeddingProvider(HuggingFaceTokenizer tokenizer, OrtEnvironment environment, OrtSession session) {
        this.tokenizer = tokenizer;
        this.environment = environment;
        this.session = session;
    }

    public static Path defaultModelDirectory() {
        return Path.of(System.getProperty("user.home"), ".litroute", "models");
    }

    public static Specter2EmbeddingProvider atDefaultLocation() throws IOException {
        return open(defaultModelDirectory());
    }

    /** @throws IOException if the exported model or tokenizer is missing or cannot be loaded */
    public static Specter2EmbeddingProvider open(Path modelDirectory) throws IOException {
        Path model = modelDirectory.resolve(MODEL_FILE);
        Path tokenizerFile = modelDirectory.resolve(TOKENIZER_FILE);
        if (!Files.isRegularFile(model) || !Files.isRegularFile(tokenizerFile)) {
            throw new IOException("SPECTER2 model not found in " + modelDirectory
                    + "; run scripts/model-export/setup_model.sh once to export it");
        }
        // tokenizer_config.json declares no usable max length, so truncation must be asked for here.
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile, Map.of(
                "maxLength", String.valueOf(MAX_TOKENS),
                "truncation", "true",
                "padding", "false"));
        try {
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            // Loaded by path, not bytes, so ONNX Runtime finds the weights in specter2.onnx.data.
            OrtSession session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
            return new Specter2EmbeddingProvider(tokenizer, environment, session);
        } catch (OrtException e) {
            tokenizer.close();
            throw new IOException("Could not load " + model + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public float[] embed(String title, String text) {
        Encoding encoding = tokenizer.encode(title + SEPARATOR + text);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        long[] shape = {1, ids.length};
        try (OnnxTensor idTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
                OnnxTensor maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape);
                OrtSession.Result result = session.run(Map.of("input_ids", idTensor, "attention_mask", maskTensor))) {
            float[][] output = (float[][]) result.get(0).getValue();
            return Vectors.normalize(output[0]);
        } catch (OrtException e) {
            throw new IllegalStateException("SPECTER2 inference failed", e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException("Could not close the SPECTER2 session", e);
        } finally {
            tokenizer.close();
        }
    }
}
