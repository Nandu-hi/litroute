package com.litroute.scoring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.litroute.model.PaperCandidate;
import com.litroute.model.Profile;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.scoring.PaperMatcher;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * M6 by a language model: every student's profile and the whole batch go to the model in one prompt
 * ({@link MatchPrompt}), and it answers, for each student, which papers are relevant, with a score from 0
 * to 100, and the one paper the student should focus on, with a one-line reason.
 *
 * Unlike {@code RelevanceScorer}, a paper may be matched to several students. Each match becomes a
 * {@link ScoredPaper}: the score divided by 100; recommended only for the focus paper, whose rationale is
 * the model's reason; {@code PENDING} for the professor's review.
 *
 * A batch whose prompt would not fit the model's token window ({@link LlmClient#maxPromptTokens}) is
 * sent in several requests: as few as fit, of near-equal runs of papers in batch order, each prompt
 * naming every student. Each request picks a focus paper per student; the student keeps the best-scored
 * of them, and the others keep the model's reason but are not recommended.
 *
 * The answer is checked rather than trusted: a student or paper id the prompt did not contain is dropped
 * and logged, a paper listed twice for a student keeps its higher score, and a focus paper that is not
 * among the student's matches gives way to the student's best-scored match.
 */
public final class AiPaperMatcher implements PaperMatcher {

    private static final Logger LOG = Logger.getLogger(AiPaperMatcher.class.getName());

    private final LlmClient model;
    private final List<ProfileBrief> profiles;

