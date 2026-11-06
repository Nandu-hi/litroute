package com.litroute.store;

import com.litroute.model.Profile;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.store.SentPaperStore.Sent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the pipeline keeps in {@code ~/.litroute/data}, one {@link Cache} per thing that can be cleared: how big
 * each is, what it holds, and clearing it through its own store. The student PDFs in {@code profiles/}, the model
 * and the authors list are not caches and are left alone, and so is any other file in the folder.
 *
 * Only the store that owns a cache touches it, so a clear keeps that store's rules (locks, atomic writes, a PDF
 * copy deleted only by the queue or the sent record pointing to it). Sizes are read file by file, so a corrupt
 * store still shows its size, and says it can't be read.
 */
public final class DataCaches {

    /** One thing that can be cleared. The order is the order a clear takes them in: see {@link #clear}. */
    public enum Cache {
        /** M7e: the review queue, its decisions and the students' PDF copies. */
        REVIEW_QUEUE("Review queue"),
        /** The sent-paper record; without it a student can get a paper twice. */
        SENT_PAPERS("Sent papers"),
        /** M7c: the extracted papers and the lookup cache. */
        EXTRACTED_PAPERS("Extracted papers"),
        /** M7a: the processed Message-IDs (Gmail is not touched). */
        PROCESSED_EMAILS("Processed emails"),
        /** {@code pdfs/}: the downloaded PDFs. */
        DOWNLOADED_PDFS("Downloaded PDFs"),
        /** M7d: the built profiles (the student folders stay). */
        PROFILES("Student profiles");

        private final String label;

        /**
         * Input: the name the Caches tab shows.
         * Output: the cache constant.
         * Used by: the enum constants.
         * Example: SENT_PAPERS -> "Sent papers"
         */
        Cache(String label) {
            this.label = label;
        }

        /**
         * Input: none.
         * Output: the name the Caches tab shows.
         * Used by: CachesPane (M8).
         * Example: PROFILES -> "Student profiles"
         */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One cache as it stands.
     *
     * @param cache    which one
     * @param bytes    its size on disk
     * @param contents what it holds, for a person to read
     * @param where    its file or folder, relative to the data folder
     */
    public record Usage(Cache cache, long bytes, String contents, String where) {
    }

    /**
     * Every cache as it stands, and the rest of the data folder.
     *
     * @param caches     one per {@link Cache}, in its order
     * @param otherBytes the other files in the data folder, which no clear touches
     */
    public record Report(List<Usage> caches, long otherBytes) {

        /** {@return the size of all the caches together, in bytes} */
        public long totalBytes() {
            return caches.stream().mapToLong(Usage::bytes).sum();
        }
    }

    private static final String EMAILS_FILE = "email_cache.json";
    private static final String EXTRACTED_FILE = "enrichment_cache.json";
    private static final String PROFILES_FILE = "profiles.json";
    private static final String QUEUE_FILE = "scored_queue.json";
    private static final String SENT_FILE = "sent_papers.json";
    private static final String MATCHED_FOLDER = "matched";
    private static final String PDFS_FOLDER = "pdfs";
    private static final String UNREADABLE = "can't be read: the file is corrupt";

    /** Says what a store holds; reading it may find it corrupt. */
    @FunctionalInterface
    private interface Contents {
        /**
         * Input: none.
         * Output: what the store holds, for a person to read; IOException if it can't be read.
         * Used by: {@link DataCaches#described}.
         * Example: -> "26 paper(s)"
         */
        String read() throws IOException;
    }

    private final Path folder;
    private final EmailCacheStore emails;
    private final EnrichmentCacheStore extracted;
    private final ProfileStore profiles;
    private final ScoredQueueStore queue;
    private final SentPaperStore sent;

    /**
     * Nothing is read until {@link #report} or {@link #clear}.
     *
     * @param folder the data folder
     */
    public DataCaches(Path folder) {
        this.folder = folder.toAbsolutePath().normalize();
        this.emails = new EmailCacheStore(this.folder.resolve(EMAILS_FILE));
        this.extracted = new EnrichmentCacheStore(this.folder.resolve(EXTRACTED_FILE));
        this.profiles = new ProfileStore(this.folder.resolve(PROFILES_FILE));
        this.queue = new ScoredQueueStore(this.folder.resolve(QUEUE_FILE), this.folder.resolve(MATCHED_FOLDER));
        this.sent = new SentPaperStore(this.folder.resolve(SENT_FILE));
    }

    /** {@return the caches in {@code ~/.litroute/data}} */
    public static DataCaches atDefaultLocation() {
        return new DataCaches(Path.of(System.getProperty("user.home"), ".litroute", "data"));
    }

    /** {@return the data folder} */
    public Path folder() {
        return folder;
    }

