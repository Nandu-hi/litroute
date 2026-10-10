package com.litroute.testing;

import com.litroute.embedding.Specter2EmbeddingProvider;
import java.io.IOException;

/**
 * The real SPECTER2 model, loaded once per test JVM and shared by every test class (loading takes
 * several seconds). There is deliberately no stand-in: if the model has not been exported on this
 * machine, the tests that need it fail with the instruction to run setup_model.sh.
 */
public final class TestModel {

    private static Specter2EmbeddingProvider provider;

    private TestModel() {
    }

    public static synchronized Specter2EmbeddingProvider provider() {
        if (provider == null) {
            try {
                provider = Specter2EmbeddingProvider.atDefaultLocation();
            } catch (IOException e) {
                throw new AssertionError(e.getMessage(), e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(provider::close));
        }
        return provider;
    }
}
