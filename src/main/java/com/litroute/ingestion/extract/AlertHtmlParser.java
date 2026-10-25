package com.litroute.ingestion.extract;

import com.litroute.ingestion.extract.AlertLines.Line;
import com.litroute.ingestion.extract.AlertLines.Seg;
import com.litroute.ingestion.extract.LinkUnwrapper.Link;
import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import com.litroute.util.Dois;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline stage of M2: reads the papers out of an alert email's HTML.
 *
 * Every publisher's alert is a stack of "paper blocks" that read, top to bottom, as a title link
 * followed by a few short lines (authors, a citation/venue line, sometimes an article-type label and
 * an abstract). So instead of one hard-coded rule per publisher, the email is flattened to lines
 * ({@link AlertLines}) and a paper is: a line that is nothing but one link with title-like text,
 * followed - before the next title, heading or rule - by at least an author list or a venue line.
 * That last requirement is what keeps "Read article", "Unsubscribe" and footer address links from
 * being mistaken for papers; a copyright line or the issue header never counts as a venue line, so a
 * footer link above "© 2025 ..." or the journal's own name above "VOLUME: 27" is not a paper either.
 * An article-type label ("Technical Papers") heads every paper below it until the next label, as in a
 * table of contents. Anything the lines don't state (journal in a header, an unreadable link) is left
 * empty for the enrichment stage.
 *
 * Example input (HTML body of one alert email):
 * <pre>{@code
 * <p>Research article</p>
 * <p><a href="https://doi.org/10.1016/j.oceaneng.2024.118000">Wave loads on floating offshore wind turbines</a><br>
 *    A. Smith, B. Jones<br>
 *    Ocean Engineering, Volume 311, Part 2, August 2024</p>
 * <hr>
 * <p><a href="https://example.com/alerts/manage">Unsubscribe from these alerts</a></p>
 * }</pre>
 * Output of {@link #parse(RawEmail)}, one paper (the Unsubscribe link has no author or venue line under it):
 * <pre>{@code
 * ParsedPaper(linkKind = READABLE, candidate:
 *   title       = "Wave loads on floating offshore wind turbines"
 *   authors     = ["A. Smith", "B. Jones"]
 *   venue       = "Ocean Engineering",  year = 2024
 *   articleType = "Research article"    (label on the line above the title)
 *   doi         = "10.1016/j.oceaneng.2024.118000"
 *   url         = "https://doi.org/10.1016/j.oceaneng.2024.118000")
 * }</pre>
 */
final class AlertHtmlParser {

    private static final int MAX_BLOCK_LINES = 12;
    private static final int MIN_TITLE_WORDS = 3;

    private static final Pattern TITLE_TAG =
            Pattern.compile("(?i)^\\[(?:PDF|HTML|BOOK|B|CITATION|C|DOC)\\]\\s*");
    private static final Pattern FURNITURE = Pattern.compile("(?i)^(?:unsubscribe.*"
            + "|manage (?:your )?(?:alerts?|preferences|subscriptions?).*|update (?:your )?preferences"
            + "|view (?:in|the|latest|more|all|full|this)\\b.*|see all\\b.*"
            + "|read (?:the )?(?:full )?(?:article|more|abstract|now)\\b.*|full[- ]?text|download\\b.*"
            + "|help|support|contact(?: us)?|privacy(?: policy)?|terms\\b.*|sitemap|subscribe|list alerts"
            + "|cancel alert|sign in|log ?in|click here|here|your profile)$");
    private static final Pattern TYPE_LINE = Pattern.compile("(?i)^(?:[\\w\\-]+\\s+){0,3}(?:articles?|papers?"
            + "|reviews?|research|notes?|letters?|communications?|stud(?:y|ies)|reports?|erratum|editorial"
            + "|perspective|commentary|corrections?|discussions?|closures?)$");
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)\\d{2}\\b");
    private static final Pattern VOLUME = Pattern.compile("(?i)\\bvol(?:ume|\\.)?\\s*\\d+");
    private static final Pattern VENUE_CUT =
            Pattern.compile("(?i),\\s*(?:Volume|Vol\\.?|Issue|No\\.?|Part)\\b|[,\\s]+(?:19|20)\\d{2}\\b|\\s[-–—‐]\\s");
    private static final Pattern AUTHORS_THEN_VENUE =
            Pattern.compile("^(?<authors>.+?)\\s+[-–—‐]\\s+(?<rest>.+)$");
    private static final Pattern VOLUME_HEADER = Pattern.compile("(?i)^volume:?\\s*\\d+");
    private static final Pattern COPYRIGHT = Pattern.compile("(?i)^(?:©|\\(c\\)|copyright\\b)");
    /** Links Gmail adds itself when a message is forwarded: a street address becomes a Google Maps search. */
    private static final Pattern GMAIL_ADDED_LINK =
            Pattern.compile("(?i)^https?://(?:(?:www\\.)?google\\.[a-z.]+/maps\\b|maps\\.google\\.)");
    private static final Pattern VENUE_PREFIX = Pattern.compile(
            "(?i)(?:new articles (?:for|in|from)|published in|latest (?:articles|issue) (?:of|from|in)|new issue of)\\s*$");

    /** What the email header says about every paper in it, for alerts that name the journal once. */
    private record Issue(Optional<String> venue, Optional<Integer> year) {
    }

    /** The parts of one paper block, before they are combined with the issue header and links. */
    private static final class Block {
        List<String> authors = List.of();
        Optional<String> venue = Optional.empty();
        Optional<Integer> year = Optional.empty();
        String type;
        String abstractSnippet;
        final List<String> texts = new ArrayList<>();
    }

    /**
     * Input: one alert email (only its HTML body is read).
     * Output: its research papers in email order, one per title line with an author or venue line under it; repeats
     * and notes ({@link PaperCandidate#isResearch}) skipped; [] if there is no HTML body.
     * Used by: PaperExtractor.extract (M2), before enrichment.
     * Example: the email in the class example -> one ParsedPaper ("Wave loads on floating offshore wind
     * turbines", READABLE); an email with only a plain-text body -> [].
     */
    List<ParsedPaper> parse(RawEmail email) {
        if (email.htmlBody().isEmpty()) {
            return List.of();
        }
        List<Line> lines = AlertLines.parse(email.htmlBody().get());
        Issue issue = issueOf(lines);
        // Pass 1: which lines are titles (not the journal's own name, not a linked author under a title).
        String[] titles = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            titles[i] = titleOf(lines.get(i))
                    .filter(title -> !issue.venue().map(title::equalsIgnoreCase).orElse(false))
                    .orElse(null);
            if (titles[i] != null && i > 0 && titles[i - 1] != null && !linkedAuthors(titles[i]).isEmpty()) {
                titles[i] = null; // a linked author name under its paper's title, not a title of its own
            }
        }

        // Pass 2: build a paper from each title and the block under it.
        List<ParsedPaper> papers = new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (titles[i] == null) {
                continue;
            }
            Optional<ParsedPaper> paper = paperAt(email, lines, titles, issue, i);
            if (paper.isPresent() && paper.get().candidate().isResearch()
                    && seenTitles.add(compact(paper.get().candidate().title()))) {
                papers.add(paper.get());
            }
        }
        return papers;
    }

    /**
     * Input: the email, all its lines, the title per line (null if none), the issue header, and the title line's index.
     * Output: the paper, or empty if its block (up to the next title, heading, rule or 12 lines) has neither authors
     * nor venue; url = first readable link, DOI searched in url, then links, then text.
     * Used by: {@link #parse}.
     * Example, with the lines of the class example:
     * <pre>{@code
     * 0 "Research article"
     * 1 [link] "Wave loads on floating offshore wind turbines"      <- titleIndex = 1
     * 2 "A. Smith, B. Jones"
     * 3 "Ocean Engineering, Volume 311, Part 2, August 2024"
     * 4 rule                                                       <- block ends here (lines 2-3)
     * -> ParsedPaper(READABLE, authors [A. Smith, B. Jones], venue "Ocean Engineering", year 2024,
     *                type "Research article", doi "10.1016/j.oceaneng.2024.118000")
     * A title line followed directly by a rule -> empty (no authors, no venue).
     * }</pre>
     */
    private Optional<ParsedPaper> paperAt(RawEmail email, List<Line> lines, String[] titles, Issue issue, int titleIndex) {
        // The block runs until the next title, heading, rule, or MAX_BLOCK_LINES.
        int end = titleIndex + 1;
        while (end < lines.size() && end - titleIndex <= MAX_BLOCK_LINES && titles[end] == null
                && !lines.get(end).heading() && !lines.get(end).rule()) {
            end++;
        }
        if (end < lines.size() && titles[end] != null && end - 1 > titleIndex && isTypeLabel(lines.get(end - 1))) {
            end--; // a label right above the next title heads that paper, not this one
        }
        Block block = readBlock(lines.subList(titleIndex + 1, end));
        if (block.authors.isEmpty() && block.venue.isEmpty()) {
            return Optional.empty();
        }

        // Title link first, then every other link in the block (Full Text, PDF, Read article ...).
        List<String> hrefs = new ArrayList<>();
        hrefs.add(lines.get(titleIndex).soleAnchor().orElseThrow().href());
        for (Line line : lines.subList(titleIndex + 1, end)) {
            line.anchors().forEach(anchor -> hrefs.add(anchor.href()));
        }
        List<Link> links = new ArrayList<>();
        hrefs.forEach(href -> links.add(LinkUnwrapper.resolve(href)));

        Optional<String> url = links.stream().flatMap(link -> link.url().stream()).findFirst();
        Optional<String> doi = url.flatMap(Dois::find);
        for (int i = 0; doi.isEmpty() && i < hrefs.size(); i++) {
            doi = Dois.find(hrefs.get(i));
        }
        for (int i = 0; doi.isEmpty() && i < block.texts.size(); i++) {
            doi = Dois.find(block.texts.get(i));
        }

        // READABLE if any link decoded to a URL; else OPAQUE if any was a tracker; else NONE.
        LinkKind kind = url.isPresent() ? LinkKind.READABLE
                : links.stream().anyMatch(link -> link.kind() == LinkKind.OPAQUE) ? LinkKind.OPAQUE : LinkKind.NONE;

        PaperCandidate candidate = new PaperCandidate(
                email.messageId(),
                titles[titleIndex],
                block.authors,
                block.venue.or(issue::venue),
                block.year.or(issue::year),
                Optional.ofNullable(articleType(lines, titles, titleIndex, block)),
                doi,
                url,
                Optional.ofNullable(block.abstractSnippet),
                Optional.empty());
        return Optional.of(new ParsedPaper(candidate, kind));
    }

    /**
     * Input: the lines after a title, up to the end of its block.
     * Output: the block's parts: each line goes to the first part it fits (type, authors, venue, abstract); link-only
     * lines are skipped unless they are the author list (ASCE), and an "authors - venue" line fills both.
     * Used by: {@link #paperAt}.
     * Example:
     * <pre>{@code
     * ["Research Article",
     *  "A. Smith, B. Jones - Ocean Engineering 2024",
     *  "This paper studies wave loads on floating platforms using a coupled model."]
     * -> type "Research Article", authors [A. Smith, B. Jones], venue "Ocean Engineering", year 2024,
     *    abstractSnippet "This paper studies wave loads ..."
     * }</pre>
     */
    private Block readBlock(List<Line> blockLines) {
        Block block = new Block();
        boolean haveAuthors = false;
        for (Line line : blockLines) {
            String text = line.text();
            block.texts.add(text);
            if (line.soleAnchor().isPresent()) {
                List<String> linkedNames = haveAuthors ? List.of() : linkedAuthors(text);
                if (!linkedNames.isEmpty()) {
                    block.authors = linkedNames;
                    haveAuthors = true;
                }
                continue;
            }
            if (block.type == null && TYPE_LINE.matcher(text).matches()) {
                block.type = text;
                continue;
            }
            if (!haveAuthors) {
                Matcher combined = AUTHORS_THEN_VENUE.matcher(text);
                if (combined.matches()) {
                    List<String> names = AuthorNames.parse(combined.group("authors"));
                    if (!names.isEmpty()) {
                        block.authors = names;
                        haveAuthors = true;
                        readVenue(block, combined.group("rest"));
                        continue;
                    }
                }
                List<String> names = AuthorNames.parse(text);
                if (!names.isEmpty()) {
                    block.authors = names;
                    haveAuthors = true;
                    continue;
                }
            }
            if (block.venue.isEmpty() && isVenueLine(text)) {
                readVenue(block, text);
                continue;
            }
            if (block.abstractSnippet == null && wordCount(text) >= 10) {
                block.abstractSnippet = text;
            }
        }
        return block;
    }

    /**
     * Input: a normalized line of text.
     * Output: true if it is &lt;= 200 chars, has &gt;= 3 words, mentions a year or volume, and is neither a copyright line nor an issue header.
     * Used by: {@link #readBlock}.
     * Example: "Ocean Engineering, Volume 311, Part 2, August 2024" -> true; "A. Smith, B. Jones" -> false;
     * "© 2025 BioMed Central Limited" -> false (a footer); "VOLUME: 27 | NUMBER: 4 | November 2026" -> false
     * (the issue header, which {@link #issueOf} reads).
     */
    private static boolean isVenueLine(String text) {
        return text.length() <= 200 && wordCount(text) >= 3
                && (YEAR.matcher(text).find() || VOLUME.matcher(text).find())
                && !COPYRIGHT.matcher(text).find() && !VOLUME_HEADER.matcher(text).find();
    }

    /**
     * Input: the block to fill and a venue line (or the part after the authors in an "authors - venue" line).
     * Output: none; sets block.venue to the text before the first volume/issue/year/dash marker, and block.year to the first 19xx/20xx.
     * Used by: {@link #readBlock}.
     * Example: "Ocean Engineering, Volume 311, Part 2, August 2024" -> venue "Ocean Engineering", year 2024;
     * "Journal of Fluid Mechanics" -> venue "Journal of Fluid Mechanics", year left unset.
     */
    private static void readVenue(Block block, String text) {
        Matcher cut = VENUE_CUT.matcher(text);
        String head = cut.find() ? text.substring(0, cut.start()) : text;
        head = head.replace("…", "").replaceAll("[\\s,&\\-‐]+$", "").trim();
        if (!head.isEmpty()) {
            block.venue = Optional.of(head);
        }
        Matcher year = YEAR.matcher(text);
        if (year.find()) {
            block.year = Optional.of(Integer.parseInt(year.group()));
        }
    }

    /**
     * Label on the line before the title, else one after it, else the nearest label above: in a table of
     * contents a section label ("Technical Papers") heads every paper below it until the next label.
     * Input: all lines, the title per line, the title line's index, and its block (for a label under the title).
     * Output: the label (a few words ending in a type word: "article", "papers", ...), or null if there is none.
     * Used by: {@link #paperAt}.
     * Example:
     * <pre>{@code
     * "Research article" / [title]                            -> "Research article"  (line above)
     * "Technical Papers" / [title A] / authors / [title B]    -> "Technical Papers" for both
     * <h1>New articles have been published</h1> ... [title]   -> null                (not a type label)
     * }</pre>
     */
    private static String articleType(List<Line> lines, String[] titles, int titleIndex, Block block) {
        if (titleIndex > 0) {
            Line previous = lines.get(titleIndex - 1);
            if (titles[titleIndex - 1] == null && isTypeLabel(previous)) {
                return previous.text();
            }
        }
        if (block.type != null) {
            return block.type;
        }
        for (int i = titleIndex - 1; i >= 0; i--) {
            if (titles[i] == null && isTypeLabel(lines.get(i))) {
                return lines.get(i).text();
            }
        }
        return null;
    }

    /**
     * The names on a link line that is an author list, as ASCE links each author to their profile.
     * Input: a link's text.
     * Output: the names, or [] if it is not an author list or is all capitals.
     * Used by: {@link #parse} (linked author under a title is no title), {@link #readBlock}.
     * Example: "Jane Doe" -> [Jane Doe]; "ABOUT ASCE PUBLICATIONS" -> [] (a footer menu
     * link: capitalised words, but no lowercase letter, which every name in an author list has).
     */
    private static List<String> linkedAuthors(String text) {
        return text.chars().anyMatch(Character::isLowerCase) ? AuthorNames.parse(text) : List.of();
    }

    /**
     * Input: a line.
     * Output: true if it is an article-type label (not a rule, not a link, a few words ending in a type word).
     * Used by: {@link #paperAt}, {@link #articleType}.
     * Example: "Technical Papers" -> true; "Wave loads on piles" -> false
     */
    private static boolean isTypeLabel(Line line) {
        return !line.rule() && line.soleAnchor().isEmpty() && TYPE_LINE.matcher(line.text()).matches();
    }

    /**
     * Input: one line of the email.
     * Output: the link text minus any "[PDF]" tag, if the line is a single http(s) link of &gt;= 3 words that isn't footer text; else empty.
     * Used by: {@link #parse}.
     * Example:
     * <pre>{@code
     * <a href="https://doi.org/10.1/abc">[PDF] Wave loads on floating turbines</a> -> "Wave loads on floating turbines"
     * <a href="https://example.com/manage">Unsubscribe from these alerts</a>       -> empty (footer text)
     * <a href="mailto:editor@example.com">Contact the editor</a>                   -> empty (not http)
     * <a href="https://www.google.com/maps/search/...">London, N1 9XW,</a>          -> empty (Gmail's address link)
     * Read <a href="https://doi.org/10.1/abc">Wave loads on floating turbines</a>  -> empty (text beside link)
     * }</pre>
     */
    private static Optional<String> titleOf(Line line) {
        Optional<Seg> anchor = line.soleAnchor();
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        String href = anchor.get().href().trim().toLowerCase(Locale.ROOT);
        if (!href.startsWith("http://") && !href.startsWith("https://") || GMAIL_ADDED_LINK.matcher(href).find()) {
            return Optional.empty();
        }
        String title = TITLE_TAG.matcher(AlertLines.normalize(anchor.get().text())).replaceFirst("");
        if (wordCount(title) < MIN_TITLE_WORDS || FURNITURE.matcher(title).matches()) {
            return Optional.empty();
        }
        return Optional.of(title);
    }

    /**
     * Journal/year stated once in the header ("VOLUME: 27 | NUMBER: 4 | November 2026", "New articles for <journal>").
     * Input: all lines of the email.
     * Output: the journal above a "VOLUME: n" header (with its year), else the link after "New articles for"-style wording; else both empty.
     * Used by: {@link #parse}, as the fallback venue/year for every paper.
     * Example:
     * <pre>{@code
     * "Journal of Fluid Mechanics" / "VOLUME: 27 | NUMBER: 4 | November 2026" -> Issue("Journal of Fluid Mechanics", 2026)
     * New articles for <a href="https://example.com/oe">Ocean Engineering</a>  -> Issue("Ocean Engineering", empty)
     * neither                                                                  -> Issue(empty, empty)
     * }</pre>
     */
    private static Issue issueOf(List<Line> lines) {
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i).text();
            if (VOLUME_HEADER.matcher(text).find() && !lines.get(i - 1).rule()) {
                Matcher year = YEAR.matcher(text);
                return new Issue(Optional.of(lines.get(i - 1).text()),
                        year.find() ? Optional.of(Integer.parseInt(year.group())) : Optional.empty());
            }
        }
        for (Line line : lines) {
            StringBuilder before = new StringBuilder();
            for (Seg seg : line.segs()) {
                boolean linkWithText = seg.href() != null && !AlertLines.normalize(seg.text()).isEmpty();
                if (linkWithText && VENUE_PREFIX.matcher(before).find() && line.soleAnchor().isEmpty()) {
                    return new Issue(Optional.of(AlertLines.normalize(seg.text())), Optional.empty());
                }
                before.append(seg.text());
            }
        }
        return new Issue(Optional.empty(), Optional.empty());
    }

    /**
     * Input: any text.
     * Output: the number of whitespace-separated words; 0 for blank text.
     * Used by: readBlock (abstract), isVenueLine, titleOf.
     * Example: "  Wave loads   on turbines " -> 4; "   " -> 0
     */
    private static int wordCount(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    /**
     * Input: a paper title.
     * Output: it with accents stripped, lowercased, only a-z0-9 kept (same as Titles.compact).
     * Used by: {@link #parse}, to spot the same paper linked twice.
     * Example: "Wave-Loads on Floating Turbines: Café Study" -> "waveloadsonfloatingturbinescafestudy"
     */
    private static String compact(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
