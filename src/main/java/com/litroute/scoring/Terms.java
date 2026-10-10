package com.litroute.scoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words M6 compares papers by: each content word, reduced to a stem so "hazards" and "hazard"
 * count as one, and each pair of adjacent content words ("rank aggregation", "functional recovery"),
 * which pins a field down more sharply than either word alone. Pure text handling with no policy of
 * its own, like {@link com.litroute.util.Titles}.
 *
 * <ul>
 *   <li>Accents and ligatures are folded ("Brasília" reads as "brasilia") and case is ignored.
 *   <li>A word is a run of letters; a hyphen inside it is kept ("multi-hazard"), anything else ends it.
 *   <li>Words shorter than three letters, English function words and the boilerplate every paper
 *       uses (figure, table, model, method, results, ...) are dropped. A dropped word separates its
 *       neighbours: no pair is formed across it.
 *   <li>Stems: -ies becomes -y, a plural -s is dropped (not from -ss, -us or -is), and -ing or -ed is
 *       dropped when at least four letters remain.
 * </ul>
 *
 * Measured on the students' reference papers (see {@link RelevanceScorer}): also dropping every
 * common English word, or keeping only the word pairs, matched papers worse - everyday words such as
 * "earthquake", "fire" and "flood" are exactly what several of the students' fields are about.
 */
final class Terms {

    /** Joins the two stems of a word pair into one term. */
    static final String PAIR = "_";

    private static final String STOPWORDS_TEXT = """
            a about above after again against all also am an and any are as at be because been before being below
            between both but by can could did do does doing down during each few for from further had has have having
            he her here hers herself him himself his how i if in into is it its itself just me more most my myself no
            nor not now of off on once only or other our ours ourselves out over own same she should so some such than
            that the their theirs them themselves then there these they this those through to too under until up very
            was we were what when where which while who whom why will with would you your yours yourself yourselves
            may might must shall within without upon via per thus therefore however hence although though whereas
            whether either neither one two three four five six seven eight nine ten first second third using used use
            based well many much several various new et al fig figs figure figures table tables eq eqs equation
            section chapter page pages vol volume pp doi http https www org com journal elsevier springer wiley taylor
            francis copyright rights reserved license licence creative commons article paper study research author
            authors corresponding email received accepted revised available online published press university
            department institute school college usa ltd inc shown show shows given obtain obtained result results
            respectively case cases different number value values data model models method methods approach analysis
            """;
    static final Set<String> STOPWORDS = Set.copyOf(Arrays.asList(STOPWORDS_TEXT.strip().split("\\s+")));

    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}-]*");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** Unicode hyphen and non-breaking hyphen, which PDFs use inside words like "multi‐hazard". */
    private static final Pattern HYPHENS = Pattern.compile("[\\u2010\\u2011]");
    /** Soft hyphens and zero-width characters, which split words without being visible. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B-\\u200D\\uFEFF]");
    private static final String[] SUFFIXES = {"ingly", "edly", "ing", "ed"};

    private Terms() {
    }

    /** One kept word: its stem, and the word as written (case and accents folded). */
    private record Word(String stem, String surface) {
    }

    /** How often each term occurs: every kept word's stem and every pair of adjacent kept words. */
    static Map<String, Integer> counts(String text) {
        Map<String, Integer> counts = new HashMap<>();
        List<Word> words = words(text);
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            if (word == null) {
                continue;
            }
            counts.merge(word.stem(), 1, Integer::sum);
            Word next = i + 1 < words.size() ? words.get(i + 1) : null;
            if (next != null && !next.stem().equals(word.stem())) {
                counts.merge(word.stem() + PAIR + next.stem(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** For each term in the text, the way it is most often written there ("rank aggregation"), for display. */
    static Map<String, String> surfaceForms(String text) {
        Map<String, Map<String, Integer>> written = new HashMap<>();
        List<Word> words = words(text);
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            if (word == null) {
                continue;
            }
            written.computeIfAbsent(word.stem(), t -> new LinkedHashMap<>()).merge(word.surface(), 1, Integer::sum);
            Word next = i + 1 < words.size() ? words.get(i + 1) : null;
            if (next != null && !next.stem().equals(word.stem())) {
                written.computeIfAbsent(word.stem() + PAIR + next.stem(), t -> new LinkedHashMap<>())
                        .merge(word.surface() + " " + next.surface(), 1, Integer::sum);
            }
        }
        Map<String, String> surfaces = new HashMap<>();
        written.forEach((term, forms) -> surfaces.put(term,
                forms.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey()));
        return surfaces;
    }

    /** The text's words in order, with null where a dropped word separates its neighbours. */
    private static List<Word> words(String text) {
        String folded = Normalizer.normalize(INVISIBLE.matcher(text).replaceAll(""), Normalizer.Form.NFKD);
        folded = HYPHENS.matcher(MARKS.matcher(folded).replaceAll("")).replaceAll("-").toLowerCase(Locale.ROOT);
        List<Word> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(folded);
        while (matcher.find()) {
            String word = trimHyphens(matcher.group());
            if (word.length() < 3 || STOPWORDS.contains(word)) {
                words.add(null);
            } else {
                words.add(new Word(stem(word), word));
            }
        }
        return words;
    }

    static String stem(String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("sses")) {
            return word.substring(0, word.length() - 2);
        }
        String stem = word;
        if (stem.length() > 3 && stem.endsWith("s") && !stem.endsWith("ss") && !stem.endsWith("us")
                && !stem.endsWith("is")) {
            stem = stem.substring(0, stem.length() - 1);
        }
        for (String suffix : SUFFIXES) {
            if (stem.length() - suffix.length() >= 4 && stem.endsWith(suffix)) {
                return stem.substring(0, stem.length() - suffix.length());
            }
        }
        return stem;
    }

    private static String trimHyphens(String word) {
        int end = word.length();
        while (end > 0 && word.charAt(end - 1) == '-') {
            end--;
        }
        return word.substring(0, end);
    }
}
