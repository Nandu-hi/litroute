package com.litroute.ingestion.extract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litroute.ingestion.extract.AlertLines.Line;
import com.litroute.ingestion.extract.AlertLines.Seg;
import com.litroute.ingestion.extract.LinkUnwrapper.Link;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.util.Dois;
import com.litroute.util.Titles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * {@link ExtractionMode#LLM}: asks the local language model for the paper titles in an alert email, and keeps
 * only what checks out.
 *
 * The email goes as simplified XML ({@link AlertLines#toXml}), split into slices a small model can hold in
 * mind: on the client's 22-entry ASCE alert, a single request made llama3.2:3b repeat itself until the budget
 * ran out. A model can still miss a title or invent one, so its answer is never taken on trust:
 *
 * <ul>
 *   <li>The answer must be complete and be exactly {@code {"titles": [strings]}}. One cut short at the token
 *       budget has its slice asked again in two halves; a malformed one is asked again with another seed.
 *   <li>Each title must be a whole link or run of text in the email, and it is the email's own text that is kept,
 *       never the model's copy. An invented title has nothing to match, and "Advances in Landslide Detection" does
 *       not match the line "Erratum for “Advances in Landslide Detection”".
 *   <li>A line that plainly isn't a title (footer text, a list of names, a section label) is refused.
 * </ul>
 *
 * Example:
 * <pre>{@code
 * Email lines:  [a] "Wave loads on floating offshore wind turbines" / "A. Smith, B. Jones" / [a] "Unsubscribe here"
 * Model answer: {"titles": ["Wave loads on floating offshore wind turbines", "A. Smith, B. Jones", "Tidal power"]}
 * Output:       ["Wave loads on floating offshore wind turbines"]   ("A. Smith, B. Jones" is names, "Tidal power" isn't there)
 * }</pre>
 */
public final class AiTitleExtractor {

    /** A slice's most characters of email: long enough for 5-10 papers, short enough not to make the model loop. */
    static final int MAX_SLICE_CHARS = 3200;
    /** Lines each slice repeats from the one before, so a title cut off from its author line is still seen with it. */
    private static final int OVERLAP_LINES = 2;
    private static final int SEED = 42;
    /** A title is copied from the slice, so its answer can't honestly be longer than the slice is. */
    private static final int CHARS_PER_ANSWER_TOKEN = 3;
    private static final int ANSWER_OVERHEAD_TOKENS = 64;

    /** The XML this class adds, should the model copy it along with a title. */
    private static final Pattern MARKUP = Pattern.compile("</?(?:a|h|p|hr)\\s*/?>");
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectNode SCHEMA = schema();
    private static final Logger LOG = Logger.getLogger(AiTitleExtractor.class.getName());

    /** A title as the email writes it, with its link if the line is one. */
    record EmailTitle(String text, Optional<String> href) {
    }

    /**
     * What the model found in an email.
     *
     * @param known   papers the parser read too, with all the details it read
     * @param unknown titles only the model named: a title and link, which enrichment must confirm with a DOI
     */
    record ModelPapers(List<ParsedPaper> known, List<ParsedPaper> unknown) {
    }

    /** The model answered, but no answer about part of an email could be used. */
    static final class UnusableAnswerException extends IOException {
        private static final long serialVersionUID = 1L;

        /**
         * Input: what went wrong.
         * Output: the exception.
         * Used by: {@link AiTitleExtractor#ask}.
         * Example: {@code new UnusableAnswerException("llama3.2:3b gave no usable answer about part of an email.")}
         */
        UnusableAnswerException(String message) {
            super(message);
        }
    }

    private final PromptGenerator prompts;
    private final OllamaClient model;

    /**
     * Input: the prompt generator and the model.
     * Output: an extractor using them.
     * Used by: {@link #fromEnvironment}; tests with recorded replies.
     * Example: {@code new AiTitleExtractor(PromptGenerator.fromResource(), recordedClient)}
     */
    AiTitleExtractor(PromptGenerator prompts, OllamaClient model) {
        this.prompts = prompts;
        this.model = model;
    }

    /**
     * Input: none (reads prompts.txt and the environment).
     * Output: the extractor over Ollama on this machine; throws if prompts.txt is unusable or the address isn't local.
     * Used by: PipelineRunner.atDefaultLocations (M9).
     * Example: {@code new PaperExtractor(enricher, AiTitleExtractor.fromEnvironment())}
     */
    public static AiTitleExtractor fromEnvironment() {
        return new AiTitleExtractor(PromptGenerator.fromResource(), OllamaClient.fromEnvironment());
    }

    /**
     * Input: none.
     * Output: the JSON Schema of an answer: an object holding only the list of titles.
     * Used by: {@link #SCHEMA}.
     * Example: -> {"type":"object","properties":{"titles":{"type":"array","items":{"type":"string"}}},"required":["titles"],...}
     */
    private static ObjectNode schema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode titles = schema.putObject("properties").putObject("titles");
        titles.put("type", "array");
        titles.putObject("items").put("type", "string");
        schema.putArray("required").add("titles");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * The papers of one email, in {@link ExtractionMode#LLM}: which ones is the model's call; what the parser read
     * only supplies their details, and keeps out the notes it recognizes by their section.
     * Input: an alert email and what the HTML parser read from it, notes included.
     * Output: the model's papers; the parser's own if the model's answer about this email was unusable, so
     * the email isn't lost; IOException if the model can't be reached.
     * Used by: PaperExtractor.extract (M2).
     * Example: ASCE alert, model names 17 papers + a book review -> 17 parsed papers, with authors and links; no book review
     */
    ModelPapers papersIn(RawEmail email, List<ParsedPaper> parsed) throws IOException {
        List<EmailTitle> titles;
        try {
            titles = verifiedTitles(email);
        } catch (UnusableAnswerException e) {
            LOG.warning(e.getMessage() + " Using pattern matching for \"" + email.subject() + "\".");
            return new ModelPapers(AlertHtmlParser.research(parsed), List.of());
        }
        Map<String, ParsedPaper> blocks = new LinkedHashMap<>();
        Set<String> venues = new HashSet<>();
        for (ParsedPaper paper : parsed) {
            blocks.putIfAbsent(Titles.compact(paper.candidate().title()), paper);
            paper.candidate().venue().map(Titles::compact).ifPresent(venues::add);
        }
        List<ParsedPaper> known = new ArrayList<>();
        List<ParsedPaper> unknown = new ArrayList<>();
        for (EmailTitle title : titles) {
            String key = Titles.compact(title.text());
            ParsedPaper block = blocks.get(key);
            if (block != null) {
                known.add(block);
            } else if (!venues.contains(key)) {
                unknown.add(paperFrom(email, title));
            }
        }
        known = AlertHtmlParser.research(known);
        unknown = AlertHtmlParser.research(unknown);
        List<String> missed = AlertHtmlParser.research(parsed).stream().filter(paper -> titles.stream()
                .noneMatch(title -> Titles.compact(title.text()).equals(Titles.compact(paper.candidate().title()))))
                .map(paper -> paper.candidate().title()).toList();
        LOG.info(model.modelId() + " found " + (known.size() + unknown.size()) + " paper(s) in \"" + email.subject() + "\"."
                + (missed.isEmpty() ? "" : " Pattern matching would also have found: " + missed));
        return new ModelPapers(known, unknown);
    }

    /**
     * Input: an alert email (only its HTML body is read).
     * Output: the titles the model named that the email really states, once each, in the model's order; [] without HTML.
     * Used by: {@link #papersIn}; AiTitleExtractorTest.
     * Example: see the class example.
     */
    List<EmailTitle> verifiedTitles(RawEmail email) throws IOException {
        if (email.htmlBody().isEmpty()) {
            return List.of();
        }
        List<Line> lines = AlertLines.parse(email.htmlBody().get());
        Map<String, EmailTitle> inEmail = candidateTitles(lines);
        Map<String, EmailTitle> verified = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (List<String> slice : slices(AlertLines.toXml(lines))) {
            for (String answered : ask(slice)) {
                String key = Titles.compact(AlertHtmlParser.withoutTag(cleaned(answered)));
                EmailTitle title = inEmail.get(key);
                if (title != null) {
                    verified.putIfAbsent(key, title);
                } else {
                    refused.add(answered);
                }
            }
        }
        if (!refused.isEmpty()) {
            LOG.info(model.modelId() + " named " + refused.size() + " title(s) that are not in \"" + email.subject()
                    + "\", ignored: " + refused);
        }
        return List.copyOf(verified.values());
    }

    /**
     * Everything in the email the model's answer may match in full: each link and each run of plain text that could
     * be a title, by its compacted text.
     * Input: the email's lines.
     * Output: compacted text -> the email's own text, without any "[PDF]" tag, and its link.
     * Used by: {@link #verifiedTitles}.
     * Example: [a] "[PDF] Wave loads on piles" -> {"waveloadsonpiles" -> EmailTitle("Wave loads on piles", its href)};
     * "Unsubscribe here", "A. Smith, B. Jones", a mailto: link -> no entry
     */
    private static Map<String, EmailTitle> candidateTitles(List<Line> lines) {
        Map<String, EmailTitle> titles = new LinkedHashMap<>();
        for (Line line : lines) {
            for (Seg seg : line.segs()) {
                String title = AlertHtmlParser.withoutTag(AlertLines.normalize(seg.text()));
                String key = Titles.compact(title);
                boolean usable = seg.href() == null || AlertHtmlParser.isWebLink(seg.href());
                if (usable && !key.isEmpty() && AlertHtmlParser.readsAsTitle(title)) {
                    titles.putIfAbsent(key, new EmailTitle(title, Optional.ofNullable(seg.href())));
                }
            }
        }
        return titles;
    }

    /**
     * Cuts the email into slices in reading order, each repeating the last {@value #OVERLAP_LINES} lines of the one before.
     * Input: the email's XML lines.
     * Output: slices of at most about {@value #MAX_SLICE_CHARS} characters; one slice if it all fits, [] if there are no lines.
     * Used by: {@link #verifiedTitles}.
     * Example: 6,300 characters of ASCE alert -> 3 slices; 1,300 characters of SpringerOpen alert -> 1 slice
     */
    static List<List<String>> slices(List<String> xml) {
        List<List<String>> slices = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int chars = 0;
        for (String line : xml) {
            if (chars + line.length() > MAX_SLICE_CHARS && !current.isEmpty()) {
                slices.add(current);
                current = new ArrayList<>(current.subList(Math.max(0, current.size() - OVERLAP_LINES), current.size()));
                chars = current.stream().mapToInt(kept -> kept.length() + 1).sum();
            }
            current.add(line);
            chars += line.length() + 1;
        }
        if (!current.isEmpty()) {
            slices.add(current);
        }
        return slices;
    }

    /**
     * Asks the model about one slice, as the class comment describes.
     * Input: the slice's XML lines.
     * Output: the titles it answered, unchecked against the email; UnusableAnswerException if no usable answer came; other IOException if unreachable.
     * Used by: {@link #verifiedTitles}, and itself on each half of a slice whose answer was cut short.
     * Example: answer cut at the budget -> asks about each half of the lines and returns both answers
     */
    private List<String> ask(List<String> slice) throws IOException {
        String body = String.join("\n", slice);
        int budget = body.length() / CHARS_PER_ANSWER_TOKEN + ANSWER_OVERHEAD_TOKENS;
        for (int seed = SEED; seed < SEED + 2; seed++) {
            OllamaClient.Answer answer = model.generateJson(prompts.generate(body), SCHEMA, budget, seed);
            if (!answer.complete()) {
                if (slice.size() < 2) {
                    break;
                }
                LOG.info(model.modelId() + " ran past its answer budget; asking again about " + slice.size() / 2
                        + " lines at a time.");
                List<String> titles = new ArrayList<>(ask(slice.subList(0, slice.size() / 2)));
                titles.addAll(ask(slice.subList(slice.size() / 2, slice.size())));
                return titles;
            }
            Optional<List<String>> titles = titlesIn(answer.text());
            if (titles.isPresent()) {
                return titles.get();
            }
            LOG.warning(model.modelId() + " answered in the wrong format: " + answer.text());
        }
        throw new UnusableAnswerException(model.modelId() + " gave no usable answer about part of an email.");
    }

    /**
     * Input: the model's text.
     * Output: its titles, if it is exactly one JSON object with only a "titles" list of strings; else empty.
     * Used by: {@link #ask}.
     * Example: {"titles":["A","B"]} -> [A, B]; {"titles":"A"}, {"papers":[]}, {"titles":["A"]} trailing text -> empty
     */
    static Optional<List<String>> titlesIn(String answer) {
        JsonNode root;
        try {
            root = JSON.readTree(answer);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("titles").isArray()) {
            return Optional.empty();
        }
        List<String> titles = new ArrayList<>();
        for (JsonNode title : root.path("titles")) {
            if (!title.isTextual()) {
                return Optional.empty();
            }
            titles.add(title.asText());
        }
        return Optional.of(titles);
    }

    /**
     * Input: a title as the model wrote it.
     * Output: it without the XML the email was sent in: the tags dropped, the escaped angle brackets restored.
     * Used by: {@link #verifiedTitles}.
     * Example: "<a>Soil creep at p&lt;0.05</a>" -> "Soil creep at p<0.05"
     */
    private static String cleaned(String answered) {
        return MARKUP.matcher(answered).replaceAll("").replace("&lt;", "<").replace("&gt;", ">");
    }

    /**
     * Input: the email and a verified title.
     * Output: the title as a paper: its link resolved and any DOI in it read, everything else left to enrichment.
     * Used by: {@link #papersIn}.
     * Example: "Wave loads on piles" linked to https://doi.org/10.1/x -> READABLE, doi 10.1/x, url https://doi.org/10.1/x
     */
    private static ParsedPaper paperFrom(RawEmail email, EmailTitle title) {
        Link link = LinkUnwrapper.resolve(title.href().orElse(null));
        Optional<String> doi = link.url().flatMap(Dois::find).or(() -> title.href().flatMap(Dois::find));
        return new ParsedPaper(new PaperCandidate(email.messageId(), title.text(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), doi, link.url(), Optional.empty(), Optional.empty()), link.kind());
    }
}