    /**
     * @param model    the language model to ask
     * @param profiles every student, as the model should see them; names must be distinct
     */
    public AiPaperMatcher(LlmClient model, List<ProfileBrief> profiles) {
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("at least one profile is needed");
        }
        Set<String> names = new HashSet<>();
        for (ProfileBrief profile : profiles) {
            if (!names.add(profile.name())) {
                throw new IllegalArgumentException("two profiles are named " + profile.name());
            }
        }
        this.model = model;
        this.profiles = List.copyOf(profiles);
    }

    /** A matcher for the stored profiles, described by their keywords and their papers' titles. */
    public static AiPaperMatcher of(LlmClient model, Collection<Profile> profiles) {
        return new AiPaperMatcher(model, profiles.stream().map(ProfileBrief::from).toList());
    }

    /** The students, as the model is told about them. */
    public List<ProfileBrief> profiles() {
        return profiles;
    }

    /**
     * @throws IOException if the model could not be asked or its answer is not of the requested shape
     */
    @Override
    public Map<String, List<ScoredPaper>> match(List<PaperCandidate> batch) throws IOException {
        Map<String, List<ScoredPaper>> byStudent = new LinkedHashMap<>();
        profiles.forEach(profile -> byStudent.put(profile.name(), List.of()));
        if (batch.isEmpty()) {
            return byStudent;
        }
        List<List<PaperCandidate>> requests = requests(batch);
        if (requests.size() > 1) {
            LOG.info("Asking " + model.modelId() + " about " + batch.size() + " papers in " + requests.size()
                    + " requests, each within " + model.maxPromptTokens() + " prompt tokens");
        }
        // Per student, batch index -> score, and batch index -> reason for each paper picked as focus.
        Map<String, Map<Integer, Integer>> scores = new LinkedHashMap<>();
        Map<String, Map<Integer, String>> focus = new LinkedHashMap<>();
        int first = 0;
        for (List<PaperCandidate> papers : requests) {
            ask(papers, first, byStudent.keySet(), scores, focus);
            first += papers.size();
        }
        scores.forEach((name, own) -> byStudent.put(name, scored(name, own, focus.getOrDefault(name, Map.of()), batch)));
        return byStudent;
    }

    /**
     * The batch as the requests it is sent in: as few as keep every prompt within the model's token
     * window, splitting the batch into runs of near-equal length in batch order. A paper whose prompt is
     * too long even alone is sent alone, for the API to judge.
     */
    List<List<PaperCandidate>> requests(List<PaperCandidate> batch) {
        int budget = model.maxPromptTokens();
        int whole = MatchPrompt.estimateTokens(MatchPrompt.text(profiles, batch));
        for (int count = Math.max(1, Math.min(batch.size(), (whole + budget - 1) / budget)); count <= batch.size(); count++) {
            List<List<PaperCandidate>> split = split(batch, count);
            if (split.stream().allMatch(papers -> MatchPrompt.estimateTokens(MatchPrompt.text(profiles, papers)) <= budget)) {
                return split;
            }
        }
        LOG.warning("A prompt about a single paper exceeds " + model.modelId() + "'s " + budget
                + " prompt tokens; sending one paper per request anyway");
        return split(batch, batch.size());
    }

    /** The batch cut into {@code count} runs in order, whose lengths differ by at most one. */
    private static List<List<PaperCandidate>> split(List<PaperCandidate> batch, int count) {
        List<List<PaperCandidate>> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            runs.add(batch.subList(i * batch.size() / count, (i + 1) * batch.size() / count));
        }
        return runs;
    }

    /**
     * Asks the model about one run of the batch, which starts at batch index {@code first}, and adds what it
     * answers to {@code scores} and {@code focus}, by batch index.
     */
    private void ask(List<PaperCandidate> papers, int first, Set<String> names, Map<String, Map<Integer, Integer>> scores,
            Map<String, Map<Integer, String>> focus) throws IOException {
        JsonNode answer = model.generateJson(MatchPrompt.text(profiles, papers), MatchPrompt.schema(profiles, papers.size()));
        JsonNode students = answer.path("profiles");
        if (!students.isArray()) {
            throw new IOException(model.modelId() + " answered without a list of profiles: " + abbreviate(answer));
        }
        for (JsonNode student : students) {
            String name = student.path("profile").asText("");
            if (!names.contains(name)) {
                LOG.warning(model.modelId() + " answered for an unknown profile, ignored: \"" + name + "\"");
                continue;
            }
            Map<Integer, Integer> own = scores.computeIfAbsent(name, n -> new LinkedHashMap<>());
            for (JsonNode match : student.path("matches")) {
                Optional<Integer> paper = paperIndex(match.path("paper_id").asText(""), papers.size(), name);
                if (paper.isPresent()) {
                    int score = Math.max(0, Math.min(100, match.path("score").asInt(0)));
                    own.merge(first + paper.get(), score, Math::max);
                }
            }
            JsonNode pick = student.path("focus");
            if (pick.isObject()) {
                paperIndex(pick.path("paper_id").asText(""), papers.size(), name).ifPresent(paper ->
                        focus.computeIfAbsent(name, n -> new LinkedHashMap<>())
                                .put(first + paper, MatchPrompt.oneLine(pick.path("reason").asText(""))));
            }
        }
    }

    /**
     * One student's matches, best first. Recommended: the best-scored of the papers the model picked as
     * the student's focus (one per request), or, if it matched none of them, the student's best match.
     */
    private List<ScoredPaper> scored(String name, Map<Integer, Integer> own, Map<Integer, String> focus,
            List<PaperCandidate> batch) {
        List<Integer> ranked = new ArrayList<>(own.keySet());
        ranked.sort(Comparator.comparing(own::get).reversed()); // stable: ties stay in batch order
        if (ranked.isEmpty()) {
            return List.of();
        }
        focus.keySet().stream().filter(paper -> !own.containsKey(paper)).forEach(paper ->
                LOG.warning(model.modelId() + " picked \"" + batch.get(paper).title() + "\" as " + name
                        + "'s focus without matching it; that pick is ignored"));
        int chosen = ranked.stream().filter(focus::containsKey).findFirst().orElse(ranked.get(0));
        boolean modelChose = focus.containsKey(chosen);
        List<ScoredPaper> papers = new ArrayList<>();
        for (int paper : ranked) {
            int score = own.get(paper);
            boolean isFocus = paper == chosen;
            String reason = focus.getOrDefault(paper, "");
            String rationale = !reason.isEmpty() ? reason
                    : String.format(Locale.ROOT, "%s scored this %d/100 for %s%s.", model.modelId(), score, name,
                            isFocus && !modelChose ? ", the highest of its matches" : "");
            papers.add(new ScoredPaper(batch.get(paper), name, score / 100.0, rationale, ReviewStatus.PENDING, isFocus));
        }
        return List.copyOf(papers);
    }

    /** The index a paper id refers to in a request of {@code size} papers; empty (and logged) for an id it did not contain. */
    private Optional<Integer> paperIndex(String id, int size, String student) {
        if (id.startsWith("P")) {
            try {
                int index = Integer.parseInt(id.substring(1)) - 1;
                if (index >= 0 && index < size) {
                    return Optional.of(index);
                }
            } catch (NumberFormatException e) {
                // reported below
            }
        }
        LOG.warning(model.modelId() + " answered with an unknown paper id for " + student + ", ignored: \"" + id + "\"");
        return Optional.empty();
    }

    private static String abbreviate(JsonNode node) {
        String text = node.toString();
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

}
