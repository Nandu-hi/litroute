package com.litroute.testing;

import static org.junit.Assume.assumeTrue;

import com.litroute.model.Profile;
import com.litroute.profile.ProfileBuilder;
import com.litroute.profile.ProfileBuilder.BuildReport;
import com.litroute.store.ProfileStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Real students' profile folders, kept outside the repository: {@code -Dlitroute.testProfiles=...} if
 * given, else {@code ~/.litroute/test-profiles/} if it exists, else the live profiles folder
 * {@code ~/.litroute/profiles/}. One subfolder per student holding their PDFs and optionally an
 * email.txt. Tests that need them are skipped (not failed) on a machine without them.
 *
 * The folders are only ever read. Building every profile from scratch is slow (several model passes
 * per PDF, minutes for a few hundred papers), so the profiles are synced once per test JVM into
 * {@code target/test-profile-cache/}, which starts as a copy of the live profile store
 * ({@code ~/.litroute/data/profiles.json}) when there is one: only PDFs that are new or changed since
 * are embedded. Tests that change folders get a {@link Workspace}: a private tree of links to the real
 * files plus a copy of the built store, so nothing is copied until a test modifies a file.
 */
public final class TestProfiles {

    private static final Path CACHE_STORE = Path.of("target", "test-profile-cache", "profiles.json");

    /** A private profiles tree (links to the real PDFs) plus its own store. */
    public record Workspace(Path root, ProfileStore store) {

        public ProfileBuilder builder() {
            return new ProfileBuilder(root, store, TestModel.provider());
        }

        public Path folder(String profileName) {
            return root.resolve(profileName);
        }

        /** Replaces the link at this path with a real copy of the file, so the test can change it. */
        public Path writableCopy(Path file) throws IOException {
            if (Files.isSymbolicLink(file)) {
                Path target = Files.readSymbolicLink(file);
                Files.delete(file);
                Files.copy(target, file);
            }
            return file;
        }
    }

    private static boolean built;
    private static BuildReport firstBuild;

    private TestProfiles() {
    }

    public static Path source() {
        String override = System.getProperty("litroute.testProfiles");
        if (override != null) {
            return Path.of(override);
        }
        Path dedicated = Path.of(System.getProperty("user.home"), ".litroute", "test-profiles");
        return Files.isDirectory(dedicated) ? dedicated : ProfileBuilder.defaultProfilesRoot();
    }

    /** Skips the calling test unless the test profile folders exist and hold at least one PDF. */
    public static void assumeAvailable() {
        Path source = source();
        assumeTrue("test profile folders not found at " + source, Files.isDirectory(source) && !allPdfs(source).isEmpty());
    }

    /** The report of building every test profile (embeddings cached from earlier runs count as reused). */
    public static synchronized BuildReport firstBuild() {
        build();
        return firstBuild;
    }

    /** Profiles built from the test folders (built on first use), by name. */
    public static Map<String, Profile> profiles() {
        build();
        try {
            return new ProfileStore(CACHE_STORE).loadAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A private tree linking to every test profile folder, with a store already holding every embedding. */
    public static Workspace freshWorkspace() {
        build();
        try {
            Path dir = Files.createTempDirectory("litroute-profiles");
            Path storeFile = dir.resolve("profiles.json");
            Files.copy(CACHE_STORE, storeFile);
            Path root = dir.resolve("profiles");
            linkTree(source(), root);
            return new Workspace(root, new ProfileStore(storeFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An empty profiles tree with an empty store. */
    public static Workspace emptyWorkspace() {
        try {
            Path dir = Files.createTempDirectory("litroute-profiles");
            Path root = Files.createDirectories(dir.resolve("profiles"));
            return new Workspace(root, new ProfileStore(dir.resolve("profiles.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every PDF under the test profile folders. */
    public static List<Path> allPdfs() {
        assumeAvailable();
        return allPdfs(source());
    }

    /** The profile subfolders of a profiles root, as ProfileBuilder sees them. */
    public static List<Path> folders(Path root) {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().startsWith("."))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Path> allPdfs(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .filter(f -> !f.getFileName().toString().startsWith("."))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Makes {@code link} a symbolic link to {@code target}, or a copy where links are not supported. */
    public static void link(Path target, Path link) throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(target, link, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Builds straight from the (read-only) test folders into the cache store, once per JVM. */
    private static synchronized void build() {
        assumeAvailable();
        if (!built) {
            try {
                Files.createDirectories(CACHE_STORE.getParent());
                Path liveStore = Path.of(System.getProperty("user.home"), ".litroute", "data", "profiles.json");
                if (!Files.exists(CACHE_STORE) && Files.isRegularFile(liveStore)) {
                    // Only a head start: the sync still checks every PDF's hash and the model before reusing a vector.
                    Files.copy(liveStore, CACHE_STORE);
                }
                firstBuild = new ProfileBuilder(source(), new ProfileStore(CACHE_STORE), TestModel.provider()).syncAll();
                built = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void linkTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    link(path, target);
                }
            }
        }
    }
}
