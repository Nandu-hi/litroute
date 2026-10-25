package com.litroute.scoring;

import com.litroute.embedding.EmbeddingProvider;
import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.profile.PaperEmbedder;
import com.litroute.profile.PaperText;
import com.litroute.profile.PdfTextExtractor;
import com.litroute.profile.UnreadablePdfException;
import com.litroute.util.Vectors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * M6: matches deduplicated papers (M4's output) to the students' profiles (M7d). A paper goes to
 * the one student it suits best, and only if it is a research paper close to that student's own work;
 * the rest of a batch matches no one and never reaches the professor's queue.
 *
 * A candidate is compared with every student's reference papers in two ways, from the best evidence
 * it has: its PDF if M2 saved a readable one, otherwise its title and abstract, otherwise its title.
 * <ul>
 *   <li><b>Meaning</b>: SPECTER2 vectors, like for like (a PDF's full vector with the papers' full
 *       vectors, an abstract with their abstract views), blending the similarity to the student's
 *       papers taken together (their mean vector) with the mean similarity to the three closest papers.
 *       A profile is uniform: one body of work.
 *   <li><b>Wording</b>: the words and word pairs the candidate shares with the student's papers,
 *       weighted by TF-IDF ({@link LexicalIndex}), blended the same way.
 * </ul>
 * Each signal is standardized across the students for this candidate, so a paper similar to every
 * student - "civil engineering" in general - stands out for no one. The average of the two is the
 * paper's <em>fit</em> with each student, and a softmax of the fit its <em>share</em>: how much of the
 * paper belongs to that student rather than the others. Its <em>relevance</em> is absolute: 0.9 when it
 * is as close to the student's papers as their own papers typically are, 0.1 when it is as close as
 * other students' papers are, measured for each signal and kind of evidence. The score is relevance
 * times share. A paper's title can lift the relevance of its abstract or PDF ({@link #TITLE_LIFT}), and a
 * paper known by its title alone weighs meaning above words ({@link #TITLE_MEANING_WEIGHT}). A paper is
 * assigned to its highest-scoring student, and matched only if its relevance for that student reaches
 * {@link #RELEVANCE_FLOOR}, or {@link #TITLE_FLOOR} from a title alone; there is no quota per student.
 *
 * Why both signals: the eight reference students all work in structural and hazard engineering, so
 * SPECTER2 puts nearly every paper 0.85-0.95 from every student. Of the 48 papers the professor
 * filed by student (~/.litroute/match-test), scored from their full PDFs, meaning alone assigned 43 to
 * their student and got 81% of the students' top three right; wording alone 43 and 88%; both together
 * 46 and every top three right (from title and abstract only: 44 of 46, every top three right).
 * Keeping only words that are not common English, or training SPECTER2's vectors on the students'
 * folders with a triplet loss, did not do better.
 *
 * The scorer remembers the evidence it read for each candidate, so scoring a candidate again - by
 * another strategy, say - neither reads nor embeds it twice. Thread-safe: {@link #match} scores
 * candidates in parallel.
 */
public final class RelevanceScorer implements PaperMatcher {

    private static final Logger LOG = Logger.getLogger(RelevanceScorer.class.getName());

    /** Share of a blend given to the student's papers as a whole; the rest goes to the closest papers. */
    static final double PROFILE_WEIGHT = 0.5;
    static final int CLOSEST_PAPERS = 3;
    /** Two files whose vectors are this close hold the same paper, saved twice. */
    static final double SAME_PAPER = 0.99;
    /**
     * How sharply the share favours the best-fitting student. On the held-out reference papers 1, 2
     * and 3 rank papers equally well; 2 gives a clear match most of the share (0.6-0.85) without hiding
     * the runner-up.
     */
    static final double SHARE_SHARPNESS = 2.0;
    /**
     * A paper is matched only if its relevance reaches this: about 70% of the way from looking like
     * other students' papers (relevance 0.1) to looking like the student's own (0.9). The midpoint, 0.5,
     * matched 17 of the 31 papers in the professor's first reviewed batch (October 2026); the professor approved 11
     * and dropped 6, all from generic hazard and disaster-management papers that every hazard profile
     * half fits. At 0.7, 11 were matched: 9 the professor approved, plus one dropped and one (a rainstorm
     * hazard-chain paper) a per-student quota of three had pushed out. Of the papers the professor filed by
     * student (~/.litroute/match-test), 41 of 46 still reach their student from a full PDF or a title and
     * abstract (43 at 0.5); from a title alone, 33 (35 at 0.5). The floor is the same for every kind of
     * evidence: a title-only alert is less certain, not more forgiving.
     */
    public static final double RELEVANCE_FLOOR = 0.7;
    /**
     * The floor for a paper known by its title alone (no abstract, no readable PDF - Elsevier deposits no
     * abstracts with Crossref or OpenAlex). A title is a dozen words, so its relevance stays near the
     * middle of the scale even when it plainly belongs to a student: of the professor's October 2026
     * batch, the flood fragility of bridge piers (RP1) reached 0.61 and the compound geohazards of the
     * Greater Bay Area (RP4) 0.44, while the out-of-scope review of steels under impact reached 0.41
     * (with {@link #TITLE_MEANING_WEIGHT}). 0.43 takes the first two and leaves the third. On the papers the professor
     * filed by student, judged by their titles alone, 34 of 46 then reach their student (32 at 0.7) and 5
     * reach another (3 at 0.7).
     */
    public static final double TITLE_FLOOR = 0.43;
    /**
     * For a title, how much of its relevance comes from its meaning (SPECTER2) rather than its words:
     * a dozen words share few terms with anyone's papers, so the words count for less than in an abstract
     * (where the two count equally). This is what keeps the steel review (words 0.59, meaning 0.32) below
     * the compound-geohazards paper (words 0.43, meaning 0.45).
     */
    static final double TITLE_MEANING_WEIGHT = 0.65;
    /**
     * How far a paper's title may lift the relevance of its abstract or PDF: by this fraction of the gap
     * when the title alone looks closer to the student, never lowering it. An abstract can bury what the
     * title says outright: the ground-motion orientation paper (RP8) reaches 0.59 from its abstract and
     * 0.85 from its title, so 0.70 with the lift. 0.6 is the least that matches it; on the professor's
     * filed papers known by title and abstract, it changes no match.
     */
    static final double TITLE_LIFT = 0.6;
    /**
     * Anchors measured on the eight reference profiles, for when the loaded profiles cannot provide
     * their own: a single student, or students with a single paper each.
     */
    static final Anchors FULL_TEXT_FALLBACK = new Anchors(0.952, 0.911);
    static final Anchors ABSTRACT_FALLBACK = new Anchors(0.941, 0.897);
    static final Anchors TITLE_FALLBACK = new Anchors(0.930, 0.892);
    static final Anchors LEXICAL_FULL_TEXT_FALLBACK = new Anchors(0.238, 0.113);
    static final Anchors LEXICAL_ABSTRACT_FALLBACK = new Anchors(0.164, 0.064);
    static final Anchors LEXICAL_TITLE_FALLBACK = new Anchors(0.089, 0.025);
    private static final double LOG_9 = Math.log(9);
    private static final int RATIONALE_TITLE_CHARS = 90;
    private static final int RATIONALE_TERMS = 5;

    /** How candidates are compared with the students. */
    public enum Strategy {
        /** SPECTER2 vectors only: what M6 did before the words were added. */
        SEMANTIC(true, false),
        /** The papers' words only. */
        LEXICAL(false, true),
        /** Both: the default, and the most accurate on the reference data. */
        HYBRID(true, true);

        private final boolean semantic;
        private final boolean lexical;

        /**
         * Input: whether the strategy uses SPECTER2 meaning and/or words.
         * Output: the strategy constant.
         * Used by: the enum constants.
         * Example: HYBRID -> semantic = true, lexical = true
         */
        Strategy(boolean semantic, boolean lexical) {
            this.semantic = semantic;
            this.lexical = lexical;
        }
    }

    /** What a candidate was scored from. */
    public enum Basis {
        FULL_TEXT("its full PDF", "a full PDF"),
        ABSTRACT("its title and abstract (no readable PDF)", "a title and abstract"),
        TITLE("its title alone (no abstract or readable PDF), so the score is less certain", "a title alone");

        private final String description;
        private final String label;

        /**
         * Input: the rationale's description and a short label.
         * Output: the basis constant.
         * Used by: the enum constants.
         * Example: TITLE -> "its title alone (no abstract ...)"
         */
        Basis(String description, String label) {
            this.description = description;
            this.label = label;
        }
    }

    /**
     * The similarity at which a paper looks like a student's own (relevance 0.9) and like another
     * student's (0.1); relevance in between and beyond follows a logistic curve.
     */
    record Anchors(double own, double other) {

        /**
         * 0 at a typical other student's paper, 1 at a typical own paper.
         * Input: a similarity.
         * Output: 0 at a typical other student's paper, 1 at a typical own paper (linear, unbounded).
         * Used by: {@link RelevanceScorer#relevance(Strategy, Calibration, double, double, double)}.
         * Example: Anchors(0.95, 0.91).normalized(0.93) -> 0.5
         */
        double normalized(double similarity) {
            return (similarity - other) / (own - other);
        }

        /**
         * Input: a normalized similarity.
         * Output: the logistic relevance: 0.1 at 0, 0.5 at 0.5, 0.9 at 1.
         * Used by: {@link RelevanceScorer#relevance(Strategy, Calibration, double, double, double)}.
         * Example: relevance(1.0) -> 0.9; relevance(0.0) -> 0.1
         */
        static double relevance(double normalized) {
            return 1 / (1 + Math.exp(-LOG_9 * (2 * normalized - 1)));
        }
    }

    /** One kind of evidence's anchors for each signal. */
    private record Calibration(Anchors semantic, Anchors lexical) {
    }

    /** What a candidate is compared from: its SPECTER2 vector and the text whose words are matched. */
    private record Evidence(Basis basis, float[] vector, String text) {
    }

    /** One student's view of one candidate; the explanation ends where a recommendation note may follow. */
    private record Assessment(PaperCandidate candidate, String profileName, double score, double relevance, Basis basis,
            String explanation) {

        /**
         * Input: whether recommended, and a note to append to the explanation.
         * Output: a PENDING ScoredPaper with this score, relevance and rationale.
         * Used by: scoreAll, match.
         * Example: Assessment(P, alice, 0.62, 0.81, ...).toScoredPaper(true, "") -> ScoredPaper(P, alice, 0.62, recommended)
         */
        ScoredPaper toScoredPaper(boolean recommended, String note) {
            return new ScoredPaper(candidate, profileName, score, explanation + note, ReviewStatus.PENDING, recommended,
                    Optional.of(relevance));
        }
    }

    private final EmbeddingProvider provider;
    private final Strategy strategy;
    private final PdfTextExtractor extractor = new PdfTextExtractor();
    private final PaperEmbedder embedder;
    private final List<Index> indexes;
    /** Null when some profile was saved without its papers' text: then only SEMANTIC can be used. */
    private final LexicalIndex lexical;
    private final Calibration fullTextCalibration;
    private final Calibration abstractCalibration;
    /** Measured on first use: it needs every profile paper's title embedded on its own. */
    private Calibration titleCalibration;
    private final Map<PaperCandidate, Evidence> evidence = new ConcurrentHashMap<>();
    private final Map<PaperCandidate, Evidence> titleEvidence = new ConcurrentHashMap<>();

    /**
     * A scorer using both signals ({@link Strategy#HYBRID}).
     * Input: the embedding model and every student's profile (M7d).
     * Output: a HYBRID scorer, calibrated on the profiles.
     * Used by: PipelineRunner.matcher (M9, HYBRID matcher), RelevanceScorerCli.
     * Example: {@code new RelevanceScorer(specter2, profileStore.loadAll().values())}
     */
    public RelevanceScorer(EmbeddingProvider provider, Collection<Profile> profiles) {
        this(provider, profiles, Strategy.HYBRID);
    }

    /**
     * Input: the model, every profile, and the strategy to score by.
     * Output: a scorer with full-text and abstract calibration measured on held-out profile papers; throws if a profile used another model, or words are needed and a profile has no text.
     * Used by: the two-argument constructor; RelevanceScorerTest (SEMANTIC vs HYBRID).
     * Example: profile built with another modelId -> IllegalArgumentException "... rebuild the profiles first"
     */
    public RelevanceScorer(EmbeddingProvider provider, Collection<Profile> profiles, Strategy strategy) {
        List<String> withoutText = new ArrayList<>();
        for (Profile profile : profiles) {
            if (!profile.modelId().equals(provider.modelId())) {
                throw new IllegalArgumentException("Profile " + profile.name() + " was built with " + profile.modelId()
                        + ", not " + provider.modelId() + "; rebuild the profiles first");
            }
            if (profile.papers().stream().anyMatch(paper -> !paper.hasText())) {
                withoutText.add(profile.name());
            }
        }
        if (strategy.lexical && !withoutText.isEmpty()) {
            throw new IllegalArgumentException("Profiles " + withoutText + " were saved without their papers' text, which "
                    + "matching by words needs; update them with ProfileBuilderCli first");
        }
        this.provider = provider;
        this.strategy = strategy;
        this.embedder = new PaperEmbedder(provider);
        this.indexes = profiles.stream().map(Index::of).toList();
        Map<String, List<ProfilePaper>> distinctPapers = new LinkedHashMap<>();
        indexes.forEach(index -> distinctPapers.put(index.profile().name(), index.fullText().papers));
        this.lexical = withoutText.isEmpty() ? new LexicalIndex(distinctPapers) : null;
        this.fullTextCalibration = calibrate(Basis.FULL_TEXT, ProfilePaper::embedding, FULL_TEXT_FALLBACK,
                LEXICAL_FULL_TEXT_FALLBACK);
        this.abstractCalibration = calibrate(Basis.ABSTRACT, ProfilePaper::abstractView, ABSTRACT_FALLBACK,
                LEXICAL_ABSTRACT_FALLBACK);
    }

    /**
     * Scores the candidate for one of the loaded students. The score depends on the other students
     * too (the share), so this scores the candidate for all of them; prefer {@link #scoreAll}.
     * Input: a candidate and one of the loaded profiles.
     * Output: the candidate's score for that student (scores all, since the share depends on the others); throws for an unknown profile.
     * Used by: tests.
     * Example: score(P, alice) -> ScoredPaper(P, alice, 0.62, "Best match: 70% ...")
     */
    public ScoredPaper score(PaperCandidate candidate, Profile profile) {
        return scoreAll(candidate).stream().filter(scored -> scored.profileName().equals(profile.name())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Profile " + profile.name()
                        + " is not one of the profiles this scorer compares with"));
    }

    /**
     * Scores the candidate for every student, best match first.
     * Input: a candidate.
     * Output: its score for every student by this scorer's strategy, best first.
     * Used by: RelevanceScorerCli (scoring dropped-in PDFs), tests.
     * Example: -> [alice 0.62, bob 0.11, carol 0.05]
     */
    public List<ScoredPaper> scoreAll(PaperCandidate candidate) {
        return scoreAll(candidate, strategy);
    }

    /**
     * Scores the candidate for every student by the given strategy, best match first.
     * Input: a candidate and a strategy.
     * Output: its score for every student by that strategy, best first, none recommended.
     * Used by: {@link #scoreAll(PaperCandidate)}; tests comparing strategies.
     * Example: scoreAll(P, SEMANTIC) -> [bob 0.40, alice 0.35, ...]
     */
    public List<ScoredPaper> scoreAll(PaperCandidate candidate, Strategy byStrategy) {
        return assess(candidate, byStrategy).stream().map(assessment -> assessment.toScoredPaper(false, "")).toList();
    }

    /**
     * Matches a batch of deduplicated papers (M4's output) to students.
     *
     * @see #match(List, Strategy)
     * Input: deduplicated papers (M4/M7c).
     * Output: see {@link #match(List, Strategy)}, by this scorer's strategy.
     * Used by: PipelineRunner.run (M9) through PaperMatcher; RelevanceScorerCli.
     * Example: [P1, P2, P3] -> {alice=[P1], bob=[], carol=[P3]}
     */
    @Override
    public Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch) {
        return match(batch, strategy);
    }

    /**
     * Matches a batch of deduplicated papers to students: each candidate is scored for every student and
     * goes to the one it scores highest for, but only if it is a research paper (not a book review,
     * discussion, correction or the like) whose relevance for that student reaches
     * {@link #RELEVANCE_FLOOR}. Such a paper is returned once, recommended; any other is not returned.
     * A student gets as many papers as qualify, however many or few.
     * Input: deduplicated papers and a strategy.
     * Output: every student (profile order) mapped to the research papers whose best student they are and whose relevance reaches the floor, recommended, best first.
     * Used by: {@link #match(List)}; RelevanceScorerCli and tests comparing strategies.
     * Example: P1 relevance 0.81 for alice (best) -> alice=[P1]; P2 best for bob at 0.55 -> not returned
     */
    public Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch, Strategy byStrategy) {
        // Papers are independent and one with a PDF takes seconds to embed, so they are scored in parallel.
        List<Assessment> best = batch.parallelStream().map(candidate -> assess(candidate, byStrategy).get(0)).toList();
        Map<String, List<ScoredPaper>> byStudent = new LinkedHashMap<>();
        indexes.forEach(index -> byStudent.put(index.profile().name(), new ArrayList<>()));
        for (Assessment assessment : best) {
            if (assessment.candidate().isResearch() && assessment.relevance() >= floor(assessment.basis())) {
                byStudent.get(assessment.profileName()).add(assessment.toScoredPaper(true, ""));
            }
        }
        byStudent.replaceAll((name, papers) -> papers.stream()
                .sorted(Comparator.comparingDouble(ScoredPaper::score).reversed()).toList());
        return byStudent;
    }

    /**
     * The candidate scored for every student by the given strategy, best first.
     * Input: a candidate and a strategy.
     * Output: one Assessment per student (score = relevance x share, with a rationale), best first.
     * Used by: scoreAll, match.
     * Example: -> [Assessment(alice, 0.62, relevance 0.81, "Best match: 76% ... Relevance 0.81 ..."), ...]
     */
    private List<Assessment> assess(PaperCandidate candidate, Strategy byStrategy) {
        if (byStrategy.lexical && lexical == null) {
            throw new IllegalStateException("These profiles were saved without their papers' text; only "
                    + Strategy.SEMANTIC + " can score with them until ProfileBuilderCli updates them");
        }
        Evidence evidence = evidence(candidate);
        Calibration calibration = calibration(evidence.basis());
        int n = indexes.size();
        List<String> names = indexes.stream().map(index -> index.profile().name()).toList();
        List<Closeness> semantic = indexes.stream().map(index -> index.space(evidence.basis()).closeness(evidence.vector()))
                .toList();
        Map<String, Double> wording = byStrategy.lexical ? lexical.vector(evidence.text()) : Map.of();
        Map<String, LexicalIndex.Closeness> lexicalCloseness = byStrategy.lexical
                ? lexical.closeness(wording, paper -> false) : Map.of();

        // Raw similarity of the candidate to each student, by meaning and by words.
        double[] semanticSimilarity = new double[n];
        double[] lexicalSimilarity = new double[n];
        for (int i = 0; i < n; i++) {
            semanticSimilarity[i] = semantic.get(i).similarity();
            lexicalSimilarity[i] = byStrategy.lexical ? lexicalCloseness.get(names.get(i)).similarity() : 0;
        }
        // Share: relative (who suits it most); relevance: absolute (how close to the student's own work).
        double[] fit = fit(byStrategy, semanticSimilarity, lexicalSimilarity);
        double[] share = softmax(fit);
        double[] relevance = new double[n];
        double[] fromTitle = evidence.basis() == Basis.TITLE ? null : titleRelevance(candidate, byStrategy);
        boolean[] lifted = new boolean[n];
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            relevance[i] = relevance(byStrategy, calibration, semanticSimilarity[i], lexicalSimilarity[i],
                    evidence.basis() == Basis.TITLE ? TITLE_MEANING_WEIGHT : 0.5);
            lifted[i] = fromTitle != null && fromTitle[i] > relevance[i];
            if (lifted[i]) {
                relevance[i] += TITLE_LIFT * (fromTitle[i] - relevance[i]);
            }
            score[i] = relevance[i] * share[i];
        }
        // Build each student's rationale, best-scoring student first.
        List<Integer> order = IntStream.range(0, n).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> score[i]).reversed()).toList();
        Map<String, String> surfaces = byStrategy.lexical ? Terms.surfaceForms(evidence.text()) : Map.of();
        List<Assessment> assessments = new ArrayList<>();
        for (int rank = 0; rank < n; rank++) {
            int i = order.get(rank);
            int other = order.get(rank == 0 ? Math.min(1, n - 1) : 0);
            StringBuilder explanation = new StringBuilder();
            if (n > 1) {
                explanation.append(String.format(Locale.ROOT, rank == 0
                        ? "Best match: %.0f%% of the paper's match goes to %s (next: %s %.0f%%). "
                        : "%.0f%% of the paper's match goes to %s (best: %s %.0f%%). ",
                        100 * share[i], names.get(i), names.get(other), 100 * share[other]));
            }
            explanation.append(String.format(Locale.ROOT, "Relevance %.2f (a student's own papers typically 0.9, other "
                    + "students' 0.1). ", relevance[i]));
            if (lifted[i]) {
                explanation.append(String.format(Locale.ROOT, "Its title alone looks closer still (%.2f), which lifted "
                        + "it. ", fromTitle[i]));
            }
            if (byStrategy.lexical) {
                List<String> shared = lexical.sharedTerms(wording, names.get(i), RATIONALE_TERMS).stream()
                        .map(term -> "\"" + surfaces.getOrDefault(term, term.replace(Terms.PAIR, " ")) + "\"").toList();
                if (!shared.isEmpty()) {
                    explanation.append("Shared words: ").append(String.join(", ", shared)).append(". ");
                }
            }
            List<String> closest = new ArrayList<>();
            if (byStrategy.semantic) {
                semantic.get(i).closest().forEach(neighbor -> closest.add(quote(neighbor.paper(), neighbor.similarity())));
            } else {
                lexicalCloseness.get(names.get(i)).closest().forEach(neighbor -> closest.add(quote(neighbor.paper(),
                        neighbor.similarity())));
            }
            if (closest.isEmpty()) {
                explanation.append(names.get(i)).append(" has no usable papers to compare with. ");
            } else {
                explanation.append("Closest in ").append(names.get(i)).append(": ").append(String.join(", ", closest))
                        .append(". ");
            }
            explanation.append("Scored from ").append(evidence.basis().description).append('.');
            assessments.add(new Assessment(candidate, names.get(i), score[i], relevance[i], evidence.basis(),
                    explanation.toString()));
        }
        return assessments;
    }

    /**
     * Each student's fit: the strategy's signals standardized across the students, averaged.
     * Input: the strategy and each student's semantic and lexical similarity.
     * Output: each student's fit: the used signals' z-scores, averaged.
     * Used by: {@link #assess}.
     * Example: semantic z [1, -1], lexical z [1, -1] -> fit [1, -1]
     */
    private static double[] fit(Strategy byStrategy, double[] semanticSimilarity, double[] lexicalSimilarity) {
        double[] fit = new double[semanticSimilarity.length];
        double[] semanticZ = zScores(semanticSimilarity);
        double[] lexicalZ = zScores(lexicalSimilarity);
        int signals = (byStrategy.semantic ? 1 : 0) + (byStrategy.lexical ? 1 : 0);
        for (int i = 0; i < fit.length; i++) {
            fit[i] = ((byStrategy.semantic ? semanticZ[i] : 0) + (byStrategy.lexical ? lexicalZ[i] : 0)) / signals;
        }
        return fit;
    }

    /**
     * Standard scores; all zero when the values do not differ (a signal that cannot tell the students apart).
     * Input: values, one per student.
     * Output: their standard scores; all 0 if the values don't differ.
     * Used by: {@link #fit}.
     * Example: [1, 2, 3] -> [-1.22, 0, 1.22]; [5, 5] -> [0, 0]
     */
    private static double[] zScores(double[] values) {
        double mean = 0;
        for (double value : values) {
            mean += value / values.length;
        }
        double variance = 0;
        for (double value : values) {
            variance += (value - mean) * (value - mean) / values.length;
        }
        double sd = Math.sqrt(variance);
        double[] z = new double[values.length];
        if (sd > 1e-12) {
            for (int i = 0; i < values.length; i++) {
                z[i] = (values[i] - mean) / sd;
            }
        }
        return z;
    }

    /**
     * Input: each student's fit.
     * Output: shares summing to 1: exp({@value #SHARE_SHARPNESS} x fit), normalized.
     * Used by: {@link #assess}.
     * Example: [1, -1] -> [0.98, 0.02]
     */
    private static double[] softmax(double[] fit) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : fit) {
            max = Math.max(max, value);
        }
        double[] share = new double[fit.length];
        double total = 0;
        for (int i = 0; i < fit.length; i++) {
            share[i] = Math.exp(SHARE_SHARPNESS * (fit[i] - max));
            total += share[i];
        }
        for (int i = 0; i < fit.length; i++) {
            share[i] /= total;
        }
        return share;
    }

    /**
     * The least relevance a paper known from this evidence needs to be matched.
     * Input: what a candidate was scored from.
     * Output: the least relevance it needs to be matched: {@value #TITLE_FLOOR} for a title alone, else {@value #RELEVANCE_FLOOR}.
     * Used by: {@link #match(List, Strategy)}; tests.
     * Example: floor(TITLE) -> 0.43; floor(ABSTRACT) -> 0.7
     */
    public static double floor(Basis basis) {
        return basis == Basis.TITLE ? TITLE_FLOOR : RELEVANCE_FLOOR;
    }

    /**
     * Input: the strategy, the evidence's calibration, both similarities, and the meaning weight.
     * Output: the logistic relevance of the (weighted) normalized similarities.
     * Used by: assess, titleRelevance.
     * Example: both signals at the own-paper anchor -> 0.9
     */
    private static double relevance(Strategy byStrategy, Calibration calibration, double semantic, double lexical,
            double meaningWeight) {
        if (byStrategy.semantic && byStrategy.lexical) {
            return Anchors.relevance(meaningWeight * calibration.semantic().normalized(semantic)
                    + (1 - meaningWeight) * calibration.lexical().normalized(lexical));
        }
        return Anchors.relevance(byStrategy.semantic ? calibration.semantic().normalized(semantic)
                : calibration.lexical().normalized(lexical));
    }

    /**
     * Each student's relevance for the candidate's title alone, measured as a title-only candidate's would be.
     * Input: a candidate with an abstract or PDF.
     * Output: each student's relevance for its title alone, as a title-only candidate's would be measured.
     * Used by: {@link #assess}, for the title lift.
     * Example: RP8: abstract 0.59, title 0.85 -> lifted to 0.59 + 0.6 x 0.26 = 0.75
     */
    private double[] titleRelevance(PaperCandidate candidate, Strategy byStrategy) {
        Evidence title = titleEvidence.computeIfAbsent(candidate,
                paper -> new Evidence(Basis.TITLE, provider.embed(paper.title(), ""), paper.title()));
        Calibration calibration = titleCalibration();
        Map<String, LexicalIndex.Closeness> wording = byStrategy.lexical
                ? lexical.closeness(lexical.vector(title.text()), paper -> false) : Map.of();
        double[] relevance = new double[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) {
            Index index = indexes.get(i);
            double semantic = index.space(Basis.TITLE).closeness(title.vector()).similarity();
            double lexicalSimilarity = byStrategy.lexical ? wording.get(index.profile().name()).similarity() : 0;
            relevance[i] = relevance(byStrategy, calibration, semantic, lexicalSimilarity, TITLE_MEANING_WEIGHT);
        }
        return relevance;
    }

    /**
     * Input: what a candidate was scored from.
     * Output: the anchors for that kind of evidence.
     * Used by: {@link #assess}.
     * Example: calibration(FULL_TEXT) -> fullTextCalibration
     */
    private Calibration calibration(Basis basis) {
        return switch (basis) {
            case FULL_TEXT -> fullTextCalibration;
            case ABSTRACT -> abstractCalibration;
            case TITLE -> titleCalibration();
        };
    }

    /**
     * A title says less than an abstract, so title-only candidates are calibrated on titles too: each
     * profile paper's title, embedded alone, held out and compared with the abstract views like a
     * candidate would be. Comparing titles with the abstract views (rather than with the papers'
     * titles) routes them better: 78% to the right student first on the held-out papers, against 73%.
     * Input: none.
     * Output: title anchors, measured on first use by embedding every profile paper's title alone.
     * Used by: calibration, titleRelevance.
     * Example: first call embeds N titles (seconds); later calls return the cached result
     */
    private synchronized Calibration titleCalibration() {
        if (titleCalibration == null) {
            Map<String, float[]> titleVectors = indexes.stream()
                    .flatMap(index -> index.fullText().papers.stream())
                    .collect(Collectors.toMap(ProfilePaper::sha256, paper -> paper, (first, copy) -> first))
                    .values().parallelStream()
                    .collect(Collectors.toConcurrentMap(ProfilePaper::sha256, paper -> provider.embed(paper.title(), "")));
            titleCalibration = calibrate(Basis.TITLE, paper -> titleVectors.get(paper.sha256()), TITLE_FALLBACK,
                    LEXICAL_TITLE_FALLBACK);
        }
        return titleCalibration;
    }

    /**
     * Input: a candidate.
     * Output: its evidence, read once and remembered.
     * Used by: {@link #assess}.
     * Example: second call for the same candidate -> no PDF read, no embedding
     */
    private Evidence evidence(PaperCandidate candidate) {
        Evidence known = evidence.get(candidate);
        if (known == null) {
            known = read(candidate);
            evidence.putIfAbsent(candidate, known);
        }
        return known;
    }

    /**
     * Input: a candidate.
     * Output: its full-PDF evidence if the PDF is readable, else title + abstract, else title alone.
     * Used by: {@link #evidence}.
     * Example: no pdfPath, abstract "We study..." -> Evidence(ABSTRACT, embed(title, abstract), "title. abstract. ")
     */
    private Evidence read(PaperCandidate candidate) {
        Optional<Path> pdf = candidate.pdfPath().filter(Files::isRegularFile);
        if (pdf.isPresent()) {
            try {
                PaperText text = extractor.extract(pdf.get());
                // The candidate's own title (from the alert or Crossref) is more reliable than one read off the PDF.
                PaperText titled = new PaperText(candidate.title(), text.abstractText(), text.headings(), text.bodyChunks());
                return new Evidence(Basis.FULL_TEXT, embedder.embed(titled).combined(),
                        LexicalIndex.text(titled.title(), titled.abstractText(), titled.bodyText()));
            } catch (UnreadablePdfException e) {
                LOG.log(Level.FINE, "Scoring \"{0}\" without its PDF: {1}", new Object[] {candidate.title(), e.getMessage()});
            }
        }
        String abstractText = candidate.abstractSnippet().map(String::strip).orElse("");
        if (abstractText.isEmpty()) {
            return new Evidence(Basis.TITLE, provider.embed(candidate.title(), ""), candidate.title());
        }
        return new Evidence(Basis.ABSTRACT, provider.embed(candidate.title(), abstractText),
                LexicalIndex.text(candidate.title(), abstractText, ""));
    }

    /**
     * A profile paper's words as a candidate with this kind of evidence would show them.
     * Input: a profile paper and a kind of evidence.
     * Output: its words as a candidate with that evidence would show them.
     * Used by: {@link #calibrate}.
     * Example: (paper, TITLE) -> paper.title()
     */
    private static String lexicalText(ProfilePaper paper, Basis basis) {
        return switch (basis) {
            case FULL_TEXT -> LexicalIndex.documentText(paper);
            case ABSTRACT -> LexicalIndex.text(paper.title(), paper.abstractText().orElse(""), "");
            case TITLE -> paper.title();
        };
    }

    /**
     * Input: a profile paper and its similarity.
     * Output: the rationale's quote: title (shortened) and similarity to 3 places.
     * Used by: {@link #assess}.
     * Example: -> "\"Wave loads on piles\" (0.912)"
     */
    private static String quote(ProfilePaper paper, double similarity) {
        return String.format(Locale.ROOT, "\"%s\" (%.3f)", shorten(paper.title()), similarity);
    }

    /**
     * Input: a title.
     * Output: it, cut to {@value #RATIONALE_TITLE_CHARS} chars with an ellipsis if longer.
     * Used by: {@link #quote}.
     * Example: a 120-char title -> first 89 chars + "…"
     */
    private static String shorten(String title) {
        return title.length() <= RATIONALE_TITLE_CHARS ? title : title.substring(0, RATIONALE_TITLE_CHARS - 1).strip() + "…";
    }

    /** One held-out paper's similarity to each student it can be compared with, by each signal. */
    private record HeldOut(Set<String> owners, Map<String, Double> semantic, Map<String, Double> lexical) {
    }

    /**
     * Holds each distinct paper out of every profile that has it (with any copy of it) and compares
     * it, as a candidate with this kind of evidence would look, with what remains. The typical
     * similarity to its own student and to the others anchors each signal.
     * Input: a kind of evidence, how a profile paper looks as such a candidate, and fallback anchors.
     * Output: each signal's anchors: the median similarity of held-out papers to their own student and to the others.
     * Used by: the constructor (full text, abstract), titleCalibration.
     * Example: 8 reference profiles -> semantic Anchors(0.952, 0.911)
     */
    private Calibration calibrate(Basis basis, Function<ProfilePaper, float[]> asCandidate, Anchors semanticFallback,
            Anchors lexicalFallback) {
        List<ProfilePaper> distinct = new ArrayList<>();
        for (Index index : indexes) {
            for (ProfilePaper paper : index.fullText().papers) {
                if (distinct.stream().noneMatch(kept -> sameAs(kept, paper))) {
                    distinct.add(paper);
                }
            }
        }
        // Leave-one-out: compare each paper with every profile, minus itself where it is held.
        List<HeldOut> heldOut = distinct.parallelStream().map(paper -> {
            Set<String> owners = new HashSet<>();
            Map<String, Double> semantic = new HashMap<>();
            float[] vector = asCandidate.apply(paper);
            for (Index index : indexes) {
                boolean holds = index.holdsCopyOf(paper);
                if (holds) {
                    owners.add(index.profile().name());
                }
                Space rest = holds ? index.space(basis).without(paper) : index.space(basis);
                if (!rest.papers.isEmpty()) {
                    semantic.put(index.profile().name(), rest.closeness(vector).similarity());
                }
            }
            Map<String, Double> wording = new HashMap<>();
            if (lexical != null) {
                lexical.closeness(lexical.vector(lexicalText(paper, basis)), other -> sameAs(paper, other))
                        .forEach((name, closeness) -> wording.put(name, closeness.similarity()));
            }
            return new HeldOut(owners, semantic, wording);
        }).toList();

        Anchors semanticAnchors = anchors(heldOut, HeldOut::semantic, semanticFallback);
        Anchors lexicalAnchors = lexical == null ? lexicalFallback : anchors(heldOut, HeldOut::lexical, lexicalFallback);
        return new Calibration(semanticAnchors, lexicalAnchors);
    }

    /**
     * The median similarity of held-out papers to their own students and to the others.
     * Input: the held-out comparisons, which signal to read, and a fallback.
     * Output: Anchors(median own, median other); the fallback if either side is empty or own &lt;= other.
     * Used by: {@link #calibrate}.
     * Example: one student only (no "other" values) -> the fallback
     */
    private static Anchors anchors(List<HeldOut> heldOut, Function<HeldOut, Map<String, Double>> signal, Anchors fallback) {
        List<Double> own = new ArrayList<>();
        List<Double> other = new ArrayList<>();
        for (HeldOut paper : heldOut) {
            signal.apply(paper).forEach((name, similarity) -> (paper.owners().contains(name) ? own : other).add(similarity));
        }
        if (own.isEmpty() || other.isEmpty()) {
            return fallback;
        }
        Anchors measured = new Anchors(median(own), median(other));
        return measured.own() > measured.other() ? measured : fallback;
    }

    /**
     * Input: non-empty values.
     * Output: the upper median.
     * Used by: {@link #anchors}.
     * Example: [3, 1, 2, 4] -> 3
     */
    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /**
     * Whether two profile papers are the same paper: the same file, or one saved twice.
     * Input: two profile papers.
     * Output: true if same hash, or vectors at least {@value #SAME_PAPER} similar (one paper saved twice).
     * Used by: Index.of, Index.holdsCopyOf, Space.without, calibrate.
     * Example: a.pdf and a-copy.pdf (re-saved, different hash) -> true
     */
    private static boolean sameAs(ProfilePaper a, ProfilePaper b) {
        return a.sha256().equals(b.sha256()) || Vectors.cosine(a.embedding(), b.embedding()) >= SAME_PAPER;
    }

    private record Neighbor(ProfilePaper paper, double similarity) {
    }

    /** @param similarity the blend: the papers as a whole and the closest papers */
    private record Closeness(double similarity, List<Neighbor> closest) {
    }

    /**
     * One profile, ready to compare against: its papers' full vectors and their abstract views. A paper
     * saved twice in the folder counts once.
     */
    private record Index(Profile profile, Space fullText, Space abstracts) {

        /**
         * Input: a profile.
         * Output: its index, a paper saved twice counted once.
         * Used by: the constructor.
         * Example: alice with a.pdf and its copy -> Index with 1 paper
         */
        static Index of(Profile profile) {
            List<ProfilePaper> distinct = new ArrayList<>();
            for (ProfilePaper paper : profile.papers()) {
                if (distinct.stream().noneMatch(kept -> sameAs(kept, paper))) {
                    distinct.add(paper);
                }
            }
            return new Index(profile, new Space(distinct, true), new Space(distinct, false));
        }

        /**
         * Full vectors for a candidate read from its PDF; abstract views for one known by title (and abstract).
         * Input: what a candidate was scored from.
         * Output: full vectors for a PDF, abstract views otherwise.
         * Used by: assess, titleRelevance, calibrate.
         * Example: space(TITLE) -> abstracts
         */
        Space space(Basis basis) {
            return basis == Basis.FULL_TEXT ? fullText : abstracts;
        }

        /**
         * Input: a profile paper.
         * Output: true if this profile has it or a copy of it.
         * Used by: {@link RelevanceScorer#calibrate}.
         * Example: bob.holdsCopyOf(alice's paper also in bob/) -> true
         */
        boolean holdsCopyOf(ProfilePaper paper) {
            return fullText.papers.stream().anyMatch(kept -> sameAs(kept, paper));
        }
    }

    /** One profile's papers as vectors of one kind, and the profile as a whole: their mean. */
    private static final class Space {

        final List<ProfilePaper> papers;
        final List<float[]> vectors;
        /** Unit-length mean of the vectors; null when there are no papers. */
        final float[] centroid;
        private final boolean fullText;

        /**
         * Input: papers and which vectors to use (full or abstract view).
         * Output: the space, with its unit-length centroid (null if no papers).
         * Used by: Index.of, {@link #without}.
         * Example: {@code new Space(papers, true)}
         */
        Space(List<ProfilePaper> papers, boolean fullText) {
            this.papers = papers;
            this.fullText = fullText;
            this.vectors = papers.stream().map(paper -> fullText ? paper.embedding() : paper.abstractView()).toList();
            this.centroid = papers.isEmpty() ? null : Vectors.normalize(Vectors.mean(vectors));
        }

        /**
         * This space without the paper and any copy of it saved under another name.
         * Input: a paper to leave out.
         * Output: this space without it or any copy of it.
         * Used by: {@link RelevanceScorer#calibrate}.
         * Example: space of [a, b, c].without(b) -> [a, c]
         */
        Space without(ProfilePaper left) {
            return new Space(papers.stream().filter(paper -> !sameAs(paper, left)).toList(), fullText);
        }

        /**
         * Input: a candidate vector.
         * Output: 0.5 x cosine to the centroid + 0.5 x mean of the 3 closest, plus those 3; 0 if no papers.
         * Used by: assess, titleRelevance, calibrate.
         * Example: -> Closeness(0.93, [a 0.95, b 0.93, c 0.91])
         */
        Closeness closeness(float[] candidate) {
            if (papers.isEmpty()) {
                return new Closeness(0, List.of());
            }
            List<Neighbor> neighbors = new ArrayList<>();
            for (int i = 0; i < papers.size(); i++) {
                neighbors.add(new Neighbor(papers.get(i), Vectors.cosine(candidate, vectors.get(i))));
            }
            neighbors.sort(Comparator.comparingDouble(Neighbor::similarity).reversed());
            List<Neighbor> closest = List.copyOf(neighbors.subList(0, Math.min(CLOSEST_PAPERS, neighbors.size())));
            double closestMean = closest.stream().mapToDouble(Neighbor::similarity).average().orElse(0);
            double similarity = PROFILE_WEIGHT * Vectors.cosine(candidate, centroid) + (1 - PROFILE_WEIGHT) * closestMean;
            return new Closeness(similarity, closest);
        }
    }
}
