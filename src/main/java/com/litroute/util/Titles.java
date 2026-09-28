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

    /** @return 0-1, where 1 means the two titles compact to exactly the same string */
    public static double similarity(String a, String b) {
        String x = compact(a);
        String y = compact(b);
        int longest = Math.max(x.length(), y.length());
        if (longest == 0) {
            return 0;
        }
        return 1.0 - (double) editDistance(x, y) / longest;
    }

    /** The title reduced to lowercase letters and digits: the form titles are compared (and cached) in. */
    public static String compact(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static int editDistance(String a, String b) {
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
