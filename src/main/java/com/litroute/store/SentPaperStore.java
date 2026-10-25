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

    /** One paper as it was sent: its queue entry, with status {@link ReviewStatus#SENT}, and when. */
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
     * Input: the JSON file to keep this store in.
     * Output: a store for that file (nothing is read yet), sharing the lock of every store on the same file.
     * Used by: {@link #atDefaultLocation}; tests with a temp folder.
     * Example: {@code new SentPaperStore(tmp.resolve("sent_papers.json")).load()} -> empty
     */
    public SentPaperStore(Path storeFile) {
        this.storeFile = storeFile;
        this.lock = LOCKS.computeIfAbsent(storeFile.toAbsolutePath().normalize(), path -> new Object());
    }

    /**
     * Input: none.
     * Output: a store for {@code ~/.litroute/data/sent_papers.json}.
     * Used by: PipelineRunner.atDefaultLocations (M9), LitRouteApp/ReviewPane (M8), ApprovedPaperSender.atDefaultLocations (M10).
     * Example: {@code atDefaultLocation()} -> store for /home/u/.litroute/data/sent_papers.json
     */
    public static SentPaperStore atDefaultLocation() {
        return new SentPaperStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "sent_papers.json"));
    }

    /**
     * Input: none.
     * Output: every sent paper with its time, oldest first; empty if nothing sent yet.
     * Used by: ReviewPane (M8) "Sent" view; {@link #sentKeys}.
     * Example: -> {@code [Sent(alice/"Wave loads", 2026-10-20T10:00Z)]}
     */
    public List<Sent> load() throws IOException {
        synchronized (lock) {
            return read();
        }
    }

    /**
     * Input: none.
     * Output: the {@link ScoredPaper#queueKey()} of every paper sent.
     * Used by: PipelineRunner (M9) to not re-queue them; ApprovedPaperSender (M10) to take them out of the queue.
     * Example: -> {@code {"alice\ndoi:10.1/x"}}
     */
    public Set<String> sentKeys() throws IOException {
        return load().stream().map(sent -> sent.paper().queueKey()).collect(Collectors.toSet());
    }

    /**
     * Records papers as sent; one already recorded for the same student keeps one entry, with the new time.
     * Input: the papers just emailed and when.
     * Output: none; the file holds them with status SENT, re-sent ones moved to the end.
     * Used by: ApprovedPaperSender (M10) right after each email goes out.
     * Example: {@code addAll([alice/X], t2)} with alice/X sent at t1 -> one entry alice/X at t2
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
