package com.litroute.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Compares paper titles that are written differently but name the same paper. Titles are compared with
 * every non-alphanumeric character removed, so publisher quirks that don't change the letters
 * (missing spaces in "ofEarthquake", en-dash vs hyphen, curly vs straight quotes, accents) don't
 * matter, while a genuinely different paper scores low.
 *
 * Pure text handling with no policy of its own: how similar is "similar enough" is the caller's
 * decision, because M3 matching an API hit and M4 merging two alerts do not want the same threshold.
 */
public final class Titles {

    private Titles() {
    }

    /**
     * Input: two titles, in any spacing, case or punctuation.
     * Output: 0-1, where 1 means both compact to the same string (0 if both compact to nothing).
     * Used by: Deduplicator (M4) to merge alerts; CrossrefSource/OpenAlexSource (M3) to accept an API hit.
     * Example: {@code similarity("Deep  Learning–Based Model", "deep learning-based model")} -> {@code 1.0}
     */
    public static double similarity(String a, String b) {
        String x = compact(a);
        String y = compact(b);
        int longest = Math.max(x.length(), y.length());
        if (longest == 0) {
            return 0;
        }
        return 1.0 - (double) editDistance(x, y) / longest;
    }

    /**
     * Input: a title.
     * Output: the title as lowercase ASCII letters and digits only (accents dropped): the form titles are compared and cached in.
     * Used by: M3 lookup cache keys, M4 dedup, ScoredPaper.paperKey (M7e), M5 PdfTextExtractor/ProfileKeywords.
     * Example: {@code compact("Café: A Study (2024)")} -> {@code "cafeastudy2024"}
     */
    public static String compact(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "") // drop the accents NFD split off
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    /**
     * Input: two strings.
     * Output: the Levenshtein distance (insertions, deletions, substitutions) between them.
     * Used by: {@link #similarity}.
     * Example: {@code editDistance("kitten", "sitting")} -> {@code 3}
     */
    private static int editDistance(String a, String b) {
        // Two rolling rows of the DP table instead of the full matrix.
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
