package com.litroute.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a bare DOI ("10.1186/s43065-024-00115-x") inside arbitrary text or a URL such as
 * ".../articles/10.1186/x?utm_source=alert" or ".../counter/pdf/10.1186/x.pdf", stripping the
 * query string and the ".pdf"/"/full"/"/abstract" style suffixes publishers append to DOI URLs.
 */
public final class Dois {

    private static final Pattern DOI = Pattern.compile("(?i)\\b(10\\.\\d{4,9}/[^\\s\"'<>?#&\\\\]+)");
    private static final Pattern URL_SUFFIX =
            Pattern.compile("(?i)(?:\\.pdf|/pdf|/epdf|/full|/fulltext|/abstract|/abs|/meta|/references)$");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:]+$");

    private Dois() {
    }

    /**
     * Input: any text or URL (null or blank allowed), possibly percent-encoded.
     * Output: the first DOI in it, without query string or publisher suffix; empty if there is none.
     * Used by: AlertHtmlParser (M2) to read a DOI off each alert link.
     * Example: {@code find("https://x.org/counter/pdf/10.1186/s43065-024-00115-x.pdf?utm=a")} -> {@code Optional["10.1186/s43065-024-00115-x"]}
     */
    public static Optional<String> find(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = DOI.matcher(text.contains("%") ? percentDecode(text) : text);
        while (matcher.find()) {
            String doi = clean(matcher.group(1));
            // Skip a bare prefix like "10.1186/" that has nothing after the slash.
            if (doi.indexOf('/') < doi.length() - 1) {
                return Optional.of(doi);
            }
        }
        return Optional.empty();
    }

    /**
     * Input: a raw DOI match that may carry trailing punctuation, URL suffixes or a closing bracket.
     * Output: the DOI with all of those peeled off, repeatedly until nothing changes.
     * Used by: {@link #find}.
     * Example: {@code clean("10.1/x/full).")} -> {@code "10.1/x"}
     */
    private static String clean(String doi) {
        String previous;
        do {
            previous = doi;
            doi = TRAILING_PUNCTUATION.matcher(doi).replaceFirst("");
            doi = URL_SUFFIX.matcher(doi).replaceFirst("");
            // A closing bracket with no opener in the DOI belongs to the surrounding sentence: "(doi:10.1/x)".
            if (doi.endsWith(")") && count(doi, '(') < count(doi, ')')) {
                doi = doi.substring(0, doi.length() - 1);
            }
            if (doi.endsWith("]") && count(doi, '[') < count(doi, ']')) {
                doi = doi.substring(0, doi.length() - 1);
            }
        } while (!doi.equals(previous));
        return doi;
    }

    /**
     * Input: a string and a character.
     * Output: how many times the character occurs in it.
     * Used by: {@link #clean} to balance brackets.
     * Example: {@code count("a(b)(c", '(')} -> {@code 2}
     */
    private static long count(String text, char symbol) {
        return text.chars().filter(c -> c == symbol).count();
    }

    /**
     * Input: text that contains {@code %} escapes.
     * Output: the decoded text ({@code +} kept literal); the input unchanged if it is not valid encoding.
     * Used by: {@link #find}, for DOIs inside encoded redirect URLs.
     * Example: {@code percentDecode("10.1186%2Fs4")} -> {@code "10.1186/s4"}
     */
    private static String percentDecode(String text) {
        try {
            // Protect '+' so URLDecoder doesn't turn it into a space (DOIs may contain '+').
            return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
