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

    private static boolean isVenueLine(String text) {
        return text.length() <= 200 && wordCount(text) >= 3
                && (YEAR.matcher(text).find() || VOLUME.matcher(text).find());
    }

    /** "Ocean Engineering, Volume 311, Part 2, August 2024, ..." -> venue "Ocean Engineering", year 2024. */
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

    /** Label on the line before the title, else one after it, else the nearest section heading above. */
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

    /** Journal/year stated once in the header ("VOLUME: 27 | NUMBER: 4 | November 2026", "New articles for <journal>"). */
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

    private static int wordCount(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    /** Case-, punctuation- and accent-insensitive form of a title, for spotting the same paper linked twice. */
    private static String compact(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
