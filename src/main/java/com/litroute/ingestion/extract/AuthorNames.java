package com.litroute.ingestion.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads an author line such as "R. Abc, M. Def, M. A. Ghi &amp; others", "A B, C D and E F" or
 * "A B, C D … E F". It is deliberately strict: if any comma/"and"-separated piece doesn't look like
 * a personal name (no digits, capitalised words, at least two words), the whole line is rejected, so a
 * venue or abstract line is never mistaken for authors.
 *
 * A name is a capitalised word followed by 1-5 more capitalised words or lowercase particles
 * ("van", "de", "la", ...); pieces are split on ",", ";", "&amp;" and "and", and "…"/"..." count as a comma.
 *
 * Example:
 * <pre>{@code
 * "A. Smith, B. Jones and C. Lee"          -> [A. Smith, B. Jones, C. Lee]
 * "Maria de la Cruz, Jan van der Berg"     -> [Maria de la Cruz, Jan van der Berg]
 * "Ocean Engineering, Volume 311, Part 2"  -> []   ("Volume 311" is not a name)
 * }</pre>
 */
final class AuthorNames {

    private static final String NAME_WORD = "\\p{Lu}[\\p{L}.'’\\-‐‑]*";
    private static final String PARTICLE = "van|von|de|der|den|del|della|di|da|dos|la|le|el|al|bin|ibn|ter|ten|du";
    private static final Pattern NAME =
            Pattern.compile("^" + NAME_WORD + "(?:\\s+(?:" + NAME_WORD + "|" + PARTICLE + ")){1,5}$");
    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:,|;|&|\\band\\b)\\s*");

    private AuthorNames() {
    }

    /**
     * Input: one line of text from the email (null allowed).
     * Output: the names in order, "others"/"et al." dropped; [] if null, blank, over 600 chars, or any piece is not a name.
     * Used by: AlertHtmlParser (M2) for author lines and combined "authors - venue" lines.
     * Example:
     * <pre>{@code
     * "R. Abc, M. Def, M. A. Ghi & others"   -> [R. Abc, M. Def, M. A. Ghi]
     * "Wei Zhang; Li Na … Omar Haddad"       -> [Wei Zhang, Li Na, Omar Haddad]
     * "A. Smith, B. Jones, et al."           -> [A. Smith, B. Jones]
     * "A. Smith et al."                      -> []   (no separator, so "et al." stays inside the piece)
     * "Smith, Jones"                         -> []   (a name needs at least two words)
     * "This paper studies wave loads"        -> []   ("paper" is not capitalised)
     * }</pre>
     */
    static List<String> parse(String line) {
        if (line == null || line.isBlank() || line.length() > 600) {
            return List.of();
        }
        // "…" / "..." stands in for omitted authors; the names on either side are still real.
        String separated = line.replace("…", ",").replace("...", ",");
        List<String> names = new ArrayList<>();
        for (String piece : SEPARATOR.split(separated)) {
            String name = piece.trim();
            if (name.isEmpty() || isPlaceholder(name)) {
                continue;
            }
            // One non-name piece means this is not an author line at all.
            if (!NAME.matcher(name).matches()) {
                return List.of();
            }
            names.add(name);
        }
        return names;
    }

    /**
     * Input: one trimmed piece of an author line.
     * Output: true if it is "others", "et al" or "et al." (any case), i.e. stands for omitted authors.
     * Used by: {@link #parse}.
     * Example: "others" -> true; "Et Al." -> true; "A. Smith" -> false
     */
    private static boolean isPlaceholder(String name) {
        return name.equalsIgnoreCase("others") || name.equalsIgnoreCase("et al") || name.equalsIgnoreCase("et al.");
    }
}