    /**
     * Measures every cache. A corrupt store is not an error here: it is reported as such.
     * Used by: CachesPane (M8), on opening the tab, on Refresh and after a job.
     *
     * @return each cache's size and contents, and the size of everything else in the folder
     * @throws IOException if the folder can't be walked
     */
    public Report report() throws IOException {
        List<Usage> caches = List.of(queueUsage(), sentUsage(), extractedUsage(), emailsUsage(),
                pdfsUsage(), profilesUsage());
        long all = sizeOf(folder);
        return new Report(caches, Math.max(0, all - caches.stream().mapToLong(Usage::bytes).sum()));
    }

    /**
     * Clears the chosen caches, always in {@link Cache} order: the queue before the sent papers (whose PDF copies
     * it may share), the processed emails after the papers extracted from them, so a crash part-way repeats work
     * rather than losing it. Gmail is not touched.
     * Used by: CachesPane (M8), once the professor has agreed to the consequences.
     *
     * @param chosen the caches to clear
     * @throws IOException at the first cache that fails; those before it stay cleared
     */
    public void clear(Set<Cache> chosen) throws IOException {
        for (Cache cache : Cache.values()) {
            if (chosen.contains(cache)) {
                switch (cache) {
                    case REVIEW_QUEUE -> queue.clear();
                    case SENT_PAPERS -> clearSent();
                    case EXTRACTED_PAPERS -> extracted.save(EnrichmentCacheStore.Contents.empty());
                    case PROCESSED_EMAILS -> emails.save(Set.of());
                    case DOWNLOADED_PDFS -> deleteFilesIn(folder.resolve(PDFS_FOLDER));
                    case PROFILES -> profiles.clear();
                }
            }
        }
    }

    /**
     * Forgets the sent papers and deletes their PDF copies, except one a queue entry still points to. The record goes
     * first: a crash now leaves stray PDFs, never entries pointing to deleted files.
     * Input: none.
     * Output: none; sent_papers.json and the PDF copies only it pointed to are gone.
     * Used by: {@link #clear}.
     * Example: alice/x.pdf sent and alice/y.pdf queued -> x.pdf deleted, y.pdf kept
     */
    private void clearSent() throws IOException {
        List<Sent> papers = sent.load();
        Set<Path> queued = new HashSet<>();
        for (ScoredPaper paper : queue.load()) {
            queue.pdfCopy(paper).ifPresent(queued::add);
        }
        sent.clear();
        for (Sent entry : papers) {
            Optional<Path> copy = queue.pdfCopy(entry.paper());
            if (copy.isPresent() && !queued.contains(copy.get())) {
                queue.deleteCopy(copy.get());
            }
        }
    }

    /**
     * Input: none.
     * Output: the queue's file and PDF copies, with how many papers are waiting at each decision.
     * Used by: {@link #report}.
     * Example: -> Usage(REVIEW_QUEUE, 2500000, "4 papers: 1 pending, 1 approved, 1 dropped, 1 not matched", "scored_queue.json + matched/")
     */
    private Usage queueUsage() throws IOException {
        long bytes = sizeOf(folder.resolve(QUEUE_FILE));
        String contents;
        try {
            List<ScoredPaper> papers = queue.load();
            bytes += copiesSize(papers, Set.of());
            contents = papers.size() + " paper(s): " + count(papers, ReviewStatus.PENDING) + " pending, "
                    + count(papers, ReviewStatus.APPROVED) + " approved, " + count(papers, ReviewStatus.DROPPED) + " dropped, "
                    + count(papers, ReviewStatus.UNMATCHED) + " not matched";
        } catch (IOException | RuntimeException e) {
            contents = UNREADABLE;
        }
        return new Usage(Cache.REVIEW_QUEUE, bytes, contents, QUEUE_FILE + " + " + MATCHED_FOLDER + "/");
    }

    /**
     * Input: none.
     * Output: the sent record's file and the PDF copies only it points to, with how many papers went to how many students.
     * Used by: {@link #report}.
     * Example: -> Usage(SENT_PAPERS, 2500000, "16 paper(s) sent to 5 student(s)", "sent_papers.json + matched/")
     */
    private Usage sentUsage() throws IOException {
        long bytes = sizeOf(folder.resolve(SENT_FILE));
        String contents;
        try {
            List<ScoredPaper> papers = sent.load().stream().map(Sent::paper).toList();
            Set<Path> queued = new HashSet<>();
            try {
                for (ScoredPaper paper : queue.load()) {
                    queue.pdfCopy(paper).ifPresent(queued::add);
                }
            } catch (IOException | RuntimeException unreadableQueue) {
                // reported on the queue's own row
            }
            bytes += copiesSize(papers, queued);
            contents = papers.size() + " paper(s) sent to "
                    + papers.stream().map(ScoredPaper::profileName).distinct().count() + " student(s)";
        } catch (IOException | RuntimeException e) {
            contents = UNREADABLE;
        }
        return new Usage(Cache.SENT_PAPERS, bytes, contents, SENT_FILE + " + " + MATCHED_FOLDER + "/");
    }

