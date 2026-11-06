package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.store.ScoredQueueStore.StoredScoredPaper;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The papers already emailed to their students (M10), kept apart from the review queue (M7e): a sent
 * paper leaves the queue and is recorded here with the time it went. M9 never queues a paper for a
 * student it was already sent to, so a student never gets the same paper twice, not even after the
 * pipeline is cleared and re-run, which leaves this record alone. A paper is identified the way the
 * queue identifies it ({@link ScoredPaper#queueKey()}): the student plus the paper's DOI, else its
 * compacted title.
 *
 * Written under one lock per file path, like the queue, since the UI and the pipeline both use it.
 */
public final class SentPaperStore {

    /**
     * One paper as it was sent.
     *
     * @param paper  its queue entry, with status {@link ReviewStatus#SENT}
     * @param sentAt when it was sent
     */
    public record Sent(ScoredPaper paper, Instant sentAt) {
    }

    /** JSON shape of one sent paper: the queue entry's shape plus the time it was sent. */
    private record StoredSent(StoredScoredPaper paper, String sentAt) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<StoredSent>> FILE_FORMAT = new TypeReference<>() {
    };
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path storeFile;
    private final Object lock;

    /**
     * Shares the lock of every store on the same file. Nothing is read until {@link #load}.
     *
     * @param storeFile the JSON file to keep this store in
     */
    public SentPaperStore(Path storeFile) {
        this.storeFile = storeFile;
        this.lock = LOCKS.computeIfAbsent(storeFile.toAbsolutePath().normalize(), path -> new Object());
    }

    /** {@return a store for {@code ~/.litroute/data/sent_papers.json}} */
    public static SentPaperStore atDefaultLocation() {
        return new SentPaperStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "sent_papers.json"));
    }

    /**
     * {@return every sent paper with its time, oldest first; empty if nothing has been sent}
     *
     * @throws IOException if the file can't be read or is corrupt
     */
    public List<Sent> load() throws IOException {
        synchronized (lock) {
            return read();
        }
    }

    /**
     * {@return the {@link ScoredPaper#queueKey()} of every paper sent}
     * Used by: PipelineRunner (M9) to not re-queue them; ApprovedPaperSender (M10) to settle the queue.
     *
     * @throws IOException if the file can't be read or is corrupt
     */
    public Set<String> sentKeys() throws IOException {
        return load().stream().map(sent -> sent.paper().queueKey()).collect(Collectors.toSet());
    }

    /**
     * Records papers as sent. One already recorded for the same student keeps a single entry, with the new time,
     * moved to the end.
     * Used by: ApprovedPaperSender (M10) right after each email goes out.
     *
     * @param papers the papers just emailed
     * @param sentAt when
     * @throws IOException if the file can't be read or written
     */
    public void addAll(Collection<ScoredPaper> papers, Instant sentAt) throws IOException {
        synchronized (lock) {
            Map<String, Sent> sent = new LinkedHashMap<>();
            read().forEach(entry -> sent.put(entry.paper().queueKey(), entry));
            for (ScoredPaper paper : papers) {
                // Remove first so a re-sent paper moves to the end (newest).
                sent.remove(paper.queueKey());
                sent.put(paper.queueKey(), new Sent(paper.withStatus(ReviewStatus.SENT), sentAt));
            }
            AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(sent.values().stream()
                    .map(entry -> new StoredSent(StoredScoredPaper.from(entry.paper()), entry.sentAt().toString()))
                    .toList()));
        }
    }

    /**
     * Forgets every paper sent. Nothing else does: it is what keeps a student from getting a paper twice.
     *
     * @throws IOException if the file can't be deleted
     */
    public void clear() throws IOException {
        synchronized (lock) {
            Files.deleteIfExists(storeFile);
        }
    }

    /**
     * Input: none; the caller holds the lock.
     * Output: the sent papers on disk; empty if no file; IOException on a corrupt entry.
     * Used by: {@link #load}, {@link #addAll}.
     * Example: file with one entry -> {@code [Sent(...)]}
     */
    private List<Sent> read() throws IOException {
        if (!Files.exists(storeFile)) {
            return new ArrayList<>();
        }
        List<Sent> sent = new ArrayList<>();
        for (StoredSent stored : JSON.readValue(Files.readAllBytes(storeFile), FILE_FORMAT)) {
            try {
                sent.add(new Sent(stored.paper().toScoredPaper(), Instant.parse(stored.sentAt())));
            } catch (RuntimeException e) {
                throw new IOException("Corrupt sent-paper entry in " + storeFile + ": " + e.getMessage(), e);
            }
        }
        return sent;
    }
}
