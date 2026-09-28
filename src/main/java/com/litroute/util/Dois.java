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

    public static Optional<String> find(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = DOI.matcher(text.contains("%") ? percentDecode(text) : text);
        while (matcher.find()) {
            String doi = clean(matcher.group(1));
            if (doi.indexOf('/') < doi.length() - 1) {
                return Optional.of(doi);
            }
        }
        return Optional.empty();
    }

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

    private static long count(String text, char symbol) {
        return text.chars().filter(c -> c == symbol).count();
    }

    private static String percentDecode(String text) {
        try {
            return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
