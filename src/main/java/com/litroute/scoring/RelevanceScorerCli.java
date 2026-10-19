package com.litroute.scoring;

import com.litroute.embedding.Specter2EmbeddingProvider;
import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.ScoredPaper;
import com.litroute.scoring.RelevanceScorer.Strategy;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.ProfileStore;
import com.litroute.store.ScoredQueueStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs M6 until M9 does, in one of two ways:
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.litroute.scoring.RelevanceScorerCli [-Dexec.args=--top=2]
 * mvn -q compile exec:java -Dexec.mainClass=com.litroute.scoring.RelevanceScorerCli -Dexec.args="PDF_OR_FOLDER [--top=2]"
 * </pre>
 *
 * Without a path it matches the deduplicated papers held in M7c against the profiles in M7d, adds
 * the matches to the review queue (M7e) and lists each student's recommended papers ({@code --top},
 * default {@value RelevanceScorer#RECOMMENDED_PER_STUDENT}, per student). With a PDF, or a folder of
 * them, it reports each paper's score for every profile instead and changes nothing; when the
 * folder's subfolders are named after profiles (FOLDER/RP3-mf-sim/x.pdf), each PDF is expected to
 * reach the profile it sits under, and the run compares the strategies on how many did and on the
 * precision of each student's recommendations.
 */
public final class RelevanceScorerCli {

    private RelevanceScorerCli() {
    }

    public static void main(String[] args) throws Exception {
        Path pdfOrFolder = null;
        int top = RelevanceScorer.RECOMMENDED_PER_STUDENT;
        for (String arg : args) {
            if (arg.startsWith("--top=")) {
                top = Integer.parseInt(arg.substring("--top=".length()));
            } else {
                pdfOrFolder = Path.of(arg);
            }
        }
        Map<String, Profile> profiles = ProfileStore.atDefaultLocation().loadAll();
        if (profiles.isEmpty()) {
            System.out.println("No profiles yet: build them first with ProfileBuilderCli.");
            return;
        }
        try (Specter2EmbeddingProvider provider = Specter2EmbeddingProvider.atDefaultLocation()) {
            RelevanceScorer scorer = new RelevanceScorer(provider, profiles.values());
            if (pdfOrFolder != null) {
                scorePdfs(scorer, pdfOrFolder, profiles, top);
            } else {
                matchDeduplicatedPapers(scorer, profiles, top);
            }
        }
    }

    private static void matchDeduplicatedPapers(RelevanceScorer scorer, Map<String, Profile> profiles, int top)
            throws IOException {
        List<PaperCandidate> deduplicated = EnrichmentCacheStore.atDefaultLocation().load().enriched();
        if (deduplicated.isEmpty()) {
            System.out.println("No deduplicated papers in M7c yet: run the alert pipeline first.");
            return;
        }
        Map<String, List<ScoredPaper>> matches = scorer.match(deduplicated, top);
        ScoredQueueStore store = ScoredQueueStore.atDefaultLocation();
        List<ScoredPaper> queue = store.addAll(matches);
        int recommended = 0;
        for (Map.Entry<String, List<ScoredPaper>> entry : matches.entrySet()) {
            String student = entry.getKey();
            List<ScoredPaper> own = entry.getValue();
            long picked = own.stream().filter(ScoredPaper::recommended).count();
            recommended += (int) picked;
            System.out.printf("%n%s: %d recommended of %d matched%n", student, picked, own.size());
            for (ScoredPaper match : own) {
                if (match.recommended()) {
                    System.out.printf(Locale.ROOT, "  * %.2f  %s%n", match.score(), match.candidate().title());
                }
            }
            if (own.size() > picked) {
                System.out.printf("    (%d more queued, not recommended)%n", own.size() - picked);
            }
        }
        System.out.printf("%n%d papers matched, each to one student; %d recommended; the review queue now holds %d.%n"
                + "Matched PDFs are in %s%n", deduplicated.size(), recommended, queue.size(),
                store.pdfRoot());
    }

    /**
     * Prints each PDF's score for every profile, best first, marking the one it is filed under; then,
     * for labelled folders, how each strategy matched the whole folder as one batch.
     */
    private static void scorePdfs(RelevanceScorer scorer, Path pdfOrFolder, Map<String, Profile> profiles, int top)
            throws IOException {
        Path root = pdfOrFolder.toAbsolutePath();
        Path folder = Files.isDirectory(root) ? root : root.getParent();
        List<Path> pdfs;
        try (Stream<Path> files = Files.walk(root)) {
            pdfs = files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().toList();
        }
        // Each PDF takes seconds to read and embed, so they are scored in parallel and reported in order.
        List<PaperCandidate> candidates = pdfs.parallelStream().map(LocalPdfCandidates::of).toList();
        List<List<ScoredPaper>> results = candidates.parallelStream().map(scorer::scoreAll).toList();
        Map<PaperCandidate, String> filedUnder = new LinkedHashMap<>();
        for (int i = 0; i < pdfs.size(); i++) {
            Path relative = folder.relativize(pdfs.get(i));
            String expected = relative.getNameCount() > 1 ? relative.getName(0).toString() : null;
            if (expected != null && profiles.containsKey(expected)) {
                filedUnder.put(candidates.get(i), expected);
            }
            List<ScoredPaper> scored = results.get(i);
            ScoredPaper best = scored.get(0);
            String verdict = expected == null || !profiles.containsKey(expected) ? ""
                    : best.profileName().equals(expected) ? "  ok" : "  MISMATCH (expected " + expected + ")";
            System.out.printf(Locale.ROOT, "%n%s%s%n  %s%n", relative, verdict, best.candidate().title());
            for (ScoredPaper match : scored) {
                System.out.printf(Locale.ROOT, "  %.2f  %-22s%s%n", match.score(), match.profileName(),
                        match.profileName().equals(expected) ? "filed here" : "");
            }
            System.out.println("  " + best.rationale());
        }
        if (filedUnder.isEmpty()) {
            return;
        }
        System.out.printf("%nAll %d filed PDFs matched as one batch, %d recommendations per student:%n", filedUnder.size(),
                top);
        List<PaperCandidate> filed = new ArrayList<>(filedUnder.keySet());
        for (Strategy strategy : Strategy.values()) {
            Map<String, List<ScoredPaper>> matches = scorer.match(filed, top, strategy);
            System.out.printf(Locale.ROOT, "  %-8s %d of %d assigned to the student they are filed under; precision of "
                    + "the students' recommendations %.2f%n", strategy, MatchEvaluation.assignedCorrectly(matches, filedUnder),
                    filed.size(), MatchEvaluation.recommendationPrecision(matches, filedUnder, top));
        }
    }
}
