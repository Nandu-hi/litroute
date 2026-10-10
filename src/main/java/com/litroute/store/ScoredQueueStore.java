package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M7e: the M6-to-M8/M9 boundary - the professor's review queue, kept across restarts. Holds every
 * scored (paper, student) pair with its review status, in the order they were first queued.
 *
 * The GUI (M8) changes statuses while the pipeline (M9) adds newly scored papers, so every change is
 * a read-modify-write done under one lock per file (all store objects for the same file share it),
 * and each write replaces the file atomically. Neither side can lose the other's update, and a crash
 * mid-write leaves the previous queue intact.
 */
public final class ScoredQueueStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<StoredScoredPaper>> FILE_FORMAT = new TypeReference<>() {
    };
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /**
     * JSON shape of one queue entry; the candidate is stored as M7c stores it. {@code recommended} is
     * absent in queues written before recommendations existed, and reads as false.
     */
    private record StoredScoredPaper(StoredCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status, Boolean recommended) {

        static StoredScoredPaper from(ScoredPaper scored) {
            return new StoredScoredPaper(StoredCandidate.from(scored.candidate()), scored.profileName(), scored.score(),
                    scored.rationale(), scored.status(), scored.recommended());
        }

        ScoredPaper toScoredPaper() throws IOException {
            try {
                return new ScoredPaper(candidate.toCandidate(), profileName, score, rationale, status,
                        Boolean.TRUE.equals(recommended));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IOException("Corrupt queue entry for " + profileName + ": " + e.getMessage(), e);
            }
        }
    }

    private final Path storeFile;
    private final Object lock;

    public ScoredQueueStore(Path storeFile) {
        this.storeFile = storeFile;
        this.lock = LOCKS.computeIfAbsent(storeFile.toAbsolutePath().normalize(), path -> new Object());
    }

    public static ScoredQueueStore atDefaultLocation() {
        return new ScoredQueueStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "scored_queue.json"));
    }

    /**
     * @return the queue in the order entries were first added; empty if nothing has been queued yet
     * @throws IOException if the file exists but is unreadable or not a valid queue
     */
    public List<ScoredPaper> load() throws IOException {
        synchronized (lock) {
            return read();
        }
    }

    /**
     * Adds newly scored papers. A paper already queued for the same student (see
     * {@link ScoredPaper#queueKey()}) takes the new score, rationale and recommendation but keeps its
     * place and its review status, so re-scoring never undoes the professor's decision.
     *
     * @return the whole queue after the change
     */
    public List<ScoredPaper> addAll(Collection<ScoredPaper> scored) throws IOException {
        synchronized (lock) {
            Map<String, ScoredPaper> queue = byKey(read());
            for (ScoredPaper paper : scored) {
                ScoredPaper existing = queue.get(paper.queueKey());
                queue.put(paper.queueKey(), existing == null ? paper : paper.withStatus(existing.status()));
            }
            List<ScoredPaper> updated = new ArrayList<>(queue.values());
            write(updated);
            return updated;
        }
    }

    /**
     * Records the professor's decision on one queued paper.
     *
     * @return false if that paper is not queued for that student
     */
    public boolean setStatus(ScoredPaper paper, ReviewStatus status) throws IOException {
        synchronized (lock) {
            Map<String, ScoredPaper> queue = byKey(read());
            ScoredPaper existing = queue.get(paper.queueKey());
            if (existing == null) {
                return false;
            }
            queue.put(paper.queueKey(), existing.withStatus(status));
            write(new ArrayList<>(queue.values()));
            return true;
        }
    }

    private List<ScoredPaper> read() throws IOException {
        if (!Files.exists(storeFile)) {
            return new ArrayList<>();
        }
        List<ScoredPaper> queue = new ArrayList<>();
        for (StoredScoredPaper stored : JSON.readValue(Files.readAllBytes(storeFile), FILE_FORMAT)) {
            queue.add(stored.toScoredPaper());
        }
        return queue;
    }

    private void write(List<ScoredPaper> queue) throws IOException {
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(queue.stream().map(StoredScoredPaper::from).toList()));
    }

    private static Map<String, ScoredPaper> byKey(List<ScoredPaper> queue) {
        Map<String, ScoredPaper> byKey = new LinkedHashMap<>();
        for (ScoredPaper paper : queue) {
            byKey.put(paper.queueKey(), paper);
        }
        return byKey;
    }
}
