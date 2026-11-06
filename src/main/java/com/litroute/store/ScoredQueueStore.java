package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * folders. A paper without a PDF (paywalled, say) is queued all the same. A paper M6 matched to no one
 * ({@link ReviewStatus#UNMATCHED}) keeps pointing at its download until the professor takes it up
 * (approves, drops, returns it to pending or moves it), and only then is copied.
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
     * the rationale, which states it. {@code listedAuthor} is null for a paper by no listed author, and in
     * queues written before the authors list existed. {@code fits} is null in queues written before every
     * student's score was kept. {@link SentPaperStore} stores sent papers in the same shape.
     */
    record StoredScoredPaper(StoredCandidate candidate, String profileName, double score, String rationale,
            ReviewStatus status, Boolean recommended, Double relevance, String listedAuthor, List<StoredFit> fits) {

        /**
         * Input: a scored paper.
         * Output: its JSON shape (empty relevance as null).
         * Used by: {@link ScoredQueueStore#write}, SentPaperStore.addAll.
         * Example: paper with relevance empty -> StoredScoredPaper with {@code relevance == null}
         */
        static StoredScoredPaper from(ScoredPaper scored) {
            return new StoredScoredPaper(StoredCandidate.from(scored.candidate()), scored.profileName(), scored.score(),
                    scored.rationale(), scored.status(), scored.recommended(), scored.relevance().orElse(null),
                    scored.listedAuthor().orElse(null), scored.fits().stream().map(StoredFit::from).toList());
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
                        Boolean.TRUE.equals(recommended), Optional.ofNullable(relevance).or(() -> statedIn(rationale)),
                        Optional.ofNullable(listedAuthor),
                        fits == null ? List.of() : fits.stream().map(StoredFit::toFit).toList());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IOException("Corrupt queue entry for " + profileName + ": " + e.getMessage(), e);
            }
        }
    }

    /** JSON shape of one student's {@link ScoredPaper.Fit}. */
    record StoredFit(String profileName, double score, double relevance, String rationale) {

        /**
         * Input: a fit.
         * Output: its JSON shape.
         * Used by: {@link StoredScoredPaper#from}.
         * Example: Fit(bob, 0.21, 0.55, "...") -> StoredFit(bob, 0.21, 0.55, "...")
         */
        static StoredFit from(ScoredPaper.Fit fit) {
            return new StoredFit(fit.profileName(), fit.score(), fit.relevance(), fit.rationale());
        }

        /**
         * Input: none (this stored fit).
         * Output: the fit; IllegalArgumentException if invalid (reported as a corrupt entry).
         * Used by: {@link StoredScoredPaper#toScoredPaper}.
         * Example: StoredFit(bob, 0.21, 0.55, "...") -> Fit(bob, 0.21, 0.55, "...")
         */
        ScoredPaper.Fit toFit() {
            return new ScoredPaper.Fit(profileName, score, relevance, rationale);
        }
    }

    private final Path storeFile;
    private final Path pdfRoot;
    private final Object lock;

    /**
     * A queue keeping matched PDFs in a {@code matched} folder next to {@code storeFile}.
     *
     * @param storeFile the queue's JSON file
     */
    public ScoredQueueStore(Path storeFile) {
        this(storeFile, storeFile.resolveSibling("matched"));
    }

    /**
     * A queue sharing the lock of every other store object on the same file.
     *
     * @param storeFile the queue's JSON file
     * @param pdfRoot   the folder holding one subfolder of matched PDFs per student
     */
    public ScoredQueueStore(Path storeFile, Path pdfRoot) {
        this.storeFile = storeFile;
        this.pdfRoot = pdfRoot.toAbsolutePath().normalize();
        this.lock = LOCKS.computeIfAbsent(storeFile.toAbsolutePath().normalize(), path -> new Object());
    }

    /** {@return a queue at {@code ~/.litroute/data/scored_queue.json} with PDFs under {@code matched/}} */
    public static ScoredQueueStore atDefaultLocation() {
        Path data = Path.of(System.getProperty("user.home"), ".litroute", "data");
        return new ScoredQueueStore(data.resolve("scored_queue.json"), data.resolve("matched"));
    }

    /** {@return the folder holding one subfolder of matched PDFs per student} */
    public Path pdfRoot() {
        return pdfRoot;
    }

    /**
     * {@return {@code pdfRoot/<name>}, the student's PDF folder}
     *
     * @param profileName a profile name
     * @throws IllegalArgumentException if the name leads outside pdfRoot, as {@code "../x"} does
     */
    public Path pdfFolder(String profileName) {
        Path folder = pdfRoot.resolve(profileName).normalize();
        if (!folder.getParent().equals(pdfRoot)) {
            throw new IllegalArgumentException("Not a usable folder name for a profile: " + profileName);
        }
        return folder;
    }

    /**
     * {@return the queue in first-added order; empty if nothing is queued yet}
     *
     * @throws IOException if the file can't be read or is corrupt
     */
    public List<ScoredPaper> load() throws IOException {
        synchronized (lock) {
            return read();
        }
    }

    /**
     * Adds what a {@code PaperMatcher} matched, as {@link #addAll(Collection)}.
     *
     * @param matches M6 output: student to their scored papers
     * @return the whole queue after the change
     * @throws IOException if the file or a PDF copy can't be read or written
     */
    public List<ScoredPaper> addAll(Map<String, List<ScoredPaper>> matches) throws IOException {
        return addAll(matches.values().stream().flatMap(List::stream).toList());
    }

    /**
     * Adds newly scored papers. A paper already queued for the same student takes the new score, rationale
     * and recommendation but keeps its place and review status, so re-scoring never undoes a decision.
     * Each PDF is copied into the student's folder and the entry points to the copy, unless the paper is
     * {@link ReviewStatus#UNMATCHED}.
     * Example: alice/X APPROVED queued, {@code addAll([alice/X score 0.9])} -> alice/X score 0.9, still APPROVED
     *
     * @param scored newly scored papers
     * @return the whole queue after the change
     * @throws IOException if the file or a PDF copy can't be read or written
     */
    public List<ScoredPaper> addAll(Collection<ScoredPaper> scored) throws IOException {
        synchronized (lock) {
            Map<String, ScoredPaper> queue = byKey(read());
            for (ScoredPaper matched : scored) {
                ScoredPaper existing = queue.get(matched.queueKey());
                // Re-put keeps the LinkedHashMap position; carry over the professor's status.
                ScoredPaper paper = existing == null ? matched : matched.withStatus(existing.status());
                queue.put(paper.queueKey(), paper.status() == ReviewStatus.UNMATCHED ? paper : withPdfFiled(paper));
            }
            List<ScoredPaper> updated = new ArrayList<>(queue.values());
            write(updated);
            return updated;
        }
    }

    /**
     * Empties the queue (decisions included) and deletes its PDF copies, then any folder left empty. A file
     * outside {@link #pdfRoot()} is not this store's copy and is left alone.
     * Used by: PipelineRunner.reset (M9), DataCaches (the Caches tab).
     *
     * @return how many entries the queue held
     * @throws IOException if the file or a copy can't be deleted
     */
    public int clear() throws IOException {
        synchronized (lock) {
            List<ScoredPaper> queue = read();
            write(List.of());
            // Queue file first: a crash now leaves stray PDFs, never entries pointing to deleted files.
            for (ScoredPaper paper : queue) {
                Optional<Path> copy = pdfCopy(paper);
                if (copy.isPresent()) {
                    deleteCopy(copy.get());
                }
            }
            return queue.size();
        }
    }

    /**
     * Input: a queued or sent paper.
     * Output: its PDF if that is a copy in the students' folders; a file elsewhere is not this store's.
     * Used by: {@link #clear}, DataCaches.
     * Example: pdfPath matched/alice/x.pdf -> that path; pdfPath pdfs/x.pdf -> empty
     */
    Optional<Path> pdfCopy(ScoredPaper paper) {
        return paper.candidate().pdfPath().map(Path::normalize).filter(path -> path.startsWith(pdfRoot));
    }

    /**
     * Input: a PDF copy in a student's folder.
     * Output: none; the copy is deleted, and the folder too if that leaves it empty.
     * Used by: {@link #clear}, DataCaches.
     * Example: the last PDF in matched/alice -> matched/alice is gone too
     */
    void deleteCopy(Path copy) throws IOException {
        Files.deleteIfExists(copy);
        Path folder = copy.getParent();
        try (Stream<Path> left = Files.list(folder)) {
            if (left.findAny().isEmpty()) {
                Files.delete(folder);
            }
        } catch (NoSuchFileException gone) {
            // removed with an earlier entry's copy
        }
    }

    /**
     * Takes these papers out of the queue (by {@link ScoredPaper#queueKey()}), leaving their PDF copies.
     * Used by: ApprovedPaperSender (M10), after recording them in {@link SentPaperStore}.
     *
     * @param papers papers to remove
     * @return how many of them were queued; the file is rewritten only if any were
     * @throws IOException if the file can't be read or written
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
     * Takes M6's unmatched entries for these papers out of the queue, so the next run scores them again. An entry
     * the professor has acted on stays.
     * Used by: PriorityAuthors (M9), when an author is added to the list.
     *
     * @param paperKeys paper keys ({@link ScoredPaper#paperKey})
     * @return how many entries were removed; the file is rewritten only if any were
     * @throws IOException if the file can't be read or written
     */
    public int removeUnmatched(Collection<String> paperKeys) throws IOException {
        Set<String> keys = Set.copyOf(paperKeys);
        synchronized (lock) {
            List<ScoredPaper> queue = read();
            List<ScoredPaper> kept = queue.stream().filter(paper -> paper.status() != ReviewStatus.UNMATCHED
                    || !keys.contains(ScoredPaper.paperKey(paper.candidate()))).toList();
            if (kept.size() < queue.size()) {
                write(kept);
            }
            return queue.size() - kept.size();
        }
    }

    /**
     * Records the professor's decision on one queued paper. An unmatched paper's PDF is copied into the student's
     * folder once the professor decides on it.
     * Used by: ReviewPane (M8) Approve/Drop.
     *
     * @param paper  the paper, matched by queue key
     * @param status its new status
     * @return false if that paper is not queued for that student
     * @throws IOException if the file or the PDF copy can't be read or written
     */
    public boolean setStatus(ScoredPaper paper, ReviewStatus status) throws IOException {
        synchronized (lock) {
            Map<String, ScoredPaper> queue = byKey(read());
            ScoredPaper existing = queue.get(paper.queueKey());
            if (existing == null) {
                return false;
            }
            ScoredPaper decided = existing.withStatus(status);
            queue.put(paper.queueKey(), status == ReviewStatus.UNMATCHED ? decided : withPdfFiled(decided));
            write(new ArrayList<>(queue.values()));
            return true;
        }
    }

    /**
     * Gives a queued paper to another student ({@link ScoredPaper#movedTo}): it keeps its place, takes M6's score
     * for that student, and its PDF is copied into their folder; the old copy is deleted once nothing points to it.
     * The queue is written first, so a crash leaves a stray PDF, never an entry pointing to a deleted file.
     * Used by: ReviewPane (M8) Move.
     *
     * @param paper   the queued paper (by queue key)
     * @param student the student to give it to
     * @return the moved entry; empty if the paper is not queued for its student
     * @throws IllegalArgumentException if it is already queued for {@code student}, or can't be moved there
     * @throws IllegalStateException    if it has been sent
     * @throws IOException              if the file or a PDF copy can't be read or written
     */
    public Optional<ScoredPaper> move(ScoredPaper paper, String student) throws IOException {
        synchronized (lock) {
            List<ScoredPaper> queue = read();
            int at = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).queueKey().equals(paper.queueKey())) {
                    at = i;
                }
            }
            if (at < 0) {
                return Optional.empty();
            }
            ScoredPaper existing = queue.get(at);
            ScoredPaper unfiled = existing.movedTo(student);
            if (queue.stream().anyMatch(other -> other.queueKey().equals(unfiled.queueKey()))) {
                throw new IllegalArgumentException("\"" + existing.candidate().title() + "\" is already queued for "
                        + student);
            }
            ScoredPaper moved = withPdfFiled(unfiled);
            queue.set(at, moved);
            write(queue);
            Optional<Path> oldCopy = pdfCopy(existing);
            if (oldCopy.isPresent() && queue.stream().map(this::pdfCopy).noneMatch(oldCopy::equals)) {
                deleteCopy(oldCopy.get());
            }
            return Optional.of(moved);
        }
    }

    /**
     * Copies the paper's PDF into the student's folder under its own name; the same bytes already there are
     * reused, other bytes under that name get {@code x-2.pdf}. A missing PDF is logged and the paper kept as is.
     * Input: a scored paper.
     * Output: the paper, pointing at the student's copy (unchanged if it has no PDF, or it is gone).
     * Used by: {@link #addAll(Collection)}, {@link #setStatus}, {@link #move}.
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
        return paper.withCandidatePdf(Optional.of(target));
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
     * Used by: addAll, clear, remove, removeUnmatched, setStatus, move.
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
