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
     * Creates a downloader that fetches through {@code fetcher} and saves into {@code directory}. The directory
     * doesn't have to exist yet.
     * Input: fetcher - the HTTP client used for every download; directory - the folder the PDFs are saved in
     * Output: a downloader saving into that folder.
     * Used by: OnlinePaperEnricher.create (M3).
     * Example:
     * <pre>{@code
     * Input:  new PdfDownloader(new JdkHttpFetcher("LitRoute/1.0 ..."), PdfDownloader.defaultDirectory())
     * Output: a downloader saving real downloads under ~/.litroute/data/pdfs
     * }</pre>
     */
    public PdfDownloader(HttpFetcher fetcher, Path directory) {
        this.fetcher = fetcher;
        this.directory = directory;
    }

    /**
     * The folder PDFs are saved in by default: {@code ~/.litroute/data/pdfs}.
     * Input: none.
     * Output: the default PDF folder under the user's home directory
     * Used by: PipelineRunner.atDefaultLocations (M9).
     * Example:
     * <pre>{@code
     * Input:  (user.home = "/home/u")
     * Output: /home/u/.litroute/data/pdfs
     * }</pre>
     */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".litroute", "data", "pdfs");
    }

    /**
     * Saves the paper's PDF as {@code <directory>/<fileStem>.pdf}. If a real PDF is already there it is returned
     * with no network access. Otherwise each http(s) URL is tried in order, and the first reply that is a 2xx,
     * not cut off at the 80 MB cap, and a real PDF (see {@link #isPdf}) is written atomically and returned.
     * Non-http URLs, error replies, HTML pages and network failures are skipped, never thrown.
     * Input: candidateUrls - possible PDF locations, best first; fileStem - the file name without extension, from {@link #fileStem}
     * Output: the saved (or already present) PDF, or empty if none of the URLs served a real PDF
     * Used by: OnlinePaperEnricher.enrich (M3).
     * Example:
     * <pre>{@code
     * Input:  download(List.of("https://ascelibrary.org/doi/pdf/10.1061/X",          // 403 -> skipped
     *                          "ftp://example.org/paper.pdf",                        // not http -> skipped
     *                          "https://arxiv.org/pdf/2403.01234"),                  // 200, %PDF-1.5 -> saved
     *                  "10.1061_X")
     * Output: Optional[<directory>/10.1061_X.pdf]
     *
     * Input:  same call again
     * Output: Optional[<directory>/10.1061_X.pdf]   (already on disk; nothing fetched)
     *
     * Input:  download(List.of("https://www.sciencedirect.com/.../pdf"), "10.1016_j.oceaneng.2024.117")   // HTML paywall
     * Output: Optional.empty
     * }</pre>
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
     * File name (without extension) for a paper: its DOI made filesystem-safe, else a short hash of its title.
     * In a DOI every character other than letters, digits, {@code .}, {@code _} and {@code -} becomes {@code _},
     * leading dots become one {@code _} (no hidden files), and the result is cut to 120 characters. Without a DOI,
     * the title is stripped of accents, case, spaces and punctuation before hashing, so small differences in how
     * two emails write the same title give the same file.
     * Input: doi - the paper's DOI, if known; title - the paper's title, used only when there is no DOI
     * Output: a file name stem safe on any filesystem
     * Used by: OnlinePaperEnricher.enrich (M3), for the download's file name.
     * Example:
     * <pre>{@code
     * Input:  Optional[10.1016/j.oceaneng.2024.117], any title   Output: "10.1016_j.oceaneng.2024.117"
     * Input:  Optional[10.1002/(SICI)1097-0258]                  Output: "10.1002__SICI_1097-0258"
     * Input:  Optional.empty, "Wave Loads: A Review"             Output: "title-b4e875f17d8ff795"   (SHA-256 of "waveloadsareview")
     * Input:  Optional.empty, "wave loads - a review"            Output: the same stem as the line above
     * }</pre>
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
