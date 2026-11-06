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
 * the response is a real PDF (has the "%PDF-" header in its first 1 KB): a paywall or login page that answers 200 with
 * HTML is rejected and the next URL is tried, so a "PDF" file on disk is always a PDF.
 *
 * Files are named after the DOI (or, without one, a hash of the title), so running the pipeline
 * again reuses the file already on disk instead of downloading it a second time. Only URLs a
 * publisher or aggregator serves publicly are fetched - no login, no workaround for a paywall.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Input:  new PdfDownloader(fetcher, Path.of("/home/me/.litroute/data/pdfs"))
 *             .download(List.of("https://www.sciencedirect.com/.../pdf",       // 200, HTML login page -> skipped
 *                               "https://www.mdpi.com/2077-1312/12/3/400/pdf"), // 200, %PDF-1.7 -> saved
 *                       "10.3390_jmse12030400")
 * Output: Optional[/home/me/.litroute/data/pdfs/10.3390_jmse12030400.pdf]
 * }</pre>
 */
public final class PdfDownloader {

    private static final Logger LOG = Logger.getLogger(PdfDownloader.class.getName());
    private static final long MAX_PDF_BYTES = 80L * 1024 * 1024;

    private final HttpFetcher fetcher;
    private final Path directory;

    /**
     * The directory doesn't have to exist yet.
     *
     * @param fetcher   the HTTP client used for every download
     * @param directory the folder the PDFs are saved in
     */
    public PdfDownloader(HttpFetcher fetcher, Path directory) {
        this.fetcher = fetcher;
        this.directory = directory;
    }

    /** {@return the default PDF folder, {@code ~/.litroute/data/pdfs}} */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".litroute", "data", "pdfs");
    }

    /**
     * Saves the paper's PDF as {@code <directory>/<fileStem>.pdf}. A real PDF already there is returned with no
     * network access. Otherwise each http(s) URL is tried in order; the first 2xx reply that is not cut off at
     * the 80 MB cap and is a real PDF ({@link #isPdf}) is written atomically. Other URLs, error replies, HTML
     * pages and network failures are skipped, never thrown.
     *
     * @param candidateUrls possible PDF locations, best first
     * @param fileStem      the file name without extension, from {@link #fileStem}
     * @return the saved (or already present) PDF, or empty if no URL served a real PDF
     */
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

    /**
     * {@return a filesystem-safe file name (without extension) for the paper}: its DOI with every character
     * other than letters, digits, {@code .}, {@code _} and {@code -} as {@code _} (at most 120 characters), else
     * {@code title-} plus a short hash of the compacted title, so two spellings of one title share a file.
     * Example: 10.1002/(SICI)1097-0258 -> "10.1002__SICI_1097-0258"; no DOI, "Wave Loads: A Review" -> "title-b4e875f17d8ff795"
     *
     * @param doi   the paper's DOI, if known
     * @param title the paper's title, used only when there is no DOI
     */
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

    /**
     * Tells whether a body is a PDF by looking for the {@code %PDF-} header in its first 1 KB (the PDF spec allows
     * some bytes before it). The content type isn't trusted, since servers often get it wrong.
     * Input: body - the downloaded bytes
     * Output: true if the {@code %PDF-} header appears in the first 1024 bytes
     * Used by: {@link #download}, {@link #isPdfFile}; ArticlePageReader.read.
     * Example:
     * <pre>{@code
     * Input:  bytes of "%PDF-1.7\n%..."                       Output: true
     * Input:  bytes of "\r\n%PDF-1.4 ..."                     Output: true    (header after a few junk bytes)
     * Input:  bytes of "<!DOCTYPE html><title>Sign in</title>"  Output: false
     * Input:  empty array                                     Output: false
     * }</pre>
     */
    static boolean isPdf(byte[] body) {
        int limit = Math.min(body.length, 1024);
        return new String(body, 0, limit, StandardCharsets.ISO_8859_1).contains("%PDF-");
    }

    /**
     * Tells whether a file on disk is a real PDF, using the same header check as {@link #isPdf}. A missing file,
     * a directory, or a file that can't be read gives false, so the PDF is downloaded again.
     * Input: file - the path to check
     * Output: true if it is a regular file whose first 1 KB holds the PDF header
     * Used by: {@link #download}, to reuse a PDF already on disk.
     * Example:
     * <pre>{@code
     * Input:  pdfs/10.3390_jmse12030400.pdf, starting "%PDF-1.7"     Output: true
     * Input:  pdfs/10.1016_j.oceaneng.2024.117.pdf, not there         Output: false
     * Input:  pdfs/broken.pdf, containing an HTML page                Output: false
     * }</pre>
     */
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
