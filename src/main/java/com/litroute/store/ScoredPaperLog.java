package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.ScoredPaper;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which deduplicated papers M6 has already matched, by {@link ScoredPaper#paperKey}, so M9 offers each
 * paper to the matcher once. The review queue (M7e) cannot tell this alone: the AI matcher may give a
 * paper to no student, and such a paper is never queued, yet it was scored and must not be sent to the
 * model again on every run.
 *
 * M9 adds a batch here only after its matches are in the review queue, so a crash in between scores the
 * batch again on the next run rather than losing it; the queue keeps the professor's decisions when a
 * paper is re-scored.
 */
public final class ScoredPaperLog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Set<String>> KEY_SET = new TypeReference<>() {
    };

    private final Path storeFile;

    public ScoredPaperLog(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static ScoredPaperLog atDefaultLocation() {
        return new ScoredPaperLog(Path.of(System.getProperty("user.home"), ".litroute", "data", "scored_papers.json"));
    }

    /** Returns an empty set if nothing has been scored yet. */
    public Set<String> load() throws IOException {
        if (!Files.exists(storeFile)) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(JSON.readValue(Files.readAllBytes(storeFile), KEY_SET));
    }

    /** Records these paper keys as scored, keeping the ones already recorded. */
    public void addAll(Collection<String> paperKeys) throws IOException {
        Set<String> keys = load();
        keys.addAll(paperKeys);
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(keys));
    }
}
