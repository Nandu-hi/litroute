package com.litroute.testing;

import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * New papers filed by student, to check where M6 routes them: {@code ~/.litroute/match-test/} (or
 * {@code -Dlitroute.matchTest=...}), holding {@code <profile name>/<paper>.pdf}. The subfolder a PDF
 * is filed under names the student it should reach. Read only, kept outside the repository like the
 * profile folders; tests that need it are skipped where it does not exist.
 */
public final class MatchTestSet {

    /** One filed PDF and the profile it is filed under. */
    public record Filed(Path pdf, String profileName) {
    }

    private MatchTestSet() {
    }

    public static Path source() {
        String override = System.getProperty("litroute.matchTest");
        return override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".litroute", "match-test");
    }

    /** Every PDF filed one level down, under a profile's name; skips the calling test if there are none. */
    public static List<Filed> filed() {
        Path source = source();
        assumeTrue("match-test folder not found at " + source, Files.isDirectory(source));
        List<Filed> filed;
        try (Stream<Path> files = Files.walk(source)) {
            filed = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .filter(f -> source.relativize(f).getNameCount() > 1)
                    .sorted()
                    .map(f -> new Filed(f, source.relativize(f).getName(0).toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assumeTrue("no PDFs filed under profile folders in " + source, !filed.isEmpty());
        return filed;
    }
}
