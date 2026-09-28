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
 */
final class AlertLines {

    /** A run of text; href is non-null when the run is the text of a link. */
    record Seg(String text, String href) {
    }

    record Line(List<Seg> segs, boolean heading, boolean rule) {

        String text() {
            StringBuilder joined = new StringBuilder();
            segs.forEach(seg -> joined.append(seg.text()));
            return normalize(joined.toString());
        }

        /** Links on this line that have visible text (icon-only links such as ORCID badges are ignored). */
        List<Seg> anchors() {
            return segs.stream().filter(seg -> seg.href() != null && !normalize(seg.text()).isEmpty()).toList();
        }

        /** The link, if the whole line is nothing but one link - the shape of a paper title. */
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

        private void walkAnchor(Element anchor) {
            pushText();
            anchorHref = anchor.attr("href");
            anchorText.setLength(0);
            anchor.childNodes().forEach(this::walk);
            segs.add(new Seg(anchorText.toString(), anchorHref));
            anchorHref = null;
            anchorText.setLength(0);
        }

        private void pushText() {
            if (!text.isEmpty()) {
                segs.add(new Seg(text.toString(), null));
                text.setLength(0);
            }
        }

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
