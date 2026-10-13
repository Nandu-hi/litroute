package com.litroute.ingestion.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
         * The line's visible text.
         *
         * @return every segment's text (links included) concatenated in order, then cleaned up by
         *         {@link AlertLines#normalize(String)}
         */
        String text() {
            StringBuilder joined = new StringBuilder();
            segs.forEach(seg -> joined.append(seg.text()));
            return normalize(joined.toString());
        }

        /**
         * Links on this line that have visible text.
         *
         * @return the segments that have an href and non-blank normalized text, in line order; icon-only
         *         links such as ORCID badges have empty text and are left out
         */
        List<Seg> anchors() {
            return segs.stream().filter(seg -> seg.href() != null && !normalize(seg.text()).isEmpty()).toList();
        }

        /**
         * The link, if the whole line is nothing but one link - the shape of a paper title.
         *
         * @return the single anchor when {@link #anchors()} has exactly one entry and no non-link segment
         *         has visible text; empty otherwise
         */
        Optional<Seg> soleAnchor() {
            List<Seg> anchors = anchors();
            if (anchors.size() != 1) {
                return Optional.empty();
            }
            boolean otherText = segs.stream().anyMatch(seg -> seg.href() == null && !normalize(seg.text()).isEmpty());
            return otherText ? Optional.empty() : Optional.of(anchors.get(0));
        }
    }

    private static final Set<String> BLOCK_TAGS = Set.of(
            "html", "body", "p", "div", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "li", "ul", "ol",
            "dl", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article", "header",
            "footer", "center", "pre", "form", "address", "figure", "figcaption", "main", "nav", "aside");
    private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    private AlertLines() {
    }

    /**
     * Flattens an alert email's HTML into the lines a reader would see.
     *
     * @param html the raw HTML body of the alert email
     * @return the visible lines in reading order, after head/style/script, hidden elements and Gmail
     *         forwarding scaffolding are removed and the body is walked by {@link Builder}
     */
    static List<Line> parse(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("head, style, script, noscript").remove();
        doc.select("[style~=(?i)display\\s*:\\s*none]").remove();
        doc.select("div.gmail_attr, .gmail_signature").remove();

        Builder builder = new Builder();
        builder.walk(doc.body());
        builder.flush();
        return builder.lines;
    }

    /**
     * Cleans up raw HTML text for comparison and display.
     *
     * @param text raw text taken from the HTML
     * @return the text with non-breaking spaces turned into plain spaces, zero-width characters removed,
     *         whitespace runs collapsed to one space, and leading/trailing whitespace trimmed
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
         * Visits a node and its subtree depth-first, adding text and links to the current line.
         * Returns nothing; a block tag or {@code <br>} closes the current line, {@code <hr>} adds a rule
         * line, {@code <img>} is skipped, and {@code <a href>} becomes a single link segment.
         *
         * @param node the DOM node to visit (text nodes add text; elements are handled by tag)
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
         * Turns a link and everything inside it into one segment on the current line.
         * Returns nothing; pending plain text is saved first, then the anchor's text is collected (block
         * tags and {@code <br>} inside it do not split the line) and stored with its href.
         *
         * @param anchor the {@code <a>} element; it must have an href
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
         * Moves pending plain text into the current line as a non-link segment.
         *
         * Returns nothing; if the text buffer is non-empty it becomes a segment with a null href and the
         * buffer is cleared.
         */
        private void pushText() {
            if (!text.isEmpty()) {
                segs.add(new Seg(text.toString(), null));
                text.setLength(0);
            }
        }

        /**
         * Ends the current line.
         *
         * Returns nothing; pending text is saved, the line is added to {@link #lines} (marked as a heading
         * when inside an h1-h6) if any segment has visible text, and a new empty line is started.
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
