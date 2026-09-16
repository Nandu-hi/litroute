package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M7a: the M1-to-M2 boundary. Tracks which Gmail Message-IDs have already
 * been pulled and processed, so re-running the pipeline doesn't re-fetch and
 * re-parse the same alert emails.
 *
 * M9 is responsible for calling save() only after a run completes
 * successfully, and for updating this store last among the M7 checkpoints
 * (per the design doc's crash-consistency plan) so a mid-run crash results
 * in a batch being reprocessed rather than silently skipped.
 */
public final class EmailCacheStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Set<String>> MESSAGE_ID_SET_TYPE = new TypeReference<>() {
    };

    private final Path storeFile;

    public EmailCacheStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static EmailCacheStore atDefaultLocation() {
        return new EmailCacheStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "email_cache.json"));
    }

    /** Returns an empty set if no cache file has been written yet. */
    public Set<String> load() throws IOException {
        if (!Files.exists(storeFile)) {
            return new LinkedHashSet<>();
        }
        Set<String> messageIds = JSON.readValue(Files.readAllBytes(storeFile), MESSAGE_ID_SET_TYPE);
        return new LinkedHashSet<>(messageIds);
    }

    public void save(Set<String> processedMessageIds) throws IOException {
        byte[] content = JSON.writeValueAsBytes(Collections.unmodifiableSet(processedMessageIds));
        AtomicFiles.writeAtomically(storeFile, content);
    }
}
