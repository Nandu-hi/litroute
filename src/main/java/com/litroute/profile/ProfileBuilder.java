package com.litroute.profile;

import com.litroute.embedding.EmbeddingProvider;
import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.store.ProfileStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * M5: keeps the saved profiles (M7d) in step with the profiles folder. Each subfolder of the profiles
 * root is one student's profile, named after the folder:
 *
 * <pre>
 * ~/.litroute/profiles/
 *     alice/
 *         email.txt          (optional: the addresses M10 sends approved papers to, one per line)
 *         some-paper.pdf
 *         more/another.pdf   (subfolders are read too)
 * </pre>
 *
 * Adding a folder adds a profile, adding or replacing a PDF updates it, and deleting the folder
 * removes the profile at the next {@link #syncAll()}. PDFs are recognized by content hash, so a run
 * only reads and embeds files it has not seen before (with the same model); everything else is
 * reused from the store. Each paper's text and author keywords are stored with its vectors, for M6's
 * word matching, and the profile keeps the student's keywords ({@link ProfileKeywords}), for M6's
 * language model; a store saved before either was kept gets them added by reading the PDFs again,
 * without re-embedding. A PDF
 * that cannot be read is logged, reported and skipped - it never stops the rest of the profile, or
 * other profiles, from being built.
 *
 * A profile is taken to be uniform: one body of work. It is just its papers; M6 compares a candidate
 * with all of them together and with the closest few.
 */
public final class ProfileBuilder {

    private static final Logger LOG = Logger.getLogger(ProfileBuilder.class.getName());

    public static final String EMAIL_FILE = "email.txt";
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern EMAIL_SEPARATORS = Pattern.compile("[,;\\s]+");

    /** A PDF left out of a profile, and why. */
    public record SkippedPdf(String relativePath, String reason) {
    }

    /**
     * What one sync did to one profile folder.
     *
     * @param textAdded reused papers whose PDF was read again because the store predates keeping their
     *                  text or keywords (their vectors were kept, nothing was re-embedded)
     * @param stored    false if the folder had no usable PDF, so no profile exists for it
     */
    public record ProfileReport(
            String name, int embedded, int reused, int textAdded, List<SkippedPdf> skipped, List<String> emails,
            boolean stored) {
    }

    /** @param removed profiles deleted from the store because their folder no longer exists */
    public record BuildReport(List<ProfileReport> profiles, List<String> removed) {
    }

    private final Path profilesRoot;
    private final ProfileStore store;
    private final EmbeddingProvider provider;
    private final PdfTextExtractor extractor = new PdfTextExtractor();
    private final PaperEmbedder embedder;

    /**
     * Input: the folder holding one subfolder per student, the M7d store and the embedding model.
     * Output: a builder (nothing is read yet).
     * Used by: PipelineRunner.atDefaultLocations (M9), ProfilesPane (M8), ProfileBuilderCli.
     * Example: {@code new ProfileBuilder(defaultProfilesRoot(), ProfileStore.atDefaultLocation(), specter2)}
     */
    public ProfileBuilder(Path profilesRoot, ProfileStore store, EmbeddingProvider provider) {
        this.profilesRoot = profilesRoot;
        this.store = store;
        this.provider = provider;
        this.embedder = new PaperEmbedder(provider);
    }

    /**
     * Input: none.
     * Output: {@code ~/.litroute/profiles}.
     * Used by: PipelineRunner (M9), ProfilesPane (M8), ProfileBuilderCli.
     * Example: -> /home/u/.litroute/profiles
     */
    public static Path defaultProfilesRoot() {
        return Path.of(System.getProperty("user.home"), ".litroute", "profiles");
    }

    /**
     * Brings every profile up to date with its folder; creates the profiles root if it is missing.
     * A changed profile is saved as soon as it is rebuilt, so an interrupted run keeps the finished
     * ones; an unchanged one is left untouched, keeping its {@code updatedAt}.
     * Input: none (reads every folder under the profiles root, and M7d).
     * Output: a report per folder, plus the profiles removed because their folder is gone; IOException if the root or store fails.
     * Used by: PipelineRunner.run (M9) step 1, ProfilesPane (M8) Rebuild, ProfileBuilderCli.
     * Example: folders alice/ (2 PDFs, 1 new) and bob/ deleted -> [alice: embedded 1, reused 1], removed [bob]
     */
    public BuildReport syncAll() throws IOException {
        Files.createDirectories(profilesRoot);
        Map<String, Profile> saved = store.loadAll();
        // Every paper already embedded by this model, by hash: reused even if moved between folders.
        Map<String, ProfilePaper> known = new HashMap<>();
        for (Profile profile : saved.values()) {
            if (profile.modelId().equals(provider.modelId())) {
                profile.papers().forEach(paper -> known.putIfAbsent(paper.sha256(), paper));
            }
        }

        List<ProfileReport> reports = new ArrayList<>();
        Set<String> folderNames = new LinkedHashSet<>();
        for (Path folder : profileFolders()) {
            String name = folder.getFileName().toString();
            folderNames.add(name);
            reports.add(sync(folder, name, known, saved.get(name)));
        }

        // A profile whose folder was deleted goes too.
        List<String> removed = new ArrayList<>();
        for (String name : saved.keySet()) {
            if (!folderNames.contains(name)) {
                store.remove(name);
                removed.add(name);
                LOG.log(Level.INFO, "Removed profile {0}: its folder is gone", name);
            }
        }
        return new BuildReport(reports, removed);
    }

    /**
     * Input: one student folder, its name, the papers already embedded (by hash), and its saved profile (or null).
     * Output: what was embedded, reused or skipped; the profile is saved to M7d if anything changed, removed if no PDF is readable.
     * Used by: {@link #syncAll}.
     * Example: alice/ with a.pdf (known hash) and b.pdf (new) -> ProfileReport(alice, embedded 1, reused 1, ...)
     */
    private ProfileReport sync(Path folder, String name, Map<String, ProfilePaper> known, Profile previous)
            throws IOException {
        List<String> emails = readEmails(folder.resolve(EMAIL_FILE));
        List<ProfilePaper> papers = new ArrayList<>();
        List<SkippedPdf> skipped = new ArrayList<>();
        Map<String, String> pathBySha = new HashMap<>();
        int embedded = 0;
        int reused = 0;
        int textAdded = 0;
        List<Path> pdfs = pdfsIn(folder);
        for (int index = 0; index < pdfs.size(); index++) {
            Path pdf = pdfs.get(index);
            String relativePath = folder.relativize(pdf).toString();
            String sha256 = sha256(pdf);
            // The same PDF twice in one folder counts once.
            if (pathBySha.containsKey(sha256)) {
                skipped.add(new SkippedPdf(relativePath, "same file as " + pathBySha.get(sha256)));
                continue;
            }
            ProfilePaper paper = known.get(sha256);
            try {
                if (paper != null && (!paper.hasText() || !paper.hasKeywords())) {
                    // Saved before paper text or keywords were kept: read the words again, keep the vectors
                    // (and any text kept already, which M6 measured its word matching on).
                    PaperText text = extractor.extract(pdf);
                    paper = paper.withText(paper.abstractText().orElse(text.abstractText()),
                            paper.bodyText().orElse(text.bodyText()), text.keywords());
                    known.put(sha256, paper);
                    textAdded++;
                }
                if (paper != null) {
                    reused++;
                    paper = paper.at(relativePath);
                } else {
                    paper = embedder.embed(extractor.extract(pdf), relativePath, sha256);
                    embedded++;
                    known.put(sha256, paper);
                    // Embedding takes seconds per PDF, so a first build of many papers reports its progress.
                    LOG.log(Level.INFO, "{0} {1}/{2}: embedded {3}",
                            new Object[] {name, index + 1, pdfs.size(), relativePath});
                }
            } catch (UnreadablePdfException e) {
                LOG.log(Level.WARNING, "Skipping {0} in profile {1}: {2}", new Object[] {relativePath, name, e.getMessage()});
                skipped.add(new SkippedPdf(relativePath, e.getMessage()));
                continue;
            }
            pathBySha.put(sha256, relativePath);
            papers.add(paper);
        }

        if (papers.isEmpty()) {
            LOG.log(Level.WARNING, "Profile {0} has no readable PDF; nothing saved for it", name);
            if (previous != null) {
                store.remove(name);
            }
            return new ProfileReport(name, 0, 0, 0, skipped, emails, false);
        }
        if (textAdded > 0) {
            LOG.log(Level.INFO, "{0}: kept the text or keywords of {1} papers that lacked them (nothing re-embedded)",
                    new Object[] {name, textAdded});
        }
        List<String> keywords = ProfileKeywords.of(papers);
        if (previous == null || textAdded > 0 || !unchanged(previous, emails, papers, keywords)) {
            store.save(new Profile(name, emails, papers, keywords, provider.modelId(), Instant.now()));
        }
        return new ProfileReport(name, embedded, reused, textAdded, skipped, emails, true);
    }

    /**
     * Whether saving would change nothing but the timestamp: same model, addresses, files and keywords.
     * Input: the saved profile and the freshly built emails, papers and keywords.
     * Output: true if saving would change nothing but the timestamp.
     * Used by: {@link #sync}.
     * Example: same files, emails and keywords -> true; a new email.txt address -> false
     */
    private boolean unchanged(Profile previous, List<String> emails, List<ProfilePaper> papers, List<String> keywords) {
        return previous.modelId().equals(provider.modelId())
                && previous.emails().equals(emails)
                && files(previous.papers()).equals(files(papers))
                && previous.keywords().equals(keywords);
    }

    /**
     * Input: papers.
     * Output: their "path\nsha256" identities, in order.
     * Used by: {@link #unchanged}.
     * Example: [a.pdf hash ab12] -> ["a.pdf\nab12"]
     */
    private static List<String> files(List<ProfilePaper> papers) {
        return papers.stream().map(paper -> paper.relativePath() + "\n" + paper.sha256()).toList();
    }

    /**
     * Input: none.
     * Output: the non-hidden subfolders of the profiles root, sorted.
     * Used by: {@link #syncAll}.
     * Example: profiles/{alice, bob, .git} -> [alice, bob]
     */
    private List<Path> profileFolders() throws IOException {
        try (Stream<Path> entries = Files.list(profilesRoot)) {
            return entries.filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Input: a student folder.
     * Output: every non-hidden .pdf in it (any depth, any case), sorted.
     * Used by: {@link #sync}.
     * Example: alice/{a.pdf, sub/B.PDF, notes.txt} -> [a.pdf, sub/B.PDF]
     */
    private static List<Path> pdfsIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Addresses in email.txt: one or more per line (comma, semicolon or space separated); blank lines
     * and lines starting with '#' are ignored, and anything that is not an address is logged and
     * dropped. Duplicates (ignoring case) are kept once, in file order.
     * Input: the folder's email.txt path.
     * Output: its addresses in file order, duplicates (ignoring case) once; [] if no file.
     * Used by: {@link #sync}; ProfileBuilderTest.
     * Example: "# prof\na@x.edu, A@x.edu; b@y.org\nnot-an-email" -> [a@x.edu, b@y.org]
     */
    static List<String> readEmails(Path emailFile) throws IOException {
        if (!Files.isRegularFile(emailFile)) {
            return List.of();
        }
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        for (String line : Files.readAllLines(emailFile)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            for (String token : EMAIL_SEPARATORS.split(trimmed)) {
                if (EMAIL.matcher(token).matches()) {
                    byLowerCase.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
                } else if (!token.isEmpty()) {
                    LOG.log(Level.WARNING, "Ignoring \"{0}\" in {1}: not an email address", new Object[] {token, emailFile});
                }
            }
        }
        return List.copyOf(byLowerCase.values());
    }

    /**
     * Input: a file.
     * Output: the SHA-256 of its bytes, as lowercase hex.
     * Used by: {@link #sync}, so an unchanged PDF is never embedded twice.
     * Example: empty file -> "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
     */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
