package com.litroute.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tells whether a printed name is a given first and last name, strictly: the printed name must start with
 * that first name and end with that last name, spelled out, with at most middle names or initials between.
 * First name Alice and last name Carter match "Alice Carter", "Alice K. Carter", "Alice K.Carter" and
 * "Carter, Alice K.", and not "Alice", "Carter", "A. Carter", "AK Carter", "Alicia Carter" or "Carter Alice".
 *
 * Only case, accents and punctuation inside a word don't matter ("Beyaztunç" is "Beyaztunc"; "Zhan-Peng" is
 * "Zhanpeng", though "Jean-Pierre" is not "Jean"). Pure text handling, like {@link Titles}.
 */
public final class PersonNames {

    private PersonNames() {
    }

    /**
     * A comma puts the last name first, as publishers' pages do ("Carter, Alice K.").
     * Input: a first name, a last name and a printed name.
     * Output: true if the printed name starts with the first name and ends with the last name, without overlap.
     * Used by: AuthorName.matches, for M6 and PriorityAuthors checking a paper's authors against the authors list.
     * Example: {@code matches("Alice", "Carter", "Alice K.Carter")} -> true; {@code matches("Alice", "Carter", "AK Carter")} -> false
     */
    public static boolean matches(String firstName, String lastName, String printed) {
        String first = compact(firstName);
        String last = compact(lastName);
        if (first.isEmpty() || last.isEmpty()) {
            return false;
        }
        int comma = printed.indexOf(',');
        if (comma >= 0) {
            return leadingWords(words(printed.substring(comma + 1)), first) > 0
                    && leadingWords(reversed(words(printed.substring(0, comma))), compactReversed(lastName)) > 0;
        }
        List<String> words = words(printed);
        int firstWords = leadingWords(words, first);
        int lastWords = leadingWords(reversed(words), compactReversed(lastName));
        return firstWords > 0 && lastWords > 0 && firstWords + lastWords <= words.size();
    }

    /**
     * Input: a name part as typed or printed.
     * Output: its letters and digits in lowercase, without accents.
     * Used by: {@link #matches}, {@link #words}; AuthorName, to refuse an initial and to tell two entries apart.
     * Example: "Beyaztunç" -> "beyaztunc"; "Zhan-Peng" -> "zhanpeng"; "K." -> "k"
     */
    public static String compact(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace("ß", "ss").replace("ø", "o").replace("Ø", "O").replace("æ", "ae").replace("Æ", "AE")
                .replace("œ", "oe").replace("Œ", "OE").replace("ł", "l").replace("Ł", "L").replace("đ", "d")
                .replace("Đ", "D").replace("ı", "i")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * Input: printed text.
     * Output: its compacted words, split at spaces and dots; a hyphen or apostrophe doesn't split a word.
     * Used by: {@link #matches}.
     * Example: "Alice K.Carter" -> [alice, k, carter]; "Jean-Pierre O'Brien" -> [jeanpierre, obrien]
     */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.split("[\\s.]+")) {
            String compacted = compact(word);
            if (!compacted.isEmpty()) {
                words.add(compacted);
            }
        }
        return words;
    }

    /**
     * Input: words, and the text they must spell from the start.
     * Output: how many leading words spell it exactly; 0 if none do.
     * Used by: {@link #matches}.
     * Example: ([alice, k, carter], "alice") -> 1; ([zhan, peng, gan], "zhanpeng") -> 2; ([a, k, carter], "alice") -> 0
     */
    private static int leadingWords(List<String> words, String text) {
        StringBuilder spelled = new StringBuilder();
        for (int i = 0; i < words.size() && spelled.length() < text.length(); i++) {
            spelled.append(words.get(i));
            if (spelled.toString().equals(text)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Input: words.
     * Output: the words in reverse order, each spelled backwards, so the end of a name reads as a start.
     * Used by: {@link #matches}, for the last name.
     * Example: [alice, carter] -> [retrac, ecila]
     */
    private static List<String> reversed(List<String> words) {
        List<String> reversed = new ArrayList<>();
        for (int i = words.size() - 1; i >= 0; i--) {
            reversed.add(new StringBuilder(words.get(i)).reverse().toString());
        }
        return reversed;
    }

    /**
     * Input: a name part.
     * Output: it compacted and spelled backwards.
     * Used by: {@link #matches}, for the last name.
     * Example: "Carter" -> "retrac"
     */
    private static String compactReversed(String name) {
        return new StringBuilder(compact(name)).reverse().toString();
    }
}
