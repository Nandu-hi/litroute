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

    /**
     * Input: the JSON file to keep this store in.
     * Output: a store for that file (nothing is read yet).
     * Used by: {@link #atDefaultLocation}; tests with a temp folder.
     * Example: {@code new EmailCacheStore(tmp.resolve("email_cache.json")).load()} -> empty
     */
    public EmailCacheStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * Input: none.
     * Output: a store for {@code ~/.litroute/data/email_cache.json}.
     * Used by: PipelineRunner.atDefaultLocations (M9), PipelinePane (M8) to count processed alerts.
     * Example: {@code atDefaultLocation()} -> store for /home/u/.litroute/data/email_cache.json
     */
    public static EmailCacheStore atDefaultLocation() {
        return new EmailCacheStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "email_cache.json"));
    }

    /**
     * Input: none.
     * Output: a modifiable set of the Message-IDs already processed; empty if no file yet.
     * Used by: PipelineRunner (M9) before fetching (M1 skips these) and on reset; AlertBatchProcessor (M2).
     * Example: file {@code ["<a@x>","<b@x>"]} -> {@code {"<a@x>","<b@x>"}}
     */
    public Set<String> load() throws IOException {
        if (!Files.exists(storeFile)) {
            return new LinkedHashSet<>();
        }
        Set<String> messageIds = JSON.readValue(Files.readAllBytes(storeFile), MESSAGE_ID_SET_TYPE);
        return new LinkedHashSet<>(messageIds);
    }

    /**
     * Input: every Message-ID processed so far.
     * Output: none; the file is atomically replaced with them as a JSON array.
     * Used by: AlertBatchProcessor (M2) LAST, after M7c is saved; and with an empty set on reset.
     * Example: {@code save({"<a@x>"})} -> file {@code ["<a@x>"]}
     */
    public void save(Set<String> processedMessageIds) throws IOException {
        byte[] content = JSON.writeValueAsBytes(Collections.unmodifiableSet(processedMessageIds));
        AtomicFiles.writeAtomically(storeFile, content);
    }
}
