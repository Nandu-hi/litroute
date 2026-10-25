package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    /** How M6's rationale states the relevance, for entries queued before it was stored on its own. */
    private static final Pattern STATED_RELEVANCE = Pattern.compile("\\bRelevance (\\d(?:\\.\\d+)?) \\(");

    /**
     * JSON shape of one queue entry; the candidate is stored as M7c stores it. {@code recommended} is
     * absent in queues written before recommendations existed, and reads as false. {@code relevance} is
     * null when the matcher did not measure it; in queues written before it was stored, it is read from
     * the rationale, which states it. {@link SentPaperStore} stores sent papers in the same shape.
     */
    record StoredScoredPaper(StoredCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status, Boolean recommended, Double relevance) {

        /**
         * Input: a scored paper.
         * Output: its JSON shape (empty relevance as null).
         * Used by: {@link ScoredQueueStore#write}, SentPaperStore.addAll.
         * Example: paper with relevance empty -> StoredScoredPaper with {@code relevance == null}
         */
        static StoredScoredPaper from(ScoredPaper scored) {
            return new StoredScoredPaper(StoredCandidate.from(scored.candidate()), scored.profileName(), scored.score(),
                    scored.rationale(), scored.status(), scored.recommended(), scored.relevance().orElse(null));
        }

        /**
         * Input: none (this stored entry).
         * Output: the scored paper (missing recommended -> false, missing relevance -> read from rationale); IOException if invalid.
         * Used by: {@link ScoredQueueStore#read}, SentPaperStore.read.
         * Example: old entry, rationale "Relevance 0.82 (...)" -> relevance {@code Optional[0.82]}
         */
        ScoredPaper toScoredPaper() throws IOException {
            try {
                return new ScoredPaper(candidate.toCandidate(), profileName, score, rationale, status,
                        Boolean.TRUE.equals(recommended), Optional.ofNullable(relevance).or(() -> statedIn(rationale)));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IOException("Corrupt queue entry for " + profileName + ": " + e.getMessage(), e);
            }
        }
    }

    private final Path storeFile;
    private final Path pdfRoot;
    private final Object lock;

    /**
     * Input: the queue's JSON file.
     * Output: a queue keeping matched PDFs in a {@code matched} folder next to that file.
     * Used by: tests with a temp folder.
     * Example: {@code new ScoredQueueStore(tmp/q.json).pdfRoot()} -> {@code tmp/matched}
     */
    public ScoredQueueStore(Path storeFile) {
        this(storeFile, storeFile.resolveSibling("matched"));
    }

    /**
     * Input: the queue's JSON file and the folder holding one subfolder of matched PDFs per student.
     * Output: a queue sharing the lock of every other store object on the same file.
     * Used by: {@link #atDefaultLocation}, the one-argument constructor, tests.
     * Example: {@code new ScoredQueueStore(d/scored_queue.json, d/matched)}
     */
    public ScoredQueueStore(Path storeFile, Path pdfRoot) {
        this.storeFile = storeFile;
        this.pdfRoot = pdfRoot.toAbsolutePath().normalize();
        this.lock = LOCKS.computeIfAbsent(storeFile.toAbsolutePath().normalize(), path -> new Object());
    }

    /**
     * Input: none.
     * Output: a queue at {@code ~/.litroute/data/scored_queue.json} with PDFs under {@code ~/.litroute/data/matched/}.
     * Used by: PipelineRunner.atDefaultLocations (M9), LitRouteApp (M8), ApprovedPaperSender.atDefaultLocations (M10), RelevanceScorerCli.
     * Example: {@code atDefaultLocation().pdfRoot()} -> /home/u/.litroute/data/matched
     */
    public static ScoredQueueStore atDefaultLocation() {
        Path data = Path.of(System.getProperty("user.home"), ".litroute", "data");
        return new ScoredQueueStore(data.resolve("scored_queue.json"), data.resolve("matched"));
    }

    /**
     * Input: none.
     * Output: the folder holding one subfolder of matched PDFs per student.
     * Used by: RelevanceScorerCli to print where the PDFs went; {@link #clear}.
     * Example: -> {@code ~/.litroute/data/matched}
     */
    public Path pdfRoot() {
        return pdfRoot;
    }

    /**
     * Input: a profile name.
     * Output: {@code pdfRoot/<name>}; throws if the name would lead outside pdfRoot.
     * Used by: {@link #withPdfFiled}.
     * Example: {@code pdfFolder("alice")} -> {@code matched/alice}; {@code pdfFolder("../x")} -> throws
     */
    public Path pdfFolder(String profileName) {
        Path folder = pdfRoot.resolve(profileName).normalize();
        if (!folder.getParent().equals(pdfRoot)) {
            throw new IllegalArgumentException("Not a usable folder name for a profile: " + profileName);
        }
        return folder;
    }

    /**
     * Input: none.
     * Output: the queue in first-added order; empty if nothing queued yet; IOException if corrupt.
     * Used by: ReviewPane (M8) to show it, PipelineRunner (M9) for counts and scored keys, ApprovedPaperSender (M10).
     * Example: -> {@code [alice/"Wave loads" PENDING, bob/"Soil creep" APPROVED]}
     */
    public List<ScoredPaper> load() throws IOException {
        synchronized (lock) {
            return read();
        }
    }

    /**
     * Adds what a {@code PaperMatcher} matched: each student's papers, as {@link #addAll(Collection)}.
     * Input: M6 output, student -> their scored papers.
     * Output: the whole queue after the change.
     * Used by: RelevanceScorerCli; tests.
     * Example: {@code addAll({alice=[X], bob=[Y]})} -> queue [..., alice/X, bob/Y]
     */
    public List<ScoredPaper> addAll(Map<String, List<ScoredPaper>> matches) throws IOException {
        return addAll(matches.values().stream().flatMap(List::stream).toList());
    }

    /**
     * Adds newly scored papers. A paper already queued for the same student takes the new score, rationale
     * and recommendation but keeps its place and review status, so re-scoring never undoes a decision.
     * Each PDF is copied into the student's folder and the entry points to the copy.
     * Input: scored papers.
     * Output: the whole queue after the change.
     * Used by: PipelineRunner (M9) after M6 (papers already sent filtered out first); {@link #addAll(Map)}.
     * Example: alice/X APPROVED queued, {@code addAll([alice/X score 0.9])} -> alice/X score 0.9, still APPROVED
     */
    public List<ScoredPaper> addAll(Collection<ScoredPaper> scored) throws IOException {
        synchronized (lock) {
            Map<String, ScoredPaper> queue = byKey(read());
            for (ScoredPaper matched : scored) {
                ScoredPaper paper = withPdfFiled(matched);
                ScoredPaper existing = queue.get(paper.queueKey());
                // Re-put keeps the LinkedHashMap position; carry over the professor's status.
                queue.put(paper.queueKey(), existing == null ? paper : paper.withStatus(existing.status()));
            }
            List<ScoredPaper> updated = new ArrayList<>(queue.values());
            write(updated);
            return updated;
        }
    }

    /**
     * Empties the queue (decisions included) and deletes its PDF copies in the students' folders, then any
     * folder left empty. A file outside {@link #pdfRoot()} is not this store's copy and is left alone.
     * Input: none.
     * Output: how many entries the queue held.
     * Used by: PipelineRunner.reset (M9, Clear and re-run).
     * Example: queue of 3 with copies in matched/alice -> returns 3; matched/alice deleted
     */
    public int clear() throws IOException {
        synchronized (lock) {
            List<ScoredPaper> queue = read();
            write(List.of());
            // Queue file first: a crash now leaves stray PDFs, never entries pointing to deleted files.
            for (ScoredPaper paper : queue) {
                Optional<Path> copy = paper.candidate().pdfPath().map(Path::normalize).filter(path -> path.startsWith(pdfRoot));
                if (copy.isPresent()) {
                    Files.deleteIfExists(copy.get());
                    Path folder = copy.get().getParent();
                    try (Stream<Path> left = Files.list(folder)) {
                        if (left.findAny().isEmpty()) {
                            Files.delete(folder);
                        }
                    } catch (NoSuchFileException gone) {
                        // removed with an earlier entry's copy
                    }
                }
            }
            return queue.size();
        }
    }

    /**
     * Takes these papers out of the queue (by {@link ScoredPaper#queueKey()}), leaving their PDF copies.
     * Input: papers to remove.
     * Output: how many of them were queued; the file is rewritten only if any were.
     * Used by: ApprovedPaperSender (M10), after recording them in {@link SentPaperStore}.
     * Example: queue [alice/X, bob/Y], {@code remove([alice/X])} -> 1; queue [bob/Y]
     */
    public int remove(Collection<ScoredPaper> papers) throws IOException {
        Set<String> keys = papers.stream().map(ScoredPaper::queueKey).collect(Collectors.toSet());
        synchronized (lock) {
            List<ScoredPaper> queue = read();
            List<ScoredPaper> kept = queue.stream().filter(paper -> !keys.contains(paper.queueKey())).toList();
            if (kept.size() < queue.size()) {
                write(kept);
            }
            return queue.size() - kept.size();
        }
    }

    /**
     * Records the professor's decision on one queued paper.
     * Input: the paper (matched by queue key) and its new status.
     * Output: true if updated; false if that paper is not queued for that student.
     * Used by: ReviewPane (M8) Approve/Drop.
     * Example: {@code setStatus(alice/X, APPROVED)} -> true; {@code setStatus(zoe/X, APPROVED)} -> false
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
     * Copies the paper's PDF into the student's folder under its own name; the same bytes already there are
     * reused, other bytes under that name get {@code x-2.pdf}. A missing PDF is logged and the paper kept as is.
     * Input: a scored paper.
     * Output: the paper, pointing at the student's copy (unchanged if it has no PDF, or it is gone).
     * Used by: {@link #addAll(Collection)}.
     * Example: alice, pdfs/x.pdf -> pdfPath matched/alice/x.pdf (or matched/alice/x-2.pdf if another x.pdf is there)
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
        // Next free name, unless a file of that name already holds these exact bytes.
        for (int n = 2; Files.exists(target) && Files.mismatch(source, target) != -1; n++) {
            target = folder.resolve(stem + "-" + n + extension);
        }
        if (!Files.exists(target)) {
            AtomicFiles.writeAtomically(target, Files.readAllBytes(source));
        }
        PaperCandidate filed = paper.candidate().withPdfPath(Optional.of(target));
        return new ScoredPaper(filed, paper.profileName(), paper.score(), paper.rationale(), paper.status(),
                paper.recommended(), paper.relevance());
    }

    /**
     * Input: an M6 rationale (null allowed).
     * Output: the relevance it states, or empty.
     * Used by: {@link StoredScoredPaper#toScoredPaper} for entries saved before relevance was stored.
     * Example: {@code statedIn("Relevance 0.82 (share 0.6)")} -> {@code Optional[0.82]}
     */
    private static Optional<Double> statedIn(String rationale) {
        Matcher stated = rationale == null ? null : STATED_RELEVANCE.matcher(rationale);
        return stated != null && stated.find() ? Optional.of(Double.parseDouble(stated.group(1))) : Optional.empty();
    }

    /**
     * Input: none; the caller holds the lock.
     * Output: the queue on disk; empty if no file; IOException if corrupt.
     * Used by: every public method that reads or changes the queue.
     * Example: file of 2 entries -> list of 2 ScoredPapers
     */
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

    /**
     * Input: the whole queue; the caller holds the lock.
     * Output: none; the file is atomically replaced.
     * Used by: addAll, clear, remove, setStatus.
     * Example: {@code write([])} -> file {@code []}
     */
    private void write(List<ScoredPaper> queue) throws IOException {
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(queue.stream().map(StoredScoredPaper::from).toList()));
    }

    /**
     * Input: the queue.
     * Output: the same entries keyed by queue key, in queue order.
     * Used by: {@link #addAll(Collection)}, {@link #setStatus}.
     * Example: {@code [alice/X]} -> {@code {"alice\ndoi:x" = alice/X}}
     */
    private static Map<String, ScoredPaper> byKey(List<ScoredPaper> queue) {
        Map<String, ScoredPaper> byKey = new LinkedHashMap<>();
        for (ScoredPaper paper : queue) {
            byKey.put(paper.queueKey(), paper);
        }
        return byKey;
    }
}
