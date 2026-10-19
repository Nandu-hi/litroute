package com.litroute.scoring.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litroute.model.PaperCandidate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The prompt {@link AiPaperMatcher} sends and the shape the answer must take. Papers are labelled
 * {@code P1}..{@code Pn} in batch order and students go by their exact profile names; the schema restricts
 * the answer to those labels, so every match maps back to one paper and one student without guessing.
 */
final class MatchPrompt {

    /**
     * An abstract is cut to this many characters: its opening says what the paper does, and a batch of 31
     * papers for 8 students stays near 4,500 tokens, inside Groq's free 8,000 tokens a minute with the answer.
     */
    static final int ABSTRACT_CHARS = 300;
    static final int MAX_REASON_WORDS = 25;
    /**
     * Characters per token when estimating a prompt's length: English runs to about 4.5 (a prompt of
     * 15,916 characters was 3,512 tokens to Groq), so 4 over-estimates and a prompt sized by it fits.
     */
    static final int CHARS_PER_TOKEN = 4;

    private static final ObjectMapper JSON = new ObjectMapper();

    private MatchPrompt() {
    }

    /** The label of the paper at {@code index} (0-based) in the batch. */
    static String paperId(int index) {
        return "P" + (index + 1);
    }

    /** A generous estimate of how many tokens the text is. */
    static int estimateTokens(String text) {
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    static String text(List<ProfileBrief> profiles, List<PaperCandidate> papers) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                Route new research papers to students. For each student, list the papers relevant to their \
                research with a score 0-100 (90+ core topic, 60-89 clearly useful, 30-59 peripheral; omit \
                below 30). A paper may suit several students. Notes (book reviews, discussions, closures, \
                errata) score below 30 unless exactly on topic. For each student with matches, pick the one \
                paper to read first, with a reason of at most %d words. Use the names and ids below exactly; \
                answer for every student.

                """.formatted(MAX_REASON_WORDS));
        prompt.append("# Students\n");
        for (ProfileBrief profile : profiles) {
            prompt.append("\n## ").append(profile.name()).append('\n');
            if (!profile.keywords().isEmpty()) {
                prompt.append("Keywords: ").append(String.join("; ", profile.keywords())).append('\n');
            }
            prompt.append("Typical papers:\n");
            profile.paperTitles().forEach(title -> prompt.append("- ").append(oneLine(title)).append('\n'));
        }
        prompt.append("\n# New papers\n");
        for (int i = 0; i < papers.size(); i++) {
            PaperCandidate paper = papers.get(i);
            prompt.append("\n## ").append(paperId(i)).append('\n');
            prompt.append("Title: ").append(oneLine(paper.title())).append('\n');
            paper.venue().ifPresent(venue -> prompt.append("Venue: ").append(oneLine(venue))
                    .append(paper.year().map(year -> " (" + year + ")").orElse("")).append('\n'));
            paper.articleType().ifPresent(type -> prompt.append("Type: ").append(oneLine(type)).append('\n'));
            paper.abstractSnippet().ifPresent(text -> prompt.append("Abstract: ").append(cut(oneLine(text))).append('\n'));
        }
        return prompt.toString();
    }

    /**
     * <pre>{@code
     * {"profiles": [{"profile": "RP1-mh-risk",
     *                "matches": [{"paper_id": "P3", "score": 85}, ...],
     *                "focus": {"paper_id": "P3", "reason": "..."}}, ...]}
     * }</pre>
     */
    static ObjectNode schema(List<ProfileBrief> profiles, int paperCount) {
        ObjectNode root = object();
        ObjectNode student = object();
        enumOf(properties(student).putObject("profile"), profiles.stream().map(ProfileBrief::name).toList());

        ObjectNode match = object();
        enumOf(properties(match).putObject("paper_id"), paperIds(paperCount));
        properties(match).putObject("score").put("type", "integer").put("minimum", 0).put("maximum", 100);
        required(match, "paper_id", "score");
        ObjectNode matches = properties(student).putObject("matches").put("type", "array");
        matches.set("items", match);

        ObjectNode focus = object();
        properties(student).set("focus", focus);
        enumOf(properties(focus).putObject("paper_id"), paperIds(paperCount));
        properties(focus).putObject("reason").put("type", "string")
                .put("description", "Why the student should read this paper first: one sentence.");
        required(focus, "paper_id", "reason");
        required(student, "profile", "matches");

        ObjectNode list = properties(root).putObject("profiles").put("type", "array");
        list.set("items", student);
        required(root, "profiles");
        return root;
    }

    private static List<String> paperIds(int count) {
        return IntStream.range(0, count).mapToObj(MatchPrompt::paperId).toList();
    }

    private static ObjectNode object() {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "object");
        node.putObject("properties");
        return node;
    }

    private static ObjectNode properties(ObjectNode object) {
        return (ObjectNode) object.get("properties");
    }

    private static void enumOf(ObjectNode node, List<String> values) {
        node.put("type", "string");
        ArrayNode allowed = node.putArray("enum");
        values.forEach(allowed::add);
    }

    private static void required(ObjectNode node, String... fields) {
        ArrayNode list = node.putArray("required");
        for (String field : fields) {
            list.add(field);
        }
    }

    static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String cut(String text) {
        return text.length() <= ABSTRACT_CHARS ? text : text.substring(0, ABSTRACT_CHARS).strip() + "...";
    }
}
