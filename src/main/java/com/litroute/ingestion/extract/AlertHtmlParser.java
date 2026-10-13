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
 * being mistaken for papers. Anything the lines don't state (journal in a header, an unreadable
 * link) is left empty for the enrichment stage.
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
            + "|perspective|commentary|corrections?)$");
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)\\d{2}\\b");
    private static final Pattern VOLUME = Pattern.compile("(?i)\\bvol(?:ume|\\.)?\\s*\\d+");
    private static final Pattern VENUE_CUT =
            Pattern.compile("(?i),\\s*(?:Volume|Vol\\.?|Issue|No\\.?|Part)\\b|[,\\s]+(?:19|20)\\d{2}\\b|\\s[-–—‐]\\s");
    private static final Pattern AUTHORS_THEN_VENUE =
            Pattern.compile("^(?<authors>.+?)\\s+[-–—‐]\\s+(?<rest>.+)$");
    private static final Pattern VOLUME_HEADER = Pattern.compile("(?i)^volume:?\\s*\\d+");
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
     * Reads every paper out of one alert email.
     *
     * Example: the email in the class example -> one ParsedPaper ("Wave loads on floating offshore wind
     * turbines", READABLE); an email with only a plain-text body -> [].
     *
     * @param email the alert email; only its HTML body is read
     * @return the papers in email order, one per title line that has an author or venue line under it,
     *         skipping repeats of a title already seen; empty if the email has no HTML body
     */
    List<ParsedPaper> parse(RawEmail email) {
        if (email.htmlBody().isEmpty()) {
            return List.of();
        }
        List<Line> lines = AlertLines.parse(email.htmlBody().get());
        Issue issue = issueOf(lines);
        String[] titles = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            titles[i] = titleOf(lines.get(i)).orElse(null);
        }

        List<ParsedPaper> papers = new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (titles[i] == null) {
                continue;
            }
            Optional<ParsedPaper> paper = paperAt(email, lines, titles, issue, i);
            if (paper.isPresent() && seenTitles.add(compact(paper.get().candidate().title()))) {
                papers.add(paper.get());
            }
        }
        return papers;
    }

    /**
     * Builds the paper whose title is on one line, from that line and the block of lines under it.
     *
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
     *
     * @param email the alert email, for the message id
     * @param lines all lines of the email
     * @param titles the title read from each line, or null where the line is not a title
     * @param issue journal/year from the email header, used where the block does not state them
     * @param titleIndex index of the title line
     * @return the paper, or empty if the block (lines up to the next title, heading, rule or 12 lines) has
     *         neither authors nor venue; the URL is the first readable link, the DOI is searched in URL, links, then text
     */
    private Optional<ParsedPaper> paperAt(RawEmail email, List<Line> lines, String[] titles, Issue issue, int titleIndex) {
        int end = titleIndex + 1;
        while (end < lines.size() && end - titleIndex <= MAX_BLOCK_LINES && titles[end] == null
                && !lines.get(end).heading() && !lines.get(end).rule()) {
            end++;
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

        LinkKind kind = url.isPresent() ? LinkKind.READABLE
                : links.stream().anyMatch(link -> link.kind() == LinkKind.OPAQUE) ? LinkKind.OPAQUE : LinkKind.NONE;

        PaperCandidate candidate = new PaperCandidate(
                email.messageId(),
                titles[titleIndex],
                block.authors,
                block.venue.or(issue::venue),
                block.year.or(issue::year),
                Optional.ofNullable(articleType(lines, titles, titleIndex, block, issue)),
                doi,
                url,
                Optional.ofNullable(block.abstractSnippet),
                Optional.empty());
        return Optional.of(new ParsedPaper(candidate, kind));
    }

    /**
     * Sorts the lines under a title into type label, authors, venue/year and abstract.
     *
     * Example:
     * <pre>{@code
     * ["Research Article",
     *  "A. Smith, B. Jones - Ocean Engineering 2024",
     *  "This paper studies wave loads on floating platforms using a coupled model."]
     * -> type "Research Article", authors [A. Smith, B. Jones], venue "Ocean Engineering", year 2024,
     *    abstractSnippet "This paper studies wave loads ..."
     * }</pre>
     *
     * @param blockLines the lines after the title, up to the end of its block
     * @return the block's parts; each line goes to the first part it fits (link-only lines are skipped),
     *         and an "authors - venue" line fills both authors and venue
     */
    private Block readBlock(List<Line> blockLines) {
        Block block = new Block();
        boolean haveAuthors = false;
        for (Line line : blockLines) {
            String text = line.text();
            block.texts.add(text);
            if (line.soleAnchor().isPresent()) {
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
     * Whether a line looks like a citation/venue line.
     *
     * Example: "Ocean Engineering, Volume 311, Part 2, August 2024" -> true; "A. Smith, B. Jones" -> false.
     *
     * @param text the normalized line text
     * @return true when the line is at most 200 characters, has at least 3 words, and mentions a year or volume
     */
    private static boolean isVenueLine(String text) {
        return text.length() <= 200 && wordCount(text) >= 3
                && (YEAR.matcher(text).find() || VOLUME.matcher(text).find());
    }

    /**
     * "Ocean Engineering, Volume 311, Part 2, August 2024, ..." -> venue "Ocean Engineering", year 2024.
     * Returns nothing; the venue is the text before the first volume/issue/year/dash marker with trailing
     * punctuation removed, and the year is the first 19xx/20xx in the line. Each is set on the block when found.
     *
     * Example: "Ocean Engineering 2024" -> venue "Ocean Engineering", year 2024;
     * "Journal of Fluid Mechanics" -> venue "Journal of Fluid Mechanics", year left unset.
     *
     * @param block the block to fill in
     * @param text the venue line (or the part after the authors in an "authors - venue" line)
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
     * Label on the line before the title, else one after it, else the nearest section heading above.
     *
     * Example:
     * <pre>{@code
     * "Research article" / [title]                       -> "Research article"  (line above)
     * <h2>Original Research</h2> ... [title] ...          -> "Original Research" (section heading)
     * <h2>Volume 27, Issue 4</h2> ... [title] ...         -> null                (heading has digits)
     * }</pre>
     *
     * @param lines all lines of the email
     * @param titles the title read from each line, or null where the line is not a title
     * @param titleIndex index of the title line
     * @param block the title's block, for a type label found under the title
     * @param issue the email header, so a heading that is just the journal name is not taken as a type
     * @return the label; a heading counts only if it has at most 5 words and no digits; null if none fits
     */
    private static String articleType(List<Line> lines, String[] titles, int titleIndex, Block block, Issue issue) {
        if (titleIndex > 0) {
            Line previous = lines.get(titleIndex - 1);
            if (!previous.rule() && titles[titleIndex - 1] == null && previous.soleAnchor().isEmpty()
                    && TYPE_LINE.matcher(previous.text()).matches()) {
                return previous.text();
            }
        }
        if (block.type != null) {
            return block.type;
        }
        for (int i = titleIndex - 1; i >= 0; i--) {
            Line line = lines.get(i);
            if (line.heading() && titles[i] == null) {
                String text = line.text();
                boolean sectionLike = wordCount(text) <= 5 && !text.matches(".*\\d.*")
                        && !issue.venue().map(text::equalsIgnoreCase).orElse(false);
                return sectionLike ? text : null;
            }
        }
        return null;
    }

    /**
     * The paper title on a line, if the line has the shape of one.
     *
     * Example:
     * <pre>{@code
     * <a href="https://doi.org/10.1/abc">[PDF] Wave loads on floating turbines</a> -> "Wave loads on floating turbines"
     * <a href="https://example.com/manage">Unsubscribe from these alerts</a>       -> empty (footer text)
     * <a href="mailto:editor@example.com">Contact the editor</a>                   -> empty (not http)
     * Read <a href="https://doi.org/10.1/abc">Wave loads on floating turbines</a>  -> empty (text beside link)
     * }</pre>
     *
     * @param line one line of the email
     * @return the link text with any "[PDF]"-style tag removed, when the line is a single http(s) link whose
     *         text has at least 3 words and is not footer text like "Unsubscribe"; empty otherwise
     */
    private static Optional<String> titleOf(Line line) {
        Optional<Seg> anchor = line.soleAnchor();
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        String href = anchor.get().href().trim().toLowerCase(Locale.ROOT);
        if (!href.startsWith("http://") && !href.startsWith("https://")) {
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
     *
     * Example:
     * <pre>{@code
     * "Journal of Fluid Mechanics" / "VOLUME: 27 | NUMBER: 4 | November 2026" -> Issue("Journal of Fluid Mechanics", 2026)
     * New articles for <a href="https://example.com/oe">Ocean Engineering</a>  -> Issue("Ocean Engineering", empty)
     * neither                                                                  -> Issue(empty, empty)
     * }</pre>
     *
     * @param lines all lines of the email
     * @return the journal from the line above a "VOLUME: n" header (with that header's year), else the link text
     *         after "New articles for"-style wording; both parts empty if the email has neither
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
     * Counts words in a piece of text.
     *
     * Example: "  Wave loads   on turbines " -> 4; "   " -> 0.
     *
     * @param text any text
     * @return the number of whitespace-separated words; 0 for blank text
     */
    private static int wordCount(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    /**
     * Case-, punctuation- and accent-insensitive form of a title, for spotting the same paper linked twice.
     *
     * Example: "Wave-Loads on Floating Turbines: Café Study" -> "waveloadsonfloatingturbinescafestudy".
     *
     * @param title a paper title
     * @return the title with accents stripped, lowercased, and everything except a-z and 0-9 removed
     */
    private static String compact(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
