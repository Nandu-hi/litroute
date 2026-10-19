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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * M6: matches deduplicated papers (M4's output) to the students' profiles (M7d). Each paper goes to
 * the one student it suits best, and each student's best two or three papers of a batch are
 * recommended to the professor.
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
 * times share. A paper is assigned to its highest-scoring student; of each student's papers in a
 * batch, the best-scored research papers that clear a relevance floor are recommended.
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

    /** Papers recommended per student and batch, unless the caller asks for another number. */
    public static final int RECOMMENDED_PER_STUDENT = 3;
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
     * A paper is recommended only if its relevance reaches this: closer to the student's own papers
     * than to other students' papers (0.9 and 0.1 being typical of those). On the reference profiles,
     * 95% of a student's own papers, held out, reach 0.45 from a full PDF and 0.51 from a title and
     * abstract - about this floor - but only 0.25 from a title alone, and the SpringerOpen paper on
     * ceramic coatings the professor marked irrelevant reaches 0.29 from its title alone (its alert
     * carries no abstract). So the floor is this one midpoint for every kind of evidence.
     */
    static final double RELEVANCE_FLOOR = 0.5;
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
    /**
     * Alert sections that hold notes rather than research papers, as publishers label them (ASCE's
     * "Discussions and Closures", "Book Reviews", "Corrections", ...). Such items are matched and
     * queued like any paper, but never recommended.
     */
    private static final Pattern NON_RESEARCH_TYPE = Pattern.compile("(?i)^(?:book reviews?|discussions?(?: and closures?)?"
            + "|closures?|errat(?:a|um)|corrections?|corrigend(?:a|um)|retractions?(?: notes?)?|editorials?"
            + "|obituar(?:y|ies)|letters? to the editor|comments?(?: and repl(?:y|ies))?|repl(?:y|ies))$");
    /** The same notes recognized by their titles, for alerts that do not label their sections. */
    private static final Pattern NON_RESEARCH_TITLE = Pattern.compile("(?i)^\\s*(?:errat(?:a|um)\\b|corrigend(?:a|um)\\b"
            + "|correction(?:\\s+to\\b|\\s*:)|retraction\\b|discussion\\s+of\\b|closure\\s+to\\b|comment\\s+on\\b"
            + "|reply\\s+to\\b)");
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

        /** 0 at a typical other student's paper, 1 at a typical own paper. */
        double normalized(double similarity) {
            return (similarity - other) / (own - other);
        }

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

        ScoredPaper toScoredPaper(boolean recommended, String note) {
            return new ScoredPaper(candidate, profileName, score, explanation + note, ReviewStatus.PENDING, recommended);
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

    /** A scorer using both signals ({@link Strategy#HYBRID}). */
    public RelevanceScorer(EmbeddingProvider provider, Collection<Profile> profiles) {
        this(provider, profiles, Strategy.HYBRID);
    }

    /**
     * @param profiles every student's profile, as M7d holds them
     * @param strategy what {@link #match(List)}, {@link #scoreAll(PaperCandidate)} and
     *                 {@link #score} compare by
     * @throws IllegalArgumentException if a profile was built with a different model than {@code provider}
     *                                  (its vectors would not be comparable), or the strategy matches words
     *                                  and a profile was saved without its papers' text
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
     *
     * @throws IllegalArgumentException if the profile is not one this scorer was built with
     */
    public ScoredPaper score(PaperCandidate candidate, Profile profile) {
        return scoreAll(candidate).stream().filter(scored -> scored.profileName().equals(profile.name())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Profile " + profile.name()
                        + " is not one of the profiles this scorer compares with"));
    }

    /** Scores the candidate for every student, best match first. */
    public List<ScoredPaper> scoreAll(PaperCandidate candidate) {
        return scoreAll(candidate, strategy);
    }

    /** Scores the candidate for every student by the given strategy, best match first. */
    public List<ScoredPaper> scoreAll(PaperCandidate candidate, Strategy byStrategy) {
        return assess(candidate, byStrategy).stream().map(assessment -> assessment.toScoredPaper(false, "")).toList();
    }

    /**
     * Matches a batch of deduplicated papers (M4's output) to students, recommending up to
     * {@link #RECOMMENDED_PER_STUDENT} per student.
     *
     * @see #match(List, int, Strategy)
     */
    @Override
    public Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch) {
        return match(batch, RECOMMENDED_PER_STUDENT, strategy);
    }

    /** @see #match(List, int, Strategy) */
    public Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch, int perStudent) {
        return match(batch, perStudent, strategy);
    }

    /**
     * Matches a batch of deduplicated papers to students: exactly one {@link ScoredPaper} per candidate,
     * for the student it scores highest for. Of each student's papers, the {@code perStudent}
     * best-scored are recommended - provided they are research papers (not book reviews, discussions,
     * corrections and the like) whose relevance reaches {@link #RELEVANCE_FLOOR}. Every paper is
     * returned either way, so none disappears before the professor has seen it once; the rationale of
     * one that is not recommended says why.
     *
     * @return every student, in the order the profiles were given, mapped to the papers assigned to
     *         them, highest score first (see {@link PaperMatcher#match})
     */
    public Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch, int perStudent, Strategy byStrategy) {
        if (perStudent < 1) {
            throw new IllegalArgumentException("perStudent must be at least 1, was " + perStudent);
        }
        // Papers are independent and one with a PDF takes seconds to embed, so they are scored in parallel.
        List<Assessment> best = batch.parallelStream().map(candidate -> assess(candidate, byStrategy).get(0)).toList();
        String[] notes = new String[best.size()];
        boolean[] recommended = new boolean[best.size()];
        Map<String, List<Integer>> eligible = new LinkedHashMap<>();
        for (int i = 0; i < best.size(); i++) {
            Assessment assessment = best.get(i);
            if (!isResearch(assessment.candidate())) {
                notes[i] = " Not recommended: a book review, discussion, correction or similar note, not a research paper.";
            } else if (assessment.relevance() < RELEVANCE_FLOOR) {
                notes[i] = String.format(Locale.ROOT, " Not recommended: relevance %.2f is below %.2f - scored from %s, the "
                        + "paper looks no more like %s's papers than like other students' papers.", assessment.relevance(),
                        RELEVANCE_FLOOR, assessment.basis().label, assessment.profileName());
            } else {
                eligible.computeIfAbsent(assessment.profileName(), name -> new ArrayList<>()).add(i);
            }
        }
        eligible.forEach((name, papers) -> {
            papers.sort(Comparator.comparingDouble((Integer i) -> best.get(i).score()).reversed());
            for (int rank = 0; rank < papers.size(); rank++) {
                int i = papers.get(rank);
                if (rank < perStudent) {
                    recommended[i] = true;
                } else {
                    notes[i] = " Not recommended: " + name + " has " + perStudent + " better-scored papers in this batch.";
                }
            }
        });
        Map<String, List<ScoredPaper>> byStudent = new LinkedHashMap<>();
        indexes.forEach(index -> byStudent.put(index.profile().name(), new ArrayList<>()));
        for (int i = 0; i < best.size(); i++) {
            ScoredPaper scored = best.get(i).toScoredPaper(recommended[i], notes[i] == null ? "" : notes[i]);
            byStudent.get(scored.profileName()).add(scored);
        }
        byStudent.replaceAll((name, papers) -> papers.stream()
                .sorted(Comparator.comparingDouble(ScoredPaper::score).reversed()).toList());
        return byStudent;
    }

    /** Whether the candidate is a research paper rather than a note about one (a discussion, erratum, review of a book...). */
    static boolean isResearch(PaperCandidate candidate) {
        boolean noteSection = candidate.articleType().map(type -> NON_RESEARCH_TYPE.matcher(type.strip()).matches())
                .orElse(false);
        return !noteSection && !NON_RESEARCH_TITLE.matcher(candidate.title()).find();
    }

    /** The candidate scored for every student by the given strategy, best first. */
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

        double[] semanticSimilarity = new double[n];
        double[] lexicalSimilarity = new double[n];
        for (int i = 0; i < n; i++) {
            semanticSimilarity[i] = semantic.get(i).similarity();
            lexicalSimilarity[i] = byStrategy.lexical ? lexicalCloseness.get(names.get(i)).similarity() : 0;
        }
        double[] fit = fit(byStrategy, semanticSimilarity, lexicalSimilarity);
        double[] share = softmax(fit);
        double[] relevance = new double[n];
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            relevance[i] = relevance(byStrategy, calibration, semanticSimilarity[i], lexicalSimilarity[i]);
            score[i] = relevance[i] * share[i];
        }
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

    /** Each student's fit: the strategy's signals standardized across the students, averaged. */
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

    /** Standard scores; all zero when the values do not differ (a signal that cannot tell the students apart). */
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

    private static double relevance(Strategy byStrategy, Calibration calibration, double semantic, double lexical) {
        double normalized = 0;
        int signals = 0;
        if (byStrategy.semantic) {
            normalized += calibration.semantic().normalized(semantic);
            signals++;
        }
        if (byStrategy.lexical) {
            normalized += calibration.lexical().normalized(lexical);
            signals++;
        }
        return Anchors.relevance(normalized / signals);
    }

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

    private Evidence evidence(PaperCandidate candidate) {
        Evidence known = evidence.get(candidate);
        if (known == null) {
            known = read(candidate);
            evidence.putIfAbsent(candidate, known);
        }
        return known;
    }

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

    /** A profile paper's words as a candidate with this kind of evidence would show them. */
    private static String lexicalText(ProfilePaper paper, Basis basis) {
        return switch (basis) {
            case FULL_TEXT -> LexicalIndex.documentText(paper);
            case ABSTRACT -> LexicalIndex.text(paper.title(), paper.abstractText().orElse(""), "");
            case TITLE -> paper.title();
        };
    }

    private static String quote(ProfilePaper paper, double similarity) {
        return String.format(Locale.ROOT, "\"%s\" (%.3f)", shorten(paper.title()), similarity);
    }

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
     *
     * @param asCandidate the paper's SPECTER2 vector as such a candidate would carry it
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

    /** The median similarity of held-out papers to their own students and to the others. */
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

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** Whether two profile papers are the same paper: the same file, or one saved twice. */
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

        static Index of(Profile profile) {
            List<ProfilePaper> distinct = new ArrayList<>();
            for (ProfilePaper paper : profile.papers()) {
                if (distinct.stream().noneMatch(kept -> sameAs(kept, paper))) {
                    distinct.add(paper);
                }
            }
            return new Index(profile, new Space(distinct, true), new Space(distinct, false));
        }

        /** Full vectors for a candidate read from its PDF; abstract views for one known by title (and abstract). */
        Space space(Basis basis) {
            return basis == Basis.FULL_TEXT ? fullText : abstracts;
        }

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

        Space(List<ProfilePaper> papers, boolean fullText) {
            this.papers = papers;
            this.fullText = fullText;
            this.vectors = papers.stream().map(paper -> fullText ? paper.embedding() : paper.abstractView()).toList();
            this.centroid = papers.isEmpty() ? null : Vectors.normalize(Vectors.mean(vectors));
        }

        /** This space without the paper and any copy of it saved under another name. */
        Space without(ProfilePaper left) {
            return new Space(papers.stream().filter(paper -> !sameAs(paper, left)).toList(), fullText);
        }

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
