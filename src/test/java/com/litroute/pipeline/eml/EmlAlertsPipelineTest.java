package com.litroute.pipeline.eml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.litroute.ingestion.email.AlertClassifier;
import com.litroute.ingestion.email.EmailSource;
import com.litroute.ingestion.enrich.CachingPaperEnricher;
import com.litroute.ingestion.enrich.OnlinePaperEnricher;
import com.litroute.ingestion.extract.AlertBatchProcessor;
import com.litroute.ingestion.extract.PaperExtractor;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.pipeline.PipelineRunner;
import com.litroute.pipeline.PipelineRunner.Report;
import com.litroute.scoring.RelevanceScorer;
import com.litroute.security.GmailCredentials;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.PriorityAuthorStore;
import com.litroute.store.ScoredQueueStore;
import com.litroute.store.SentPaperStore;
import com.litroute.testing.TestModel;
import com.litroute.testing.TestProfiles;
import com.litroute.testing.TestProfiles.Workspace;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * M9 end to end on a folder of real alert emails saved as .eml files: each file is parsed as M1 would
 * read it from Gmail (same MIME walk, same {@link AlertClassifier}), then the real {@link PipelineRunner}
 * extracts the papers by pattern, enriches them from the live services (Crossref, OpenAlex, publisher
 * pages, open-access PDFs), deduplicates them and matches them with the real SPECTER2 hybrid against the
 * students' real profile folders and the professor's authors list. Every store is a fresh temporary one,
 * so the live data in {@code ~/.litroute/data} is only read (the authors list), never written.
 *
 * <p>The report, which papers went to which student and which matched no one, is written to
 * {@code target/eml-alerts-match-report.txt} (or {@code -Dlitroute.emlReport=...}).
 *
 * <p>It needs the network and takes minutes, so it runs only when asked:
 * {@code mvn test -Dtest=EmlAlertsPipelineTest -Dlitroute.emlAlerts=$HOME/.claude/original_email_alerts}
 */
public class EmlAlertsPipelineTest {

    private static final GmailCredentials CREDENTIALS = new GmailCredentials("student@example.edu",
            "not-used-offline".toCharArray());

    /** The .eml files as an inbox of unread mail; what M1's classifier turns away is kept for the report. */
    private static final class EmlInbox implements EmailSource {

        final List<RawEmail> alerts = new ArrayList<>();
        final Map<String, String> fileByMessageId = new TreeMap<>();
        final List<String> notAlerts = new ArrayList<>();

        EmlInbox(Path folder) throws Exception {
            AlertClassifier classifier = new AlertClassifier();
            Session session = Session.getInstance(new Properties());
            for (Path file : emlFiles(folder)) {
                MimeMessage message;
                try (InputStream in = Files.newInputStream(file)) {
                    message = new MimeMessage(session, in);
                }
                String messageId = Optional.ofNullable(message.getMessageID())
                        .orElse("<eml-" + Math.abs(file.getFileName().toString().hashCode()) + "@local>");
                String from = message.getFrom() != null && message.getFrom().length > 0
                        && message.getFrom()[0] instanceof InternetAddress address ? address.getAddress() : null;
                String subject = message.getSubject();
                Optional<String>[] bodies = bodies(message);
                if (!classifier.isPublicationAlert(from, subject, bodies[1])) {
                    notAlerts.add(file.getFileName() + " (from " + from + ", subject \"" + subject + "\")");
                    continue;
                }
                Instant received = message.getSentDate() != null ? message.getSentDate().toInstant() : Instant.now();
                alerts.add(new RawEmail(messageId, subject, from, received, bodies[0], bodies[1]));
                fileByMessageId.put(messageId, file.getFileName().toString());
            }
        }

        @Override
        public List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> processed) {
            return alerts.stream().filter(email -> !processed.contains(email.messageId())).toList();
        }

        @Override
        public int markRead(GmailCredentials credentials, Collection<String> messageIds) {
            return 0;
        }

