package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.PaperCandidate;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * M7e: the M6-to-M8/M9 boundary - the professor's review queue, kept across restarts. Holds every
 * scored (paper, student) pair with its review status, in the order they were first queued.
 *
 * It also keeps each student's matched PDFs together: a queued paper with a PDF has it copied into
 * the student's folder ({@code ~/.litroute/data/matched/<profile>/} by default), and its queue entry
 * points to that copy, so M10 can attach it. A paper matched to two students is copied into both
 * folders. A paper without a PDF (paywalled, say) is queued all the same.
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
    private static final Logger LOG = Logger.getLogger(ScoredQueueStore.class.getName());

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
    private final Path pdfRoot;
    private final Object lock;

    /** A queue keeping the matched PDFs in a {@code matched} folder next to the queue file. */
    public ScoredQueueStore(Path storeFile) {
        this(storeFile, storeFile.resolveSibling("matched"));
    }

    /**
     * @param storeFile the queue's JSON file
     * @param pdfRoot   the folder holding one subfolder of matched PDFs per student
     */
    public ScoredQueueStore(Path storeFile, Path pdfRoot) {
        this.storeFile = storeFile;
        this.pdfRoot = pdfRoot.toAbsolutePath().normalize();
        this.lock = LOCKS.computeIfAbsent(storeFile.toAbsolutePath().normalize(), path -> new Object());
    }

    public static ScoredQueueStore atDefaultLocation() {
        Path data = Path.of(System.getProperty("user.home"), ".litroute", "data");
        return new ScoredQueueStore(data.resolve("scored_queue.json"), data.resolve("matched"));
    }

    /** The folder holding one subfolder of matched PDFs per student. */
    public Path pdfRoot() {
        return pdfRoot;
    }

    /** The folder the student's matched PDFs are copied into. */
    public Path pdfFolder(String profileName) {
        Path folder = pdfRoot.resolve(profileName).normalize();
        if (!folder.getParent().equals(pdfRoot)) {
            throw new IllegalArgumentException("Not a usable folder name for a profile: " + profileName);
        }
        return folder;
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
     * Adds what a {@code PaperMatcher} matched: each student's papers, as {@link #addAll(Collection)}.
     *
     * @return the whole queue after the change
     */
    public List<ScoredPaper> addAll(Map<String, List<ScoredPaper>> matches) throws IOException {
        return addAll(matches.values().stream().flatMap(List::stream).toList());
    }

    /**
     * Adds newly scored papers. A paper already queued for the same student (see
     * {@link ScoredPaper#queueKey()}) takes the new score, rationale and recommendation but keeps its
     * place and its review status, so re-scoring never undoes the professor's decision. Each paper's PDF,
     * if it has one, is copied into the student's folder (see {@link #pdfFolder}) and the queued entry
     * points to the copy.
     *
     * @return the whole queue after the change
     */
    public List<ScoredPaper> addAll(Collection<ScoredPaper> scored) throws IOException {
        synchronized (lock) {
            Map<String, ScoredPaper> queue = byKey(read());
            for (ScoredPaper matched : scored) {
                ScoredPaper paper = withPdfFiled(matched);
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

    /**
     * The paper with its PDF copied into the student's folder, under the PDF's own file name. A file of
     * that name holding the same bytes is the same PDF, filed before; one holding other bytes belongs to
     * another paper, so this copy gets a numbered name ({@code x-2.pdf}). A PDF that is gone from where
     * the pipeline saved it is left as it is: the paper is still queued.
     */
    private ScoredPaper withPdfFiled(ScoredPaper paper) throws IOException {
        Optional<Path> pdf = paper.candidate().pdfPath();
        if (pdf.isEmpty()) {
            return paper;
        }
        Path source = pdf.get();
        if (!Files.isRegularFile(source)) {
            LOG.warning("PDF of \"" + paper.candidate().title() + "\" is gone, not copied for " + paper.profileName()
                    + ": " + source);
            return paper;
        }
        Path folder = pdfFolder(paper.profileName());
        if (source.toAbsolutePath().normalize().getParent().equals(folder)) {
            return paper; // already the student's copy
        }
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path target = folder.resolve(name);
        for (int n = 2; Files.exists(target) && Files.mismatch(source, target) != -1; n++) {
            target = folder.resolve(stem + "-" + n + extension);
        }
        if (!Files.exists(target)) {
            AtomicFiles.writeAtomically(target, Files.readAllBytes(source));
        }
        PaperCandidate filed = paper.candidate().withPdfPath(Optional.of(target));
        return new ScoredPaper(filed, paper.profileName(), paper.score(), paper.rationale(), paper.status(),
                paper.recommended());
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
