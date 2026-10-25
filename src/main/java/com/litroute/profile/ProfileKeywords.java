package com.litroute.profile;

import com.litroute.model.Profile.ProfilePaper;
import com.litroute.util.Titles;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a student works on, in a few words, for M6's language model: the author keywords of the student's
 * reference papers, those that run through the student's work first.
 *
 * A keyword ranks by how many of the student's papers list it, then by how many are about it - mention it
 * in their title, abstract or keywords - so "multi-hazard", listed by five papers, comes before
 * "magneto-rheological damper", listed by one, and among keywords one paper each lists, "fragility
 * analysis", which a dozen abstracts mention, comes before it too. Keywords written differently - case,
 * hyphens, spaces, accents, word endings ("rank aggregation", "Ranking aggregations") - count as one,
 * written as the first paper to list it wrote it. Two PDFs of one paper (the same title) count once.
 *
 * <p>Example (four papers of a multi-hazard student, keywords only):
 *
 * <pre>
 * Input:  "Multi-hazard; Fragility; Tall buildings", "multihazard; earthquake; wind load",
 *         "Multi-hazards; Copula", "Fragility analysis; Wind loads"
 * Output: [Multi-hazard, wind load, Fragility, Tall buildings, earthquake, Copula, Fragility analysis]
 *         (Multi-hazard: listed 3 times; wind load: 2; Fragility: 1, mentioned by 2 papers; the rest:
 *         1 and 1, in the order they were first listed)
 * </pre>
 */
public final class ProfileKeywords {

    /** Keywords per student: enough to say what the student works on, few enough to keep the prompt short. */
    public static final int MAX_KEYWORDS = 10;
    /** Keywords this short ("fire", "GIS") are matched as whole words, so "fire" is not found in "wildfire". */
    private static final int WHOLE_WORD_CHARS = 5;
    private static final String[] SUFFIXES = {"ing", "ed"};

    private ProfileKeywords() {
    }

    /** One keyword, however its papers spell it. */
    private static final class Keyword {

        final String written;
        final String key;
        int lists;
        int mentions;

        /**
         * Input: the keyword as first written, and its stem key.
         * Output: a keyword with zero lists and mentions.
         * Used by: {@link #of}.
         * Example: {@code new Keyword("Multi-hazard", "multihazard")}
         */
        Keyword(String written, String key) {
            this.written = written;
            this.key = key;
        }
    }

    /**
     * Input: a profile's papers (duplicate titles counted once).
     * Output: at most {@value #MAX_KEYWORDS} keywords, most listed first, then most mentioned; [] if no paper lists any.
     * Used by: ProfileBuilder.sync (M5), stored on the Profile for M6's AI prompt.
     * Example: see the class example: 4 multi-hazard papers -> [Multi-hazard, wind load, Fragility, ...]
     */
    public static List<String> of(List<ProfilePaper> allPapers) {
        Set<String> titles = new HashSet<>();
        List<ProfilePaper> papers = allPapers.stream().filter(paper -> titles.add(Titles.compact(paper.title()))).toList();
        // Count how many papers list each keyword (once per paper).
        Map<String, Keyword> byKey = new LinkedHashMap<>();
        for (ProfilePaper paper : papers) {
            Set<String> listed = new HashSet<>();
            for (String written : paper.keywords().orElse(List.of())) {
                String key = key(written);
                if (!key.isEmpty() && listed.add(key)) {
                    byKey.computeIfAbsent(key, k -> new Keyword(written.strip(), k)).lists++;
                }
            }
        }
        // Count how many papers are about each keyword: title, abstract or keywords mention it.
        for (ProfilePaper paper : papers) {
            String about = String.join(" ", paper.title(), paper.abstractText().orElse(""),
                    String.join(" ", paper.keywords().orElse(List.of())));
            List<String> stems = stems(about);
            String compact = String.join("", stems);
            String spaced = " " + String.join(" ", stems) + " ";
            for (Keyword keyword : byKey.values()) {
                if (mentions(keyword, compact, spaced)) {
                    keyword.mentions++;
                }
            }
        }
        List<Keyword> ranked = new ArrayList<>(byKey.values());
        ranked.sort(Comparator.comparingInt((Keyword keyword) -> keyword.lists).reversed()
                .thenComparing(Comparator.comparingInt((Keyword keyword) -> keyword.mentions).reversed()));
        return ranked.stream().limit(MAX_KEYWORDS).map(keyword -> keyword.written).toList();
    }

    /**
     * Whether a text, given as its word stems run together and space-separated, mentions the keyword. Run
     * together, "multi-hazard" is found in "multihazard risk"; a short keyword must be whole words.
     * Input: a keyword, and a text's stems run together and space-separated.
     * Output: whether the text mentions it (short keywords must be whole words).
     * Used by: {@link #of}.
     * Example: "multi-hazard" in compact "multihazardrisk" -> true; "fire" in spaced " wildfire " -> false
     */
    private static boolean mentions(Keyword keyword, String compact, String spaced) {
        if (keyword.key.length() > WHOLE_WORD_CHARS) {
            return compact.contains(keyword.key);
        }
        return spaced.contains(" " + String.join(" ", stems(keyword.written)) + " ");
    }

    /**
     * The keyword's word stems run together, so "Multi-hazards" and "multihazard" are one keyword, and so
     * are "reduced-order models" and "Reduced order modeling". "Fragility" and "fragility analysis" stay two.
     * Input: a keyword as written.
     * Output: its word stems run together: the identity keywords are merged by.
     * Used by: {@link #of}; ProfileKeywordsTest.
     * Example: "Reduced order modeling" -> "reducedordermodel"; "Multi-hazards" -> "multihazard"
     */
    static String key(String keyword) {
        return String.join("", stems(keyword));
    }

    /**
     * The text's words, accents and case folded, each less a plural "s" (not from "-ss", "-is" or "-us")
     * and then "-ing" or "-ed" where four letters remain ("modelling" and "modeling" both become "model").
     * Input: any text.
     * Output: its words with accents and case folded, less a plural s and then -ing/-ed.
     * Used by: {@link #of}, {@link #mentions}, {@link #key}.
     * Example: "Modelling Wind Loads" -> [model, wind, load]
     */
    private static List<String> stems(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> stems = new ArrayList<>();
        for (String word : folded.split("[^a-z0-9]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("is")
                    && !word.endsWith("us")) {
                word = word.substring(0, word.length() - 1);
            }
            for (String suffix : SUFFIXES) {
                if (word.length() - suffix.length() >= 4 && word.endsWith(suffix)) {
                    word = word.substring(0, word.length() - suffix.length());
                    if (word.endsWith("ll")) {
                        word = word.substring(0, word.length() - 1);
                    }
                    break;
                }
            }
            stems.add(word);
        }
        return stems;
    }
}