        @Override
        public int markUnread(GmailCredentials credentials, Collection<String> messageIds) {
            return 0;
        }
    }

    @Test
    public void everyEmlAlertPaperIsMatchedToAtMostOneProfileAndReported() throws Exception {
        String folderProperty = System.getProperty("litroute.emlAlerts");
        assumeTrue("set -Dlitroute.emlAlerts=<folder of .eml files> to run", folderProperty != null);
        Path folder = Path.of(folderProperty);
        assumeTrue("no .eml files in " + folder, Files.isDirectory(folder) && !emlFiles(folder).isEmpty());
        TestProfiles.assumeAvailable();

        EmlInbox inbox = new EmlInbox(folder);
        Workspace profiles = TestProfiles.freshWorkspace();
        Path data = Files.createTempDirectory("litroute-eml-pipeline");
        ScoredQueueStore queue = new ScoredQueueStore(data.resolve("scored_queue.json"), data.resolve("matched"));
        EmailCacheStore processed = new EmailCacheStore(data.resolve("email_cache.json"));
        OnlinePaperEnricher online = OnlinePaperEnricher.create(data.resolve("pdfs"));
        AlertBatchProcessor batches = new AlertBatchProcessor(
                lookups -> new PaperExtractor(new CachingPaperEnricher(online, lookups)),
                new EnrichmentCacheStore(data.resolve("enrichment_cache.json")), processed);
        var authors = PriorityAuthorStore.atDefaultLocation().load();
        PipelineRunner runner = new PipelineRunner(profiles.builder(), profiles.store(), inbox, processed, batches,
                students -> new RelevanceScorer(TestModel.provider(), students, authors), queue,
                new SentPaperStore(data.resolve("sent_papers.json")), false);

        List<String> progress = new ArrayList<>();
        Report report = runner.run(CREDENTIALS, line -> {
            progress.add(line);
            System.out.println("[pipeline] " + line);
        });

        List<ScoredPaper> queued = queue.load();
        List<ScoredPaper> matched = queued.stream().filter(p -> p.status() != ReviewStatus.UNMATCHED).toList();
        List<ScoredPaper> unmatched = queued.stream().filter(p -> p.status() == ReviewStatus.UNMATCHED).toList();

        assertEquals(inbox.alerts.size(), report.newAlerts());
        assertEquals(report.papersHeld(), report.papersScored());
        assertEquals("every paper queued once", report.papersHeld(), queued.size());
        assertEquals(queued.size(), queued.stream().map(p -> ScoredPaper.paperKey(p.candidate())).distinct().count());
        assertEquals(report.matches(), matched.size());
        assertEquals(report.unmatched(), unmatched.size());
        assertTrue(matched.stream().allMatch(p -> p.relevance().orElse(0.0) > 0 && p.candidate().isResearch()));

        Path out = Path.of(System.getProperty("litroute.emlReport", "target/eml-alerts-match-report.txt"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, render(folder, inbox, report, profiles, matched, unmatched, progress),
                StandardCharsets.UTF_8);
        System.out.println("[pipeline] report written to " + out.toAbsolutePath());
    }

    private static String render(Path folder, EmlInbox inbox, Report report, Workspace profiles,
            List<ScoredPaper> matched, List<ScoredPaper> unmatched, List<String> progress) throws IOException {
        List<String> students = TestProfiles.folders(profiles.root()).stream()
                .map(dir -> dir.getFileName().toString()).toList();
        Comparator<ScoredPaper> byRelevance = Comparator.comparingDouble((ScoredPaper p) -> p.relevance().orElse(0.0))
                .reversed();
        String rule = "=".repeat(100) + "\n";
        String thin = "-".repeat(100) + "\n";
        StringBuilder text = new StringBuilder();
        text.append("LitRoute - papers from ").append(inbox.alerts.size()).append(" alert emails matched against ")
                .append(students.size()).append(" student profiles\n");
        text.append("Run: ").append(Instant.now()).append(", EmlAlertsPipelineTest (real pipeline, live enrichment, "
                + "SPECTER2 + lexical hybrid; " + (com.litroute.profile.PaperEmbedder.SEMANTIC_ABSTRACT_ONLY
                        ? "SPECTER2 on title + abstract only, TF-IDF on the full text" : "SPECTER2 on title, abstract, headings and body")
                + ")\n");
        text.append("Emails: ").append(folder).append("\n\n");
        text.append("HOW TO READ THE SCORES\n");
        text.append("  Relevance : 0-1, how close the paper is to that student's own papers (a student's own papers are\n");
        text.append("              typically ~0.9, another student's ~0.1). A paper is matched only if relevance >= ")
                .append(String.format(Locale.ROOT, "%.2f%n", RelevanceScorer.RELEVANCE_FLOOR));
        text.append(String.format(Locale.ROOT, "              (>= %.2f when only the title is known) and it is a "
                + "research paper.%n", RelevanceScorer.TITLE_FLOOR));
        text.append("  Score     : relevance x that student's share of the paper's match across all ")
                .append(students.size()).append(" profiles.\n");
        text.append("  All fits  : every profile as \"relevance/score\", highest relevance first.\n");
        text.append("  Each paper goes to at most one profile: the best one.\n\n");

        text.append("SUMMARY\n");
        text.append("  .eml files read             : ").append(inbox.alerts.size() + inbox.notAlerts.size()).append('\n');
        text.append("  Papers found (after dedup)  : ").append(report.papersHeld()).append('\n');
        text.append("  Matched to a profile        : ").append(report.matches()).append('\n');
        text.append("  Not matched to any profile  : ").append(report.unmatched()).append("\n\n");
        for (String student : students) {
            text.append(String.format(Locale.ROOT, "  %-22s %3d paper(s)%n", student,
                    matched.stream().filter(p -> p.profileName().equals(student)).count()));
        }
        if (!inbox.notAlerts.isEmpty()) {
            text.append("\n  Not recognised as alerts (skipped, as M1 would):\n");
            inbox.notAlerts.forEach(line -> text.append("    - ").append(line).append('\n'));
        }

        text.append('\n').append(rule).append("MATCHED PAPERS, BY PROFILE\n").append(rule);
        for (String student : students) {
            List<ScoredPaper> mine = matched.stream().filter(p -> p.profileName().equals(student))
                    .sorted(byRelevance).toList();
            text.append('\n').append(thin).append(student).append("  (").append(mine.size()).append(" paper(s))\n")
                    .append(thin);
            if (mine.isEmpty()) {
                text.append("  (none)\n");
            }
            int i = 1;
            for (ScoredPaper paper : mine) {
                text.append(paperBlock(i++, paper, inbox, false));
            }
        }

        text.append("\n\n").append(rule).append("NOT MATCHED TO ANY PROFILE  (").append(unmatched.size())
                .append(" paper(s), highest relevance first)\n").append(rule);
        int i = 1;
        for (ScoredPaper paper : unmatched.stream().sorted(byRelevance).toList()) {
            text.append(paperBlock(i++, paper, inbox, true));
        }

        text.append("\n\n").append(rule).append("PIPELINE LOG\n").append(rule);
        progress.forEach(line -> text.append(line).append('\n'));
        return text.toString();
    }

    private static String paperBlock(int index, ScoredPaper paper, EmlInbox inbox, boolean unmatched) {
        PaperCandidate c = paper.candidate();
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%n  %d. %s%n", index, c.title()));
        b.append("     DOI       : ").append(c.doi().map(doi -> "https://doi.org/" + doi)
                .orElseGet(() -> c.url().orElse("-") + "   (no DOI found; paper link)")).append('\n');
        Matcher basis = SCORED_FROM.matcher(paper.rationale());
        b.append(String.format(Locale.ROOT, "     Relevance : %.2f   Score : %.2f   (scored from %s)%s%n",
                paper.relevance().orElse(0.0), paper.score(), basis.find() ? basis.group(1).trim() : "?",
                paper.recommended() ? "   [recommended]" : ""));
        paper.listedAuthor().ifPresent(author -> b.append("     Listed    : ").append(author).append('\n'));
        if (unmatched) {
            b.append("     Closest   : ").append(paper.profileName()).append('\n');
        }
        List<String> fits = paper.fits().stream()
                .sorted(Comparator.comparingDouble(ScoredPaper.Fit::relevance).reversed())
                .map(f -> String.format(Locale.ROOT, "%s %.2f/%.2f", f.profileName(), f.relevance(), f.score()))
                .toList();
        int half = Math.min(4, fits.size());
        b.append("     All fits  : ").append(String.join(", ", fits.subList(0, half))).append('\n');
        if (fits.size() > half) {
            b.append("                 ").append(String.join(", ", fits.subList(half, fits.size()))).append('\n');
        }
        b.append("     Email     : ").append(inbox.fileByMessageId.getOrDefault(c.sourceMessageId(), c.sourceMessageId())
                .replaceFirst("\\.eml$", "")).append('\n');
        return b.toString();
    }

    /** What a paper was scored from, as M6's rationale states it: "full PDF", "title and abstract", ... */
    private static final Pattern SCORED_FROM = Pattern.compile("Scored from (?:its )?([^.(]*)");

    private static List<Path> emlFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".eml")).sorted().toList();
        }
    }

    /** The first text/html and first text/plain body, walking multiparts, as GmailEmailSource does. */
    @SuppressWarnings("unchecked")
    private static Optional<String>[] bodies(Part part) throws Exception {
        Optional<String>[] found = new Optional[] {Optional.empty(), Optional.empty()};
        walk(part, found);
        return found;
    }

    private static void walk(Part part, Optional<String>[] found) throws Exception {
        if (part.isMimeType("text/html") && part.getContent() instanceof String html) {
            found[0] = found[0].or(() -> Optional.of(html));
        } else if (part.isMimeType("text/plain") && part.getContent() instanceof String plain) {
            found[1] = found[1].or(() -> Optional.of(plain));
        } else if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                walk(child, found);
            }
        }
    }
}
