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

    /**
     * Input: the JSON file to keep this store in.
     * Output: a store for that file (nothing is read yet).
     * Used by: {@link #atDefaultLocation}; tests with a temp folder.
     * Example: {@code new ScoredPaperLog(tmp.resolve("scored_papers.json")).load()} -> empty
     */
    public ScoredPaperLog(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * Input: none.
     * Output: a store for {@code ~/.litroute/data/scored_papers.json}.
     * Used by: PipelineRunner.atDefaultLocations (M9).
     * Example: {@code atDefaultLocation()} -> store for /home/u/.litroute/data/scored_papers.json
     */
    public static ScoredPaperLog atDefaultLocation() {
        return new ScoredPaperLog(Path.of(System.getProperty("user.home"), ".litroute", "data", "scored_papers.json"));
    }

    /**
     * Input: none.
     * Output: a modifiable set of the paper keys already scored; empty if no file yet.
     * Used by: PipelineRunner (M9) to send only unscored papers to M6; {@link #addAll}.
     * Example: file {@code ["doi:10.1/x"]} -> {@code {"doi:10.1/x"}}
     */
    public Set<String> load() throws IOException {
        if (!Files.exists(storeFile)) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(JSON.readValue(Files.readAllBytes(storeFile), KEY_SET));
    }

    /**
     * Forgets every paper recorded, so the next run scores them all again.
     * Input: none.
     * Output: none; the file is deleted.
     * Used by: PipelineRunner.reset (M9, Clear and re-run).
     * Example: {@code clear(); load()} -> {@code {}}
     */
    public void clear() throws IOException {
        Files.deleteIfExists(storeFile);
    }

    /**
     * Records these paper keys as scored, keeping the ones already recorded.
     * Input: paper keys ({@link ScoredPaper#paperKey}).
     * Output: none; the file holds the union of old and new keys.
     * Used by: PipelineRunner (M9), only after the batch's matches are in M7e.
     * Example: file {@code ["doi:a"]}, {@code addAll(["doi:b"])} -> {@code ["doi:a","doi:b"]}
     */
    public void addAll(Collection<String> paperKeys) throws IOException {
        Set<String> keys = load();
        keys.addAll(paperKeys);
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(keys));
    }
}