    /**
     * Input: none.
     * Output: M7c's file, with its papers and lookups.
     * Used by: {@link #report}.
     * Example: -> Usage(EXTRACTED_PAPERS, 98062, "26 paper(s), 26 lookup(s)", "enrichment_cache.json")
     */
    private Usage extractedUsage() throws IOException {
        return new Usage(Cache.EXTRACTED_PAPERS, sizeOf(folder.resolve(EXTRACTED_FILE)), described(() -> {
            EnrichmentCacheStore.Contents held = extracted.load();
            return held.enriched().size() + " paper(s), " + held.lookups().size() + " lookup(s)";
        }), EXTRACTED_FILE);
    }

    /**
     * Input: none.
     * Output: M7a's file and how many emails it records.
     * Used by: {@link #report}.
     * Example: -> Usage(PROCESSED_EMAILS, 427, "6 email(s)", "email_cache.json")
     */
    private Usage emailsUsage() throws IOException {
        return new Usage(Cache.PROCESSED_EMAILS, sizeOf(folder.resolve(EMAILS_FILE)),
                described(() -> emails.load().size() + " email(s)"), EMAILS_FILE);
    }

    /**
     * Input: none.
     * Output: the downloaded PDFs' folder and how many files it holds.
     * Used by: {@link #report}.
     * Example: -> Usage(DOWNLOADED_PDFS, 11000000, "3 PDF(s)", "pdfs/")
     */
    private Usage pdfsUsage() throws IOException {
        Path pdfs = folder.resolve(PDFS_FOLDER);
        long files = 0;
        if (Files.isDirectory(pdfs)) {
            try (Stream<Path> walk = Files.walk(pdfs)) {
                files = walk.filter(Files::isRegularFile).count();
            }
        }
        return new Usage(Cache.DOWNLOADED_PDFS, sizeOf(pdfs), files + " PDF(s)", PDFS_FOLDER + "/");
    }

    /**
     * Input: none.
     * Output: M7d's file, with its profiles and their papers.
     * Used by: {@link #report}.
     * Example: -> Usage(PROFILES, 14222244, "8 profile(s), 294 paper(s)", "profiles.json")
     */
    private Usage profilesUsage() throws IOException {
        return new Usage(Cache.PROFILES, sizeOf(folder.resolve(PROFILES_FILE)), described(() -> {
            Map<String, Profile> students = profiles.loadAll();
            return students.size() + " profile(s), "
                    + students.values().stream().mapToInt(profile -> profile.papers().size()).sum() + " paper(s)";
        }), PROFILES_FILE);
    }

    /**
     * Input: how to say what a store holds.
     * Output: that, or that the store can't be read.
     * Used by: the Usage methods of the stores with nothing else to measure.
     * Example: a corrupt sent_papers.json -> "can't be read: the file is corrupt"
     */
    private static String described(Contents contents) {
        try {
            return contents.read();
        } catch (IOException | RuntimeException e) {
            return UNREADABLE;
        }
    }

    /**
     * Input: papers and the PDF copies to leave out of the count.
     * Output: the bytes of their distinct PDF copies in the students' folders.
     * Used by: {@link #queueUsage}, {@link #sentUsage}.
     * Example: two papers pointing at one 2 MB copy -> 2097152
     */
    private long copiesSize(List<ScoredPaper> papers, Set<Path> leftOut) throws IOException {
        Set<Path> copies = new HashSet<>();
        for (ScoredPaper paper : papers) {
            queue.pdfCopy(paper).filter(copy -> !leftOut.contains(copy)).ifPresent(copies::add);
        }
        long bytes = 0;
        for (Path copy : copies) {
            bytes += sizeOf(copy);
        }
        return bytes;
    }

    /**
     * Input: papers and a decision.
     * Output: how many of them have it.
     * Used by: {@link #queueUsage}.
     * Example: [pending, approved], PENDING -> 1
     */
    private static long count(List<ScoredPaper> papers, ReviewStatus status) {
        return papers.stream().filter(paper -> paper.status() == status).count();
    }

    /**
     * Input: a file or folder.
     * Output: its bytes, counting everything under a folder; 0 if it doesn't exist.
     * Used by: everything measuring a cache.
     * Example: a folder with files of 10 and 20 bytes -> 30
     */
    private static long sizeOf(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            long bytes = 0;
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                try {
                    bytes += Files.size(file);
                } catch (NoSuchFileException gone) {
                    // a temporary file a store has just renamed
                }
            }
            return bytes;
        }
    }

    /**
     * Input: a folder.
     * Output: none; everything under it is deleted and the folder stays; a missing folder is left missing.
     * Used by: {@link #clear} for the downloaded PDFs.
     * Example: pdfs/ with 3 PDFs -> pdfs/ empty
     */
    private static void deleteFilesIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(path -> !path.equals(directory)).sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
