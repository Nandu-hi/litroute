package com.litroute.testing;

import com.litroute.model.RawEmail;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;

/**
 * The six alert emails Prof. Prakash Badal forwarded as reference material, and the only email
 * input the tests use. Each one is a real message: a real publisher's alert, forwarded from
 * aprakashn@gmail.com to cs24b027@smail.iitm.ac.in on 4 September 2026, with the envelope headers
 * below taken from the Gmail print of that message. The bodies here are tidied copies: the forwarder's
 * note and signature, the "Forwarded message" header block and the publisher's text are real, but
 * section labels are headings, links are direct DOI links where the real ones are tracking links, and
 * Gmail's own address links are gone. The HTML exactly as it reached the pipeline's inbox is in
 * {@code samples/received/} (see {@code ReceivedAlertsTest}); the recorded HTTP traffic was captured
 * against these tidied copies.
 *
 * Between them they cover every shape the pipeline has to handle: a readable DOI link
 * (SpringerOpen relevant), opaque tracking tokens only (SpringerOpen irrelevant), a redirect
 * wrapper carrying the real URL (Google Scholar), a mix of decodable and encrypted wrappers
 * (ScienceDirect), and a 22-paper table of contents (ASCE).
 */
public final class SampleMails {

    /**
     * @param file        the fixture under src/test/resources/samples/
     * @param subject     the forward's own subject, as Gmail shows it
     * @param quotedFrom  the publisher that sent the original message, quoted in the body
     * @param papers      how many papers the mail lists
     */
    public record Sample(String file, String subject, String quotedFrom, int papers, Instant receivedAt) {

        /** The message as M1 hands it to the rest of the pipeline. */
        public RawEmail email() {
            String html = read(file);
            return new RawEmail("<f-" + Math.abs(file.hashCode()) + "@mail.gmail.com>", subject, FORWARDER,
                    receivedAt, Optional.of(html), Optional.of(plainText(html)));
        }

        /** What Gmail's text/plain alternative carries: the same message without the markup. */
        public String plainTextBody() {
            return plainText(read(file));
        }
    }

    /** Every one of these mails reached the inbox from Prof. Badal's address, not the publisher's. */
    public static final String FORWARDER = "aprakashn@gmail.com";

    private static final Instant SEP_4 = Instant.parse("2026-09-04T00:00:00Z");

    public static final Sample SPRINGER_RELEVANT = new Sample("springeropen_relevant.html",
            "Fwd: New Articles From Journal of Infrastructure Preservation and Resilience",
            "no-reply@springeropen.com", 1, SEP_4.plusSeconds(7 * 3600 + 28 * 60));

    public static final Sample SPRINGER_IRRELEVANT = new Sample("springeropen_irrelevant.html",
            "Fwd: New Articles From Journal of Infrastructure Preservation and Resilience",
            "no-reply@springeropen.com", 1, SEP_4.plusSeconds(7 * 3600 + 26 * 60));

    public static final Sample TANDFONLINE = new Sample("tandfonline_alert.html",
            "Fwd: New articles for Sustainable and Resilient Infrastructure are now available online",
            "alerts@tandfonline.com", 1, SEP_4.plusSeconds(7 * 3600 + 23 * 60));

    public static final Sample GOOGLE_SCHOLAR = new Sample("google_scholar_alert.html",
            "Fwd: Recommended articles",
            "scholaralerts-noreply@google.com", 1, SEP_4.plusSeconds(7 * 3600 + 16 * 60));

    public static final Sample SCIENCEDIRECT = new Sample("sciencedirect_recommendations.html",
            "Fwd: New recommendations available on ScienceDirect",
            "sciencedirect@notification.elsevier.com", 5, SEP_4.plusSeconds(7 * 3600 + 15 * 60));

    public static final Sample ASCE = new Sample("asce_natural_hazards_review.html",
            "Fwd: New articles have been published Natural Hazards Review, Volume 27, Issue 4",
            "ascelibrary@asce.org", 22, SEP_4.plusSeconds(7 * 3600 + 14 * 60));

    public static final List<Sample> ALL = List.of(SPRINGER_RELEVANT, SPRINGER_IRRELEVANT, TANDFONLINE,
            GOOGLE_SCHOLAR, SCIENCEDIRECT, ASCE);

    private SampleMails() {
    }

    private static String read(String file) {
        try (InputStream in = SampleMails.class.getResourceAsStream("/samples/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing sample mail: " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Gmail sends both alternatives; this is the text one, keeping the line structure headers need. */
    private static String plainText(String html) {
        return Jsoup.parse(html).wholeText();
    }
}
