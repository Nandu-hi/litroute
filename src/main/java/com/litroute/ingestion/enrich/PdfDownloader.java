package com.litroute.ingestion.enrich;

import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Saves a paper's PDF into a local folder. Each candidate URL is tried in order and accepted only if
 * the response is a real PDF (starts with "%PDF-"): a paywall or login page that answers 200 with
 * HTML is rejected and the next URL is tried, so a "PDF" file on disk is always a PDF.
 *
 * Files are named after the DOI (or, without one, a hash of the title), so running the pipeline
 * again reuses the file already on disk instead of downloading it a second time. Only URLs a
 * publisher or aggregator serves publicly are fetched - no login, no workaround for a paywall.
 */
public final class PdfDownloader {

    private static final Logger LOG = Logger.getLogger(PdfDownloader.class.getName());
    private static final long MAX_PDF_BYTES = 80L * 1024 * 1024;

    private final HttpFetcher fetcher;
    private final Path directory;

    public PdfDownloader(HttpFetcher fetcher, Path directory) {
        this.fetcher = fetcher;
        this.directory = directory;
    }

    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".litroute", "data", "pdfs");
    }

    /** @return the saved (or already present) PDF, or empty if none of the URLs served a real PDF */
    public Optional<Path> download(List<String> candidateUrls, String fileStem) {
        Path target = directory.resolve(fileStem + ".pdf");
        if (isPdfFile(target)) {
            return Optional.of(target);
        }
        for (String url : candidateUrls) {
            try {
                String lower = url.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    continue;
                }
                HttpReply reply = fetcher.get(URI.create(url), MAX_PDF_BYTES);
                if (!reply.ok() || reply.truncated() || !isPdf(reply.body())) {
                    LOG.log(Level.FINE, "Not a downloadable PDF (HTTP {0}): {1}", new Object[] {reply.status(), url});
                    continue;
                }
                AtomicFiles.writeAtomically(target, reply.body());
                return Optional.of(target);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "PDF download failed for " + url + ": " + e);
            }
        }
        return Optional.empty();
    }

    /** File name (without extension) for a paper: its DOI made filesystem-safe, else a short hash of its title. */
    public static String fileStem(Optional<String> doi, String title) {
        if (doi.isPresent()) {
            String safe = doi.get().replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("^\\.+", "_");
            return safe.length() > 120 ? safe.substring(0, 120) : safe;
        }
        String compact = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(compact.getBytes(StandardCharsets.UTF_8));
            return "title-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isPdf(byte[] body) {
        int limit = Math.min(body.length, 1024);
        return new String(body, 0, limit, StandardCharsets.ISO_8859_1).contains("%PDF-");
    }

    private static boolean isPdfFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            byte[] head = new byte[1024];
            try (var in = Files.newInputStream(file)) {
                int read = in.read(head);
                return read > 0 && isPdf(java.util.Arrays.copyOf(head, read));
            }
        } catch (IOException e) {
            return false;
        }
    }
}
