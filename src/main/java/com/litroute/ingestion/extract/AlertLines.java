package com.litroute.ingestion.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Flattens an alert email's HTML into the ordered lines a reader would see: every block element and
 * {@code <br>} starts a new line, links keep their href, images vanish. Publisher alerts all use
 * different markup (tables, headings, divs) but the same visual pattern of title / authors / venue
 * lines, so reading the email as lines is what makes one parser work across them.
 *
 * Gmail's forwarding scaffolding (the forwarder's signature and the quoted From/Date/Subject header
 * block) is dropped, since it is the forwarder's text, not the alert's.
 *
 * Example input:
 * <pre>{@code
 * <td>
 *   <a href="https://doi.org/10.1/abc">Deep <b>Learning</b> for X</a><br>
 *   A. Smith, B. Jones <img src="orcid.png"><br>
 *   <i>Nature</i>, 2026
 * </td>
 * <hr>
 * }</pre>
 * Output of {@link #parse(String)}, four lines:
 * <pre>{@code
 * 1. text() = "Deep Learning for X"   soleAnchor() = Seg("Deep Learning for X", "https://doi.org/10.1/abc")
 * 2. text() = "A. Smith, B. Jones"    anchors() = []   (the image is dropped)
 * 3. text() = "Nature, 2026"          anchors() = []   (inline <i> does not split the line)
 * 4. rule = true, no segments         (from the <hr>)
 * }</pre>
 */
final class AlertLines {

    /** A run of text; href is non-null when the run is the text of a link. */
    record Seg(String text, String href) {
    }

    record Line(List<Seg> segs, boolean heading, boolean rule) {

        /**
         * Input: none (this line's segments).
         * Output: every segment's text, links included, concatenated and {@link AlertLines#normalize normalized}.
         * Used by: AlertHtmlParser for every line test (authors, venue, type, issue lines).
         * Example: segs [("Deep ", link), ("Learning", null)] -> "Deep Learning"
         */
        String text() {
            StringBuilder joined = new StringBuilder();
            segs.forEach(seg -> joined.append(seg.text()));
            return normalize(joined.toString());
        }

        /**
         * Input: none.
         * Output: the segments with an href and non-blank text, in order (icon-only links like ORCID badges left out).
         * Used by: {@link #soleAnchor}; AlertHtmlParser to collect a paper's links.
         * Example: [("Title", "https://x"), ("", "https://orcid.org/..")] -> [("Title", "https://x")]
         */
        List<Seg> anchors() {
            return segs.stream().filter(seg -> seg.href() != null && !normalize(seg.text()).isEmpty()).toList();
        }

        /**
         * The link, if the whole line is nothing but one link - the shape of a paper title. A "[PDF]"-style
         * format tag beside it (Google Scholar) does not count as other text.
         * Input: none.
         * Output: the single anchor if there is exactly one and no other visible non-link text; empty otherwise.
         * Used by: AlertHtmlParser to find title lines and to skip them as authors/venue/type.
         * Example: [("[PDF] ", null), ("Wave loads", url)] -> Seg("Wave loads", url); "See Wave loads here" -> empty
         */
        Optional<Seg> soleAnchor() {
            List<Seg> anchors = anchors();
            if (anchors.size() != 1) {
                return Optional.empty();
            }
            boolean otherText = segs.stream().anyMatch(seg -> seg.href() == null
                    && !FORMAT_TAGS.matcher(normalize(seg.text())).matches());
            return otherText ? Optional.empty() : Optional.of(anchors.get(0));
        }
    }

    private static final Set<String> BLOCK_TAGS = Set.of(
            "html", "body", "p", "div", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "li", "ul", "ol",
            "dl", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article", "header",
            "footer", "center", "pre", "form", "address", "figure", "figcaption", "main", "nav", "aside");
    private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");
    /** Nothing, or nothing but "[PDF]"-style format tags, as search alerts put beside a title. */
    private static final Pattern FORMAT_TAGS =
            Pattern.compile("(?i)(?:\\[(?:PDF|HTML|BOOK|B|CITATION|C|DOC)\\]\\s*)*");

    private AlertLines() {
    }

    /**
     * Input: the raw HTML body of an alert email.
     * Output: its visible lines in reading order (head/style/script, hidden elements, Gmail forward scaffolding removed).
     * Used by: AlertHtmlParser.parse (M2).
     * Example: see the class javadoc: a td with a link, br, authors, br, venue + hr -> 4 lines
     */
    static List<Line> parse(String html) {
        Document doc = Jsoup.parse(html);
        // Drop what a reader never sees, and the forwarder's own text.
        doc.select("head, style, script, noscript").remove();
        doc.select("[style~=(?i)display\\s*:\\s*none]").remove();
        doc.select("div.gmail_attr, .gmail_signature").remove();

        Builder builder = new Builder();
        builder.walk(doc.body());
        builder.flush();
        return builder.lines;
    }

    /**
     * Input: raw text taken from the HTML.
     * Output: NBSPs as spaces, zero-width characters removed, whitespace collapsed, trimmed.
     * Used by: {@link Line#text}, {@link Line#anchors}; AlertHtmlParser for link text.
     * Example: "[NBSP] Deep[ZWSP]  Learning [newline]" -> {@code "Deep Learning"}
     */
    static String normalize(String text) {
        return text.replace(' ', ' ')
                .replaceAll("[​-‍﻿]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final class Builder {
        final List<Line> lines = new ArrayList<>();
        private List<Seg> segs = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder anchorText = new StringBuilder();
        private String anchorHref;
        private int headingDepth;

        /**
         * Input: a DOM node.
         * Output: none; its subtree's text and links go onto lines: block/{@code <br>} ends a line, {@code <hr>} adds a
         * rule, {@code <img>} is skipped, {@code <a href>} becomes one link segment.
         * Used by: {@link AlertLines#parse}, recursively.
         * Example: {@code <p>A<br>B</p>} -> lines "A", "B"
         */
        void walk(Node node) {
            if (node instanceof TextNode textNode) {
                (anchorHref != null ? anchorText : text).append(textNode.getWholeText());
                return;
            }
            if (!(node instanceof Element element)) {
                return;
            }
            String tag = element.normalName();
            switch (tag) {
                case "br" -> {
                    // Inside a link a <br> is just a space: the title stays one segment.
                    if (anchorHref != null) {
                        anchorText.append(' ');
                    } else {
                        flush();
                    }
                }
                case "hr" -> {
                    flush();
                    lines.add(new Line(List.of(), false, true));
                }
                case "img" -> {
                }
                default -> {
                    if (tag.equals("a") && anchorHref == null && element.hasAttr("href")) {
                        walkAnchor(element);
                        return;
                    }
                    boolean block = anchorHref == null && BLOCK_TAGS.contains(tag);
                    if (block) {
                        flush();
                    }
                    if (HEADING_TAGS.contains(tag)) {
                        headingDepth++;
                    }
                    element.childNodes().forEach(this::walk);
                    if (block) {
                        flush();
                    }
                    if (HEADING_TAGS.contains(tag)) {
                        headingDepth--;
                    }
                }
            }
        }

        /**
         * Input: an {@code <a>} element with an href.
         * Output: none; it becomes one segment on the current line (blocks and {@code <br>} inside it don't split it).
         * Used by: {@link #walk}.
         * Example: {@code <a href="u"><div>Deep</div><b>Learning</b></a>} -> Seg("DeepLearning", "u")
         */
        private void walkAnchor(Element anchor) {
            pushText();
            anchorHref = anchor.attr("href");
            anchorText.setLength(0);
            anchor.childNodes().forEach(this::walk);
            segs.add(new Seg(anchorText.toString(), anchorHref));
            anchorHref = null;
            anchorText.setLength(0);
        }

        /**
         * Input: none.
         * Output: none; pending plain text (if any) becomes a non-link segment and the buffer is cleared.
         * Used by: {@link #walkAnchor}, {@link #flush}.
         * Example: text buffer "A. Smith" -> segs += Seg("A. Smith", null)
         */
        private void pushText() {
            if (!text.isEmpty()) {
                segs.add(new Seg(text.toString(), null));
                text.setLength(0);
            }
        }

        /**
         * Input: none.
         * Output: none; the current line is added (as a heading inside h1-h6) if it has visible text, and a new one started.
         * Used by: {@link #walk}, {@link AlertLines#parse}.
         * Example: segs [("  ", null)] -> nothing added; [("Nature", null)] -> line "Nature" added
         */
        void flush() {
            pushText();
            boolean visible = segs.stream().anyMatch(seg -> !normalize(seg.text()).isEmpty());
            if (visible) {
                lines.add(new Line(segs, headingDepth > 0, false));
            }
            segs = new ArrayList<>();
        }
    }
}
