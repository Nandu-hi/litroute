package com.litroute.scoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.profile.PaperText;
import com.litroute.profile.PdfTextExtractor;
import com.litroute.profile.UnreadablePdfException;
import com.litroute.scoring.RelevanceScorer.Strategy;
import com.litroute.testing.ClientAlerts;
import com.litroute.testing.MatchTestSet;
import com.litroute.testing.MatchTestSet.Filed;
import com.litroute.testing.TestModel;
import com.litroute.testing.TestProfiles;
import com.litroute.util.Titles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M6 on real data only: the real model, the students' real profiles, the new papers the professor
 * filed by student (see {@link MatchTestSet}), and the client's real alert emails run through M2-M4.
 */
public class RelevanceScorerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static RelevanceScorer scorer;
    /** Every filed PDF as a candidate with its PDF attached, and the student it is filed under. */
    private static Map<PaperCandidate, String> fullText;
    /** The readable filed PDFs again, known only by title and abstract, as a paywalled paper is. */
    private static Map<PaperCandidate, String> titleAndAbstract;
    /** The full-text candidates whose PDF has text (the rest are scans, known by their file name). */
    private static Set<PaperCandidate> readable;
    private static final Map<String, Map<String, List<ScoredPaper>>> MATCHES = new HashMap<>();

    private static synchronized RelevanceScorer scorer() {
        if (scorer == null) {
            scorer = new RelevanceScorer(TestModel.provider(), TestProfiles.profiles().values());
        }
        return scorer;
    }

    private static synchronized void readFiledPapers() {
        if (fullText != null) {
            return;
        }
        PdfTextExtractor extractor = new PdfTextExtractor();
        Map<PaperCandidate, String> withPdf = new LinkedHashMap<>();
        Map<PaperCandidate, String> abstractOnly = new LinkedHashMap<>();
        Set<PaperCandidate> withText = new HashSet<>();
        for (Filed filed : MatchTestSet.filed()) {
            PaperCandidate candidate = LocalPdfCandidates.of(filed.pdf());
            withPdf.put(candidate, filed.profileName());
            try {
                PaperText text = extractor.extract(filed.pdf());
                withText.add(candidate);
                abstractOnly.put(new PaperCandidate("<" + filed.pdf().toUri() + "#abstract>", text.title(), List.of(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(text.abstractText()), Optional.empty()), filed.profileName());
            } catch (UnreadablePdfException e) {
                // a scan: there is no abstract to take from it
            }
        }
        fullText = withPdf;
        titleAndAbstract = abstractOnly;
        readable = withText;
    }

    private static Map<PaperCandidate, String> fullText() {
        readFiledPapers();
        return fullText;
    }

    private static Map<PaperCandidate, String> titleAndAbstract() {
        readFiledPapers();
        return titleAndAbstract;
    }

    /** Each batch matched once per strategy and test JVM (a PDF takes seconds to read and embed). */
    private static synchronized Map<String, List<ScoredPaper>> matches(Map<PaperCandidate, String> batch,
            Strategy strategy) {
        String key = (batch == fullText() ? "full text" : "title and abstract") + "/" + strategy;
        return MATCHES.computeIfAbsent(key, k -> scorer().match(new ArrayList<>(batch.keySet()), strategy));
    }

    /**
     * As the professor asked: a paper goes to at most one student, the one it is most relevant to, and
     * only if it is close to that student's own work.
     */
    @Test
    public void aPaperIsMatchedOnlyToItsBestStudentAndOnlyWhenCloseToTheirWork() {
        List<ScoredPaper> matches = flatten(matches(fullText(), Strategy.HYBRID));

        assertEquals("each paper at most once", matches.size(),
                matches.stream().map(ScoredPaper::candidate).distinct().count());
        assertTrue(matches.size() < fullText().size());
        for (ScoredPaper match : matches) {
            assertEquals(match.profileName(), scorer().scoreAll(match.candidate()).get(0).profileName());
            assertTrue(match.rationale(), match.recommended());
            assertTrue(match.rationale(), relevance(match) >= (match.rationale().contains("title alone")
                    ? RelevanceScorer.TITLE_FLOOR : RelevanceScorer.RELEVANCE_FLOOR));
        }
    }

    /** The output every matcher shares: each student, in profile order, with their papers best first. */
    @Test
    public void matchesAreListedPerStudentBestFirst() {
        Map<String, List<ScoredPaper>> matches = matches(fullText(), Strategy.HYBRID);

        assertEquals(new ArrayList<>(TestProfiles.profiles().keySet()), new ArrayList<>(matches.keySet()));
        matches.forEach((student, papers) -> {
            for (int i = 0; i < papers.size(); i++) {
                assertEquals(student, papers.get(i).profileName());
                if (i > 0) {
                    assertTrue(student + ": best first", papers.get(i - 1).score() >= papers.get(i).score());
                }
            }
        });
    }

    /**
     * The new papers reach the student they are filed under, and almost nothing reaches the wrong
     * one. A scanned PDF has no text, so its file name is all there is to go on; it is left out.
     */
    @Test
    public void filedPapersReachTheirStudent() {
        Routing withText = Routing.of(matches(fullText(), Strategy.HYBRID).values().stream().flatMap(List::stream)
                .filter(match -> readable.contains(match.candidate())).toList(), fullText());

        assertTrue(withText.report(), withText.correct() >= 0.85 * readable.size());
        assertTrue(withText.report(), withText.correct() >= 0.90 * withText.papers());
    }

    /** Most alert papers arrive without a readable PDF: title and abstract alone must still find the student. */
    @Test
    public void titleAndAbstractAloneStillFindTheStudent() {
        List<ScoredPaper> matches = flatten(matches(titleAndAbstract(), Strategy.HYBRID));
        Routing routing = Routing.of(matches, titleAndAbstract());

        assertTrue(routing.report(), routing.correct() >= 0.85 * titleAndAbstract().size());
        assertTrue(routing.report(), routing.correct() >= 0.90 * routing.papers());
        for (ScoredPaper match : matches) {
            assertTrue(match.rationale(), match.rationale().contains("title and abstract"));
        }
    }

    /** The proposal's acceptance criterion: the papers recommended to each student are at least 80% theirs. */
    @Test
    public void recommendationsMeetTheProposalsPrecisionTarget() {
        double withPdf = MatchEvaluation.recommendationPrecision(matches(fullText(), Strategy.HYBRID), fullText(), 3);
        double abstractOnly = MatchEvaluation.recommendationPrecision(matches(titleAndAbstract(), Strategy.HYBRID),
                titleAndAbstract(), 3);

        assertTrue("full text " + withPdf, withPdf >= 0.8);
        assertTrue("title and abstract " + abstractOnly, abstractOnly >= 0.8);
    }

    /** Why M6 compares words as well: on the professor's filed papers, words and meaning together beat SPECTER2 alone. */
    @Test
    public void wordsAndMeaningTogetherBeatSpecter2Alone() {
        Map<String, List<ScoredPaper>> hybrid = matches(fullText(), Strategy.HYBRID);
        Map<String, List<ScoredPaper>> semantic = matches(fullText(), Strategy.SEMANTIC);
        int hybridCorrect = MatchEvaluation.assignedCorrectly(hybrid, fullText());
        int semanticCorrect = MatchEvaluation.assignedCorrectly(semantic, fullText());
        double hybridPrecision = MatchEvaluation.recommendationPrecision(hybrid, fullText(), 3);
        double semanticPrecision = MatchEvaluation.recommendationPrecision(semantic, fullText(), 3);

        String detail = String.format(Locale.ROOT, "hybrid %d correct, precision %.2f; SPECTER2 alone %d correct, precision %.2f",
                hybridCorrect, hybridPrecision, semanticCorrect, semanticPrecision);
        assertTrue(detail, hybridCorrect >= semanticCorrect && hybridPrecision >= semanticPrecision);
        assertTrue(detail, hybridCorrect > semanticCorrect || hybridPrecision > semanticPrecision);
    }

    @Test
    public void everyScoreIsBetweenZeroAndOneAndExplained() {
        matches(fullText(), Strategy.HYBRID); // every candidate read and embedded once
        for (PaperCandidate candidate : fullText().keySet()) {
            List<ScoredPaper> scored = scorer().scoreAll(candidate);
            assertEquals(TestProfiles.profiles().size(), scored.size());
            for (int i = 0; i < scored.size(); i++) {
                ScoredPaper match = scored.get(i);
                assertTrue(match.score() >= 0 && match.score() <= 1);
                assertEquals(ReviewStatus.PENDING, match.status());
                assertFalse(match.recommended());
                assertTrue(match.rationale(), match.rationale().contains("Closest in " + match.profileName()));
                assertTrue(match.rationale(), match.rationale().contains("Scored from"));
                if (i > 0) {
                    assertTrue("best match first", scored.get(i - 1).score() >= match.score());
                }
            }
        }
    }

    /**
     * Design-doc check on known papers: each student's own reference paper, offered as a new
     * candidate, scores best for that student, and far lower for the student it fits least.
     */
    @Test
    public void aStudentsOwnPaperScoresAsTheirs() {
        Map<String, Set<String>> foldersBySha = new HashMap<>();
        TestProfiles.profiles().values().forEach(profile -> profile.papers().forEach(paper ->
                foldersBySha.computeIfAbsent(paper.sha256(), sha -> new HashSet<>()).add(profile.name())));
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        TestProfiles.profiles().values().parallelStream().forEach(profile -> {
            ProfilePaper paper = profile.papers().get(0);
            Path pdf = TestProfiles.source().resolve(profile.name()).resolve(paper.relativePath());
            List<ScoredPaper> scored = scorer().scoreAll(LocalPdfCandidates.of(pdf));
            ScoredPaper best = scored.get(0);
            if (!foldersBySha.get(paper.sha256()).contains(best.profileName())) {
                failures.add(String.format(Locale.ROOT, "%s/%s scored best for %s (%.3f)", profile.name(),
                        paper.relativePath(), best.profileName(), best.score()));
            }
            if (scored.get(scored.size() - 1).score() >= best.score() / 2) {
                failures.add(profile.name() + "/" + paper.relativePath() + " fits every student about equally");
            }
        });
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    /** Design-doc check: a blank profile degrades to a zero score instead of NaN or an exception. */
    @Test
    public void blankProfileScoresZeroInsteadOfFailing() {
        Profile real = TestProfiles.profiles().values().iterator().next();
        Profile blank = blanked(real, "zz-blank");
        List<Profile> withBlank = new ArrayList<>(TestProfiles.profiles().values());
        withBlank.add(blank);
        PaperCandidate candidate = titleOnly(real.papers().get(0).title());

        List<ScoredPaper> scored = new RelevanceScorer(TestModel.provider(), withBlank).scoreAll(candidate);
        ScoredPaper alone = new RelevanceScorer(TestModel.provider(), List.of(blank)).scoreAll(candidate).get(0);

        ScoredPaper last = scored.get(scored.size() - 1);
        assertEquals("zz-blank", last.profileName());
        assertEquals(0.0, last.score(), 0.01);
        assertEquals(0.0, alone.score(), 0.01);
        assertTrue(alone.rationale(), alone.rationale().contains("title alone"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void profilesFromAnotherModelAreRejected() {
        Profile real = TestProfiles.profiles().values().iterator().next();
        Profile older = new Profile(real.name(), real.emails(), real.papers(), "some-older-model", real.updatedAt());

        new RelevanceScorer(TestModel.provider(), List.of(older));
    }

    /** Matching by words needs the papers' text in M7d: a store saved before it was kept must be updated first. */
    @Test
    public void profilesSavedWithoutTextAreRejectedUnlessScoredByMeaningAlone() {
        Profile real = TestProfiles.profiles().values().iterator().next();
        List<ProfilePaper> vectorsOnly = real.papers().stream()
                .map(p -> new ProfilePaper(p.relativePath(), p.sha256(), p.title(), p.embedding(), p.abstractView(),
                        p.structureView(), p.bodyView()))
                .toList();
        Profile older = new Profile(real.name(), real.emails(), vectorsOnly, real.modelId(), real.updatedAt());

        try {
            new RelevanceScorer(TestModel.provider(), List.of(older));
            fail("a profile without text was accepted for matching by words");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ProfileBuilderCli"));
        }
        new RelevanceScorer(TestModel.provider(), List.of(older), Strategy.SEMANTIC);
    }

    /**
     * The papers M4 actually hands over: the client's six real alert emails, extracted, enriched and
     * deduplicated offline from the recorded HTTP traffic. The paper the professor called highly relevant
     * is matched; the SpringerOpen paper he said should be marked irrelevant is not. (The ASCE issue's
     * discussion, closure, book reviews and erratum never get this far: M2 drops them.)
     */
    @Test
    public void clientAlertPapersAreMatchedOnlyWhenRelevantResearch() throws IOException {
        List<PaperCandidate> deduplicated = ClientAlerts.deduplicated(tmp.newFolder("data").toPath());

        List<ScoredPaper> matches = flatten(scorer().match(deduplicated));

        assertTrue(matches.size() + " of " + deduplicated.size(), !matches.isEmpty() && matches.size() < deduplicated.size());
        assertTrue(titled(matches, "Advancing soil-structure interaction (SSI)").isPresent());
        assertFalse(titled(matches, "Hygrothermal analysis of ceramic coating durability").isPresent());
        assertTrue(deduplicated.stream().allMatch(PaperCandidate::isResearch));
    }

    private static Optional<ScoredPaper> titled(List<ScoredPaper> matches, String titleStart) {
        String wanted = Titles.compact(titleStart);
        return matches.stream().filter(match -> Titles.compact(match.candidate().title()).startsWith(wanted)).findFirst();
    }

    /** The relevance a match's rationale reports. */
    private static double relevance(ScoredPaper match) {
        Matcher found = Pattern.compile("Relevance ([0-9.]+)").matcher(match.rationale());
        assertTrue(match.rationale(), found.find());
        return Double.parseDouble(found.group(1));
    }

    /** Every student's papers in one list, for checks that look at papers one by one. */
    private static List<ScoredPaper> flatten(Map<String, List<ScoredPaper>> matches) {
        return matches.values().stream().flatMap(List::stream).toList();
    }

    /** Tally of how filed papers were assigned. */
    private record Routing(int papers, int correct, List<String> misses) {

        static Routing of(List<ScoredPaper> matches, Map<PaperCandidate, String> filedUnder) {
            int correct = 0;
            List<String> misses = new ArrayList<>();
            for (ScoredPaper match : matches) {
                String filed = filedUnder.get(match.candidate());
                if (match.profileName().equals(filed)) {
                    correct++;
                } else {
                    misses.add(String.format(Locale.ROOT, "  filed %s, assigned %s (%.2f): %s", filed, match.profileName(),
                            match.score(), match.candidate().title()));
                }
            }
            Collections.sort(misses);
            return new Routing(matches.size(), correct, misses);
        }

        String report() {
            return papers + " papers, " + correct + " assigned to their filed student; misses:\n" + String.join("\n", misses);
        }
    }

    private static PaperCandidate titleOnly(String title) {
        return new PaperCandidate("<title-only>", title, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * A real profile emptied of content: every vector zero and every text blank - what a blank profile
     * would look like. Its papers get their own identities so they are not taken for the real ones.
     */
    private static Profile blanked(Profile profile, String name) {
        List<ProfilePaper> papers = profile.papers().stream()
                .map(p -> new ProfilePaper(p.relativePath(), "blank-" + p.sha256(), p.title(), new float[p.embedding().length],
                        new float[p.abstractView().length], p.structureView().map(v -> new float[v.length]),
                        p.bodyView().map(v -> new float[v.length]), Optional.of(""), Optional.of("")))
                .toList();
        return new Profile(name, profile.emails(), papers, profile.modelId(), profile.updatedAt());
    }
}
