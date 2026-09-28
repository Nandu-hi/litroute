package com.litroute.ingestion.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads an author line such as "R. Abc, M. Def, M. A. Ghi &amp; others", "A B, C D and E F" or
 * "A B, C D … E F". It is deliberately strict: if any comma/"and"-separated piece doesn't look like
 * a personal name (no digits, capitalised words, at least two words), the whole line is rejected, so a
 * venue or abstract line is never mistaken for authors.
 */
final class AuthorNames {

    private static final String NAME_WORD = "\\p{Lu}[\\p{L}.'’\\-‐‑]*";
    private static final String PARTICLE = "van|von|de|der|den|del|della|di|da|dos|la|le|el|al|bin|ibn|ter|ten|du";
    private static final Pattern NAME =
            Pattern.compile("^" + NAME_WORD + "(?:\\s+(?:" + NAME_WORD + "|" + PARTICLE + ")){1,5}$");
    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:,|;|&|\\band\\b)\\s*");

    private AuthorNames() {
    }

    /** The names on the line in order, or an empty list if the line is not an author list. */
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
            if (!NAME.matcher(name).matches()) {
                return List.of();
            }
            names.add(name);
        }
        return names;
    }

    private static boolean isPlaceholder(String name) {
        return name.equalsIgnoreCase("others") || name.equalsIgnoreCase("et al") || name.equalsIgnoreCase("et al.");
    }
}
