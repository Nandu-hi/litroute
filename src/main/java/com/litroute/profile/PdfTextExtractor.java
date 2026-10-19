package com.litroute.profile;

import com.litroute.util.Titles;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads a reference PDF into a {@link PaperText}: its title, abstract, section headings and body.
 *
 * PDFs carry no structure, only positioned glyphs, so the parts are recognized by typography and
 * wording. The text is read in the PDF's own content order, which keeps two-column papers'
 * columns intact but may put a page's elements (title, abstract, body) in any order - so the title is
 * found by font size rather than position, and the abstract by its "Abstract" heading:
 *
 * <ul>
 *   <li>title: the PDF's metadata title if it is a real title and (allowing for small differences)
 *       appears on the first pages, otherwise the largest-font text on page 1 that is not journal
 *       branding;
 *   <li>abstract: the text after an "Abstract" (or a report's "Executive Summary") heading, up to
 *       keywords or the next heading; if there is none, the opening text stands in for it;
 *   <li>headings: short lines set in bold, or larger than the body text;
 *   <li>keywords: the list after a "Keywords" (or "Index Terms") label on the first two pages, split at
 *       its separators (";", ",", "·") or, when it has none, one keyword per line;
 *   <li>body: everything after the abstract up to the references or other end matter, cut into
 *       passages and sampled evenly so long papers are represented beyond their introduction.
 * </ul>
 *
 * Running headers, footers and page numbers (lines repeated across pages) are dropped everywhere.
 */
public final class PdfTextExtractor {

    /**
     * PDFBox and FontBox log a warning for every glyph they cannot map to Unicode - thousands of lines
     * for a folder of real papers, all of it covered by this class's own readability checks. Held in
     * fields so the logger configuration is not garbage-collected.
     */
    private static final Logger PDFBOX_LOG = Logger.getLogger("org.apache.pdfbox");
    private static final Logger FONTBOX_LOG = Logger.getLogger("org.apache.fontbox");

    static {
        PDFBOX_LOG.setLevel(Level.SEVERE);
        FONTBOX_LOG.setLevel(Level.SEVERE);
    }

    /** Theses and books can run to hundreds of pages; the opening pages say what they are about. */
    static final int MAX_PAGES = 60;
    /** Roughly 350 tokens, so title + passage fits SPECTER2's 512-token input. */
    static final int CHUNK_CHARS = 1500;
    static final int MAX_BODY_CHUNKS = 6;
    private static final int MIN_CHUNK_CHARS = 200;
    private static final int MAX_ABSTRACT_CHARS = 3000;
    private static final int MIN_ABSTRACT_CHARS = 100;
    private static final int FALLBACK_ABSTRACT_CHARS = 2000;
    /** A report's executive summary can come after its front matter and table of contents. */
    private static final int ABSTRACT_SEARCH_PAGES = 10;
    /** Less text than this in the whole document means a scan without OCR, not a paper. */
    private static final int MIN_DOCUMENT_LETTERS = 500;
    /**
     * Below this share of letters among the non-space characters, the "text" is not words: glyph codes
     * from fonts without a Unicode mapping ("*+,-.+/-0.1-233"), or the symbols of a map or drawing.
     * Real documents, tables and equations included, stay above 0.6.
     */
    private static final double MIN_LETTER_SHARE = 0.5;
    /**
     * End matter is only looked for after this share of the document, so a report's
     * "Acknowledgments" page or a contents entry for "References" does not end the body early.
     */
    private static final double END_MATTER_EARLIEST = 0.25;
    /** Share of a metadata title's letters that may differ from the page text and still match. */
    private static final double METADATA_TITLE_TOLERANCE = 0.15;
    /** Points by which a title must exceed the body text size; less when it is also bold. */
    private static final float TITLE_MIN_SIZE_GAIN = 1.5f;
    private static final float BOLD_TITLE_MIN_SIZE_GAIN = 0.5f;
    private static final int MAX_HEADING_WORDS = 12;
    /** Author keywords kept per paper; journals ask for three to ten. */
    static final int MAX_KEYWORDS = 12;
    static final int MAX_KEYWORD_WORDS = 6;
    private static final int MAX_KEYWORD_CHARS = 80;
    private static final int MAX_KEYWORD_LINES = 12;
    /** Font sizes within this many points count as the same text style (sizes jitter within a paragraph). */
    private static final float SIZE_TOLERANCE = 1.1f;

    private static final Pattern ABSTRACT_START = Pattern.compile(
            "(?i)^(?:a\\s?b\\s?s\\s?t\\s?r\\s?a\\s?c\\s?t\\b[\\s.:\\-–—]*|(?:executive\\s+)?summary\\s*:?$)");
    private static final Pattern ABSTRACT_END = Pattern.compile(
            "^(?:(?i:key\\s?words?|index terms|(?:(?:\\d+|i)\\.?\\s+)?introduction\\b)|(?:\\d+|I)\\.?\\s+\\p{Lu})");
    private static final Pattern END_MATTER = Pattern.compile("^(?:(?:\\d+|[IVX]+|[A-Z])(?:\\.\\d+)*\\.?\\s+)?(?i:references"
            + "|bibliography|literature cited|works cited|reference list|acknowledge?ments?|declarations?|funding"
            + "|author(?:s'|s)? contributions?|competing interests?|conflicts? of interests?"
            + "|data availability(?: statement)?|availability of data and materials?)\\s*:?$");
    private static final Pattern KEYWORDS_START = Pattern.compile(
            "(?i)^(?:additional\\s+)?(?:author\\s+)?(?:k\\s?e\\s?y\\s?[-\\s]?w\\s?o\\s?r\\s?d\\s?s?(?:\\s+and\\s+phrases)?"
            + "|index\\s+terms)(?![a-z])[\\s.:\\-–—]*");
    /** What authors separate keywords with: "a; b", "a, b", Springer's "a · b", "a • b", "a | b". */
    private static final Pattern KEYWORD_SEPARATOR = Pattern.compile("\\s*[;,·•|]\\s*");
    /**
     * What a keyword list can run into on the same line: classification codes, a footnote ("riskAuthor for
     * correspondence"), or the first section ("change1. Introduction").
     */
    private static final Pattern KEYWORDS_END = Pattern.compile("(?i)\\*?\\s*(?:\\b(?:ams|mathematics|jel)\\s+"
            + "(?:subject\\s+)?classifications?|corresponding\\s+author|author\\s+for\\s+correspondence"
            + "|\\d*\\.?\\s*\\bintroduction\\b)");
    /** A footnote or section number run into the last word: "RC frames1". */
    private static final Pattern GLUED_NUMBER = Pattern.compile("(?<=\\p{Ll})\\d$");
    /** Words that make a line a sentence, which a keyword list never is. */
    private static final Pattern SENTENCE_WORD = Pattern.compile(
            "(?i)\\b(?:the|we|our|this|these|is|are|was|were|has|have|which|that)\\b");
    private static final Pattern CAPTION = Pattern.compile("(?i)^(?:fig\\.?|figure|table|algorithm|eq\\.)\\s*[\\dIVX]");
    private static final Pattern BRANDING = Pattern.compile(
            "(?i)journal|open access|research article|original (?:article|paper)|review article|https?:|www\\.|doi"
            + "|©|copyright|vol\\.|volume|issn|arxiv|preprint|proceedings|licen[cs]e|received:|accepted:|@"
            + "|view article online|research portal|escholarship");
    /** Elsevier-style masthead lines around the journal name, which is set as large as a title. */
    private static final Pattern CONTENTS_LINE = Pattern.compile("(?i)contents lists available");
    private static final Pattern HOMEPAGE_LINE = Pattern.compile("(?i)journal homepage|homepage:");
    /** Metadata titles that are word-processor or typesetting leftovers, not the paper's title. */
    private static final Pattern JUNK_METADATA = Pattern.compile("(?i)^(?:microsoft\\s+\\w+\\s+-|untitled|doi:|pii:"
            + "|powerpoint presentation)|template|peer-review article|\\.(?:docx?|dvi|pdf|tex|indd|ps)$");
    private static final Pattern LATEX_COMMAND = Pattern.compile("\\\\[A-Za-z]+");
    private static final Pattern FOOTNOTE_MARKS = Pattern.compile("[\\s*∗†‡]+$");
    /**
     * Starts with a capital or a section number, contains a real word, has none of the symbols found
     * in code listings, equations and author footnote marks, and does not end like a sentence.
     */
    private static final Pattern HEADING_TEXT =
            Pattern.compile("^[\\p{Lu}\\d](?=.*\\p{L}{3})[^=#\\[\\]{}_|<>@*∗†‡·]*(?<![.,;:])$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(?:\\d+(?:\\.\\d+)*\\.?|[IVX]+\\.)\\s+\\p{Lu}");
    /** Bold, semibold or medium weights by name, including suffix styles such as "AdvOT5fc32f66.B". */
    private static final Pattern BOLD_FONT =
            Pattern.compile("(?i)bold|black|heavy|semibd|demi|medi|cmbx|cmb10|[.,\\-](?:b|bd|bi|bx|sb)$");
    private static final Pattern HYPHENATED_END = Pattern.compile("\\p{L}-$");
    /** Zero-width characters, soft hyphens, and trademark signs (which NFKC would turn into "TM"). */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u00AD\\u2122\\u2120\\u00AE]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** One line of text as laid out on the page. */
    record Line(int page, String text, float size, boolean bold) {

        int words() {
            return text.split(" ").length;
        }
    }

    /**
     * @throws UnreadablePdfException if the file is not a readable PDF, is password-protected, or has
     *                                no extractable text
     */
    public PaperText extract(Path pdf) throws UnreadablePdfException {
        String metadataTitle;
        List<Line> lines;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            metadataTitle = document.getDocumentInformation().getTitle();
            LineCollector collector = new LineCollector();
            collector.setEndPage(MAX_PAGES);
            collector.getText(document);
            lines = collector.lines;
        } catch (InvalidPasswordException e) {
            throw new UnreadablePdfException("password-protected", e);
        } catch (IOException | RuntimeException e) {
            throw new UnreadablePdfException("not a readable PDF: " + e.getMessage(), e);
        }
        if (letterCount(lines) < MIN_DOCUMENT_LETTERS) {
            throw new UnreadablePdfException("no extractable text (a scan without OCR?)");
        }
        if (letterShare(lines) < MIN_LETTER_SHARE) {
            throw new UnreadablePdfException("too little of the text is words (undecodable fonts, or a map or drawing)");
        }
        return new Structure(withoutPageFurniture(lines), metadataTitle, fileStem(pdf)).toPaperText();
    }

    /** Recognizes the parts of one document's lines. */
    private static final class Structure {

        private final List<Line> lines;
        private final float bodySize;
        private final Set<Integer> titleLines = new HashSet<>();
        private final String title;
        private int abstractStart = -1;
        private int abstractEnd = -1;
        private String abstractText;

        Structure(List<Line> lines, String metadataTitle, String fileStem) {
            this.lines = lines;
            this.bodySize = bodyFontSize(lines);
            this.title = findTitle(metadataTitle, fileStem);
            findAbstract();
        }

        PaperText toPaperText() {
            int end = endMatterStart();
            List<String> headings = new ArrayList<>(new LinkedHashSet<>(headings(end)));
            String body = joinLines(bodyLines(end));
            String summary = abstractText != null ? abstractText : openingText();
            return new PaperText(title, summary, headings, sampleChunks(body), keywords());
        }

        /**
         * The author keywords: the list after the first "Keywords" label on pages 1-2 that is followed by
         * one. The list runs on over the following lines of the same page and type size while they read
         * like a list - no sentence words, no piece longer than {@value #MAX_KEYWORD_WORDS} words - and
         * ends at a line ending in a full stop, the abstract, or the introduction. A list with separators
         * may wrap mid-keyword, so a further line belongs to it only if it continues the list: it has a
         * separator, starts in lower case, or the line before ended in a separator or a hyphen.
         */
        private List<String> keywords() {
            for (int i = 0; i < lines.size() && lines.get(i).page() <= 2; i++) {
                Line label = lines.get(i);
                Matcher start = KEYWORDS_START.matcher(label.text());
                if (!start.lookingAt()) {
                    continue;
                }
                List<Line> taken = new ArrayList<>();
                String rest = label.text().substring(start.end());
                Line first = !rest.isBlank() ? new Line(label.page(), rest, label.size(), label.bold())
                        : i + 1 < lines.size() ? lines.get(i + 1) : null;
                if (first == null || first.page() != label.page()) {
                    continue;
                }
                boolean separated = KEYWORD_SEPARATOR.matcher(first.text()).find();
                if (!rest.isBlank()) {
                    taken.add(first);
                }
                for (int j = i + 1; j < lines.size() && taken.size() < MAX_KEYWORD_LINES; j++) {
                    if (!taken.isEmpty() && taken.get(taken.size() - 1).text().endsWith(".")) {
                        break;
                    }
                    if (!continuesKeywords(j, label.page(), taken, separated)) {
                        break;
                    }
                    taken.add(lines.get(j));
                }
                if (taken.isEmpty() || !readsAsList(taken.get(0).text(), separated)) {
                    continue;
                }
                // One keyword per line, unless a later line shows the list was separated after all.
                boolean split = separated || taken.stream().anyMatch(line -> KEYWORD_SEPARATOR.matcher(line.text()).find());
                List<String> written = split
                        ? Arrays.asList(KEYWORD_SEPARATOR.split(joinLines(taken)))
                        : taken.stream().map(Line::text).toList();
                Map<String, String> keywords = new LinkedHashMap<>();
                for (String keyword : written) {
                    Matcher end = KEYWORDS_END.matcher(keyword);
                    boolean last = end.find();
                    String cleaned = last ? keyword.substring(0, end.start()) : keyword;
                    cleaned = GLUED_NUMBER.matcher(cleaned.replaceAll("^[\\s\\-–—.:]+|[\\s.;,:]+$", "")).replaceAll("");
                    if (isKeyword(cleaned)) {
                        keywords.putIfAbsent(Titles.compact(cleaned), cleaned);
                    }
                    if (last) {
                        break;
                    }
                }
                if (!keywords.isEmpty()) {
                    return keywords.values().stream().limit(MAX_KEYWORDS).toList();
                }
            }
            return List.of();
        }

        /** Whether line {@code j} goes on with the keyword list {@code taken}. */
        private boolean continuesKeywords(int j, int page, List<Line> taken, boolean separated) {
            Line line = lines.get(j);
            String text = line.text();
            if (line.page() != page || titleLines.contains(j) || ABSTRACT_START.matcher(text).lookingAt()
                    || ABSTRACT_END.matcher(text).find() || KEYWORDS_START.matcher(text).lookingAt()
                    || BRANDING.matcher(text).find() || !readsAsList(text, separated)) {
                return false;
            }
            if (taken.isEmpty()) {
                return true;
            }
            Line previous = taken.get(taken.size() - 1);
            if (taken.size() > 1 || previous != lines.get(j - 1)) {
                // the label's own line may be set larger or bolder than the keywords after it
                if (Math.abs(line.size() - previous.size()) > SIZE_TOLERANCE || line.bold() != previous.bold()) {
                    return false;
                }
            }
            if (!separated) {
                return true;
            }
            String before = previous.text().strip();
            return KEYWORD_SEPARATOR.matcher(text).find() || before.endsWith("-")
                    || KEYWORD_SEPARATOR.matcher(before.substring(before.length() - 1)).matches()
                    || Character.isLowerCase(text.charAt(0)); // "Machine" + "learning": a keyword wrapped
        }

        /** No sentence words, and every piece between separators (or the line, if none) keyword-sized. */
        private static boolean readsAsList(String text, boolean separated) {
            if (SENTENCE_WORD.matcher(text).find()) {
                return false;
            }
            String[] pieces = separated ? KEYWORD_SEPARATOR.split(text) : new String[] {text};
            return Arrays.stream(pieces).allMatch(piece -> piece.isBlank() || words(piece) <= MAX_KEYWORD_WORDS);
        }

        private static boolean isKeyword(String text) {
            return text.chars().filter(Character::isLetter).count() >= 2 && words(text) <= MAX_KEYWORD_WORDS
                    && text.length() <= MAX_KEYWORD_CHARS && text.indexOf(':') < 0 && !BRANDING.matcher(text).find();
        }

        private static int words(String text) {
            return text.strip().split("\\s+").length;
        }

        private String findTitle(String metadataTitle, String fileStem) {
            String metadata = usableMetadataTitle(metadataTitle, fileStem);
            if (metadata != null) {
                String compact = Titles.compact(metadata);
                String opening = Titles.compact(joinLines(lines.stream().filter(l -> l.page() <= 2).toList()));
                if (appearsIn(compact, opening)) {
                    markTitleLines(compact);
                    return metadata;
                }
            }
            List<Line> run = largestTitleRun();
            if (!run.isEmpty()) {
                run.forEach(line -> titleLines.add(lines.indexOf(line)));
                return joinLines(run);
            }
            return metadata != null ? metadata : fileStem;
        }

        /**
         * Runs of consecutive same-style lines on page 1; the largest that reads like a title. A title
         * is set clearly larger than the body text (or larger and bold), which keeps author lists and
         * affiliations out.
         */
        private List<Line> largestTitleRun() {
            List<Line> best = List.of();
            float bestSize = bodySize;
            for (int i = 0; i < lines.size() && lines.get(i).page() == 1; ) {
                int j = i + 1;
                while (j < lines.size() && lines.get(j).page() == 1 && sameStyle(lines.get(i), lines.get(j))) {
                    j++;
                }
                List<Line> run = lines.subList(i, j);
                float size = run.get(0).size();
                boolean titleSized = size >= bodySize + TITLE_MIN_SIZE_GAIN
                        || (run.get(0).bold() && size >= bodySize + BOLD_TITLE_MIN_SIZE_GAIN);
                if (size > bestSize && titleSized && plausibleTitle(joinLines(run)) && !isJournalMasthead(i, j)) {
                    best = run;
                    bestSize = size;
                }
                i = j;
            }
            return best;
        }

        /** The journal name in an Elsevier-style masthead, set between these two banner lines. */
        private boolean isJournalMasthead(int start, int end) {
            boolean afterContentsLine = start > 0 && CONTENTS_LINE.matcher(lines.get(start - 1).text()).find();
            boolean beforeHomepageLine = end < lines.size() && HOMEPAGE_LINE.matcher(lines.get(end).text()).find();
            return afterContentsLine || beforeHomepageLine;
        }

        private void markTitleLines(String compactTitle) {
            for (int i = 0; i < lines.size() && lines.get(i).page() <= 2; i++) {
                String compactLine = Titles.compact(lines.get(i).text());
                if (compactLine.length() >= 4 && compactTitle.contains(compactLine)) {
                    titleLines.add(i);
                }
            }
        }

        private void findAbstract() {
            for (int i = 0; i < lines.size() && lines.get(i).page() <= ABSTRACT_SEARCH_PAGES; i++) {
                Line heading = lines.get(i);
                var start = ABSTRACT_START.matcher(heading.text());
                if (!start.find()) {
                    continue;
                }
                // "Abstract" alone on its line, or run-in: "Abstract—We propose ..."
                String first = heading.text().substring(start.end());
                if (heading.page() > 2 && !first.isBlank()) {
                    continue; // past the opening pages only a heading on its own line counts
                }
                List<Line> taken = new ArrayList<>();
                float abstractSize = heading.size();
                if (!first.isBlank()) {
                    taken.add(new Line(heading.page(), first, abstractSize, false));
                } else if (i + 1 < lines.size()) {
                    abstractSize = lines.get(i + 1).size();
                }
                int j = i + 1;
                int length = first.length();
                for (; j < lines.size() && length < MAX_ABSTRACT_CHARS; j++) {
                    Line line = lines.get(j);
                    boolean pastAbstract = length >= MIN_ABSTRACT_CHARS
                            && (isHeadingAt(j) || Math.abs(line.size() - abstractSize) > SIZE_TOLERANCE);
                    if (line.page() > heading.page() + 1 || ABSTRACT_END.matcher(line.text()).find() || pastAbstract) {
                        break;
                    }
                    if (!titleLines.contains(j)) {
                        taken.add(line);
                        length += line.text().length() + 1;
                    }
                }
                String text = joinLines(taken);
                if (text.length() >= MIN_ABSTRACT_CHARS) {
                    abstractStart = i;
                    abstractEnd = j;
                    abstractText = truncate(text, MAX_ABSTRACT_CHARS);
                    return;
                }
            }
        }

        /** The document's opening body text, when it has no abstract: page 1 on, past a report's cover. */
        private String openingText() {
            List<Line> opening = new ArrayList<>();
            int length = 0;
            for (int i = 0; i < lines.size() && length < FALLBACK_ABSTRACT_CHARS; i++) {
                Line line = lines.get(i);
                if (!titleLines.contains(i) && isBodySize(line) && !BRANDING.matcher(line.text()).find()) {
                    opening.add(line);
                    length += line.text().length() + 1;
                }
            }
            String text = joinLines(opening);
            return text.isBlank() ? title : truncate(text, FALLBACK_ABSTRACT_CHARS);
        }

        /** Index of the first end-matter heading (references, acknowledgements, ...), or lines.size(). */
        private int endMatterStart() {
            int earliest = Math.max(abstractEnd, (int) (lines.size() * END_MATTER_EARLIEST));
            for (int i = earliest; i < lines.size(); i++) {
                Line line = lines.get(i);
                if (line.words() <= 6 && END_MATTER.matcher(line.text()).matches()) {
                    return i;
                }
            }
            return lines.size();
        }

        private List<String> headings(int end) {
            return IntStream.range(0, end)
                    .filter(i -> isBodyLine(i) && !isFrontMatter(i) && isHeadingAt(i))
                    .mapToObj(i -> lines.get(i).text())
                    .toList();
        }

        /** Body-size text and headings; drops footnotes, captions, tables and figure labels. */
        private List<Line> bodyLines(int end) {
            return IntStream.range(0, end)
                    .filter(i -> isBodyLine(i) && !isFrontMatter(i) && (isBodySize(lines.get(i)) || isHeadingAt(i))
                            && !BRANDING.matcher(lines.get(i).text()).find())
                    .mapToObj(lines::get)
                    .toList();
        }

        private boolean isBodyLine(int i) {
            Line line = lines.get(i);
            boolean inAbstract = abstractStart >= 0 && i >= abstractStart && i < abstractEnd;
            boolean keywordsLine = line.page() <= 2 && line.text().toLowerCase(Locale.ROOT).startsWith("key");
            return !titleLines.contains(i) && !inAbstract && !keywordsLine;
        }

        /** Page 1 before the abstract: authors, affiliations, journal banners. */
        private boolean isFrontMatter(int i) {
            return abstractStart >= 0 && i < abstractStart && lines.get(i).page() == lines.get(abstractStart).page();
        }

        private boolean isBodySize(Line line) {
            return Math.abs(line.size() - bodySize) <= SIZE_TOLERANCE;
        }

        /**
         * Styled like a heading and either numbered ("3.1 Encoder") or leading - directly or through at
         * most two further headings - into a paragraph. Bold labels inside figures and tables lead
         * into other labels or a caption instead.
         */
        private boolean isHeadingAt(int i) {
            Line line = lines.get(i);
            if (!looksLikeHeading(line)) {
                return false;
            }
            if (NUMBERED_HEADING.matcher(line.text()).lookingAt()) {
                return true;
            }
            int next = i + 1;
            for (int hops = 0; next < lines.size() && hops < 2 && looksLikeHeading(lines.get(next)); hops++) {
                next++;
            }
            if (next >= lines.size()) {
                return false;
            }
            Line paragraph = lines.get(next);
            return isBodySize(paragraph) && paragraph.words() >= 6 && !CAPTION.matcher(paragraph.text()).find();
        }

        private boolean looksLikeHeading(Line line) {
            String text = line.text();
            if (line.words() > MAX_HEADING_WORDS || !HEADING_TEXT.matcher(text).matches()
                    || CAPTION.matcher(text).find() || BRANDING.matcher(text).find() || isLetterSpaced(text)
                    || ABSTRACT_START.matcher(text).lookingAt()) {
                return false;
            }
            return (line.bold() && line.size() >= bodySize - 0.5f) || line.size() >= bodySize + 1.5f;
        }

        private boolean sameStyle(Line a, Line b) {
            return Math.abs(a.size() - b.size()) < 0.6f && a.bold() == b.bold();
        }
    }

    /** The metadata title, cleaned, if it is a real title rather than a file name or tool leftover. */
    private static String usableMetadataTitle(String metadataTitle, String fileStem) {
        if (metadataTitle == null) {
            return null;
        }
        String title = FOOTNOTE_MARKS.matcher(clean(LATEX_COMMAND.matcher(metadataTitle).replaceAll(" "))).replaceAll("");
        if (!plausibleTitle(title) || JUNK_METADATA.matcher(title).find()
                || Titles.compact(title).equals(Titles.compact(fileStem))) {
            return null;
        }
        return title;
    }

    /**
     * Whether the (compacted) title occurs in the (compacted) text, allowing a few letters to differ -
     * a ligature the text lost, a hyphenation, a "Measurement" that the page spells "Measurements".
     * Approximate substring search: edit distance with the match free to start and end anywhere.
     */
    static boolean appearsIn(String title, String text) {
        if (title.length() < 10) {
            return text.contains(title);
        }
        int[] previous = new int[text.length() + 1];
        int[] current = new int[text.length() + 1];
        for (int i = 1; i <= title.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= text.length(); j++) {
                int substitution = previous[j - 1] + (title.charAt(i - 1) == text.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return Arrays.stream(previous).min().getAsInt() <= title.length() * METADATA_TITLE_TOLERANCE;
    }

    private static boolean plausibleTitle(String text) {
        String cleaned = clean(text);
        int words = cleaned.split(" ").length;
        return words >= 3 && words <= 40 && cleaned.length() <= 300 && !BRANDING.matcher(cleaned).find()
                && !isLetterSpaced(cleaned) && !cleaned.toLowerCase(Locale.ROOT).matches(".*\\.(?:docx?|pdf|tex)$");
    }

    /** "R E S E A R C H" - decorative spacing, not words. */
    private static boolean isLetterSpaced(String text) {
        String[] tokens = text.split(" ");
        long single = Arrays.stream(tokens).filter(t -> t.length() == 1).count();
        return tokens.length >= 4 && single * 2 > tokens.length;
    }

    /**
     * Drops running headers/footers and page numbers: lines that recur, ignoring digits, on several
     * pages, and lines that are only a number.
     */
    private static List<Line> withoutPageFurniture(List<Line> lines) {
        Map<String, Set<Integer>> pagesByKey = lines.stream().collect(
                Collectors.groupingBy(PdfTextExtractor::furnitureKey, Collectors.mapping(Line::page, Collectors.toSet())));
        int threshold = Math.max(3, lines.stream().mapToInt(Line::page).max().orElse(0) / 3);
        return lines.stream()
                .filter(line -> pagesByKey.get(furnitureKey(line)).size() < threshold)
                .filter(line -> !line.text().matches("(?i)(?:page\\s*)?\\d+(?:\\s*(?:of|/)\\s*\\d+)?"))
                .toList();
    }

    private static String furnitureKey(Line line) {
        return line.text().toLowerCase(Locale.ROOT).replaceAll("\\d+", "#");
    }

    /** The font size most of the text is set in. */
    private static float bodyFontSize(List<Line> lines) {
        Map<Float, Integer> charsBySize = lines.stream()
                .collect(Collectors.groupingBy(Line::size, Collectors.summingInt(line -> line.text().length())));
        return charsBySize.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(10f);
    }

    /** Joins lines into running text, undoing end-of-line hyphenation ("improv-" + "ing"). */
    static String joinLines(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            String next = line.text();
            if (text.length() > 0) {
                if (HYPHENATED_END.matcher(text).find() && !next.isEmpty() && Character.isLowerCase(next.charAt(0))) {
                    text.setLength(text.length() - 1);
                } else {
                    text.append(' ');
                }
            }
            text.append(next);
        }
        return text.toString();
    }

    /** Cuts the body into passages at word boundaries and keeps up to MAX_BODY_CHUNKS spread evenly. */
    static List<String> sampleChunks(String body) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + CHUNK_CHARS, body.length());
            if (end < body.length()) {
                int space = body.lastIndexOf(' ', end);
                if (space > start + CHUNK_CHARS / 2) {
                    end = space;
                }
            }
            String chunk = body.substring(start, end).trim();
            if (chunk.length() >= MIN_CHUNK_CHARS) {
                chunks.add(chunk);
            }
            start = end;
        }
        if (chunks.size() <= MAX_BODY_CHUNKS) {
            return chunks;
        }
        return IntStream.range(0, MAX_BODY_CHUNKS)
                .mapToObj(i -> chunks.get((int) Math.round(i * (chunks.size() - 1) / (double) (MAX_BODY_CHUNKS - 1))))
                .toList();
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int space = text.lastIndexOf(' ', maxChars);
        return text.substring(0, space > maxChars / 2 ? space : maxChars);
    }

    private static int letterCount(List<Line> lines) {
        return (int) lines.stream().flatMapToInt(line -> line.text().chars()).filter(Character::isLetter).count();
    }

    private static double letterShare(List<Line> lines) {
        long nonSpace = lines.stream().flatMapToInt(line -> line.text().chars()).filter(c -> !Character.isWhitespace(c)).count();
        return nonSpace == 0 ? 0 : letterCount(lines) / (double) nonSpace;
    }

    /** Ligatures and compatibility forms ("ﬁ", full-width letters) become plain text; spacing is collapsed. */
    private static String clean(String text) {
        String normalized = Normalizer.normalize(INVISIBLE.matcher(text).replaceAll(""), Normalizer.Form.NFKC);
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    private static String fileStem(Path pdf) {
        String name = pdf.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return clean((dot > 0 ? name.substring(0, dot) : name).replace('_', ' '));
    }

    /** Collects each text line with the font size and weight most of its glyphs are set in. */
    private static final class LineCollector extends PDFTextStripper {

        private final List<Line> lines = new ArrayList<>();
        private final List<PendingLine> pageLines = new ArrayList<>();
        private StringBuilder text = new StringBuilder();
        private List<TextPosition> glyphs = new ArrayList<>();

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            if (textPositions.stream().noneMatch(glyph -> glyph.getDir() == 0)) {
                return; // rotated text: arXiv's margin stamp, sideways tables
            }
            text.append(string);
            glyphs.addAll(textPositions);
        }

        @Override
        protected void writeWordSeparator() {
            text.append(' ');
        }

        @Override
        protected void writeLineSeparator() {
            endLine();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            endLine();
            for (PendingLine line : attachStrayGlyphs(pageLines)) {
                String cleaned = clean(line.text.toString());
                if (!cleaned.isEmpty()) {
                    lines.add(new Line(getCurrentPageNo(), cleaned, dominantSize(line.glyphs), mostlyBold(line.glyphs)));
                }
            }
            pageLines.clear();
            super.endPage(page);
        }

        private void endLine() {
            if (!glyphs.isEmpty() && !text.toString().isBlank()) {
                pageLines.add(new PendingLine(text, glyphs));
            }
            text = new StringBuilder();
            glyphs = new ArrayList<>();
        }

        /**
         * Some PDF producers draw the first glyph of every line on its own, ahead of the rest of the
         * page, so it arrives as a one-letter line and the real line lacks it ("ybrid" for "Hybrid").
         * Each such stray glyph is put back in front of the line that continues it on the same
         * baseline; strays nothing continues stay as they are.
         */
        static List<PendingLine> attachStrayGlyphs(List<PendingLine> lines) {
            List<PendingLine> strays = lines.stream().filter(PendingLine::isStray)
                    .sorted(Comparator.comparingDouble(PendingLine::startX).reversed()).toList();
            if (strays.isEmpty()) {
                return lines;
            }
            Set<PendingLine> attached = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PendingLine stray : strays) {
                TextPosition last = stray.glyphs.get(stray.glyphs.size() - 1);
                float size = Math.max(last.getFontSizeInPt(), 4f);
                float end = last.getXDirAdj() + last.getWidthDirAdj();
                for (PendingLine line : lines) {
                    if (line == stray || attached.contains(line)) {
                        continue;
                    }
                    TextPosition first = line.glyphs.get(0);
                    float gap = first.getXDirAdj() - end;
                    if (Math.abs(first.getYDirAdj() - last.getYDirAdj()) <= 0.3f * size
                            && gap >= -0.5f && gap <= 0.6f * size) {
                        line.prepend(stray, gap > 0.15f * size ? " " : "");
                        attached.add(stray);
                        break;
                    }
                }
            }
            return lines.stream().filter(line -> !attached.contains(line)).toList();
        }

        /** Most common glyph size, to the nearest half point - an average is skewed by sub/superscripts. */
        private static float dominantSize(List<TextPosition> glyphs) {
            Map<Float, Long> counts = glyphs.stream()
                    .collect(Collectors.groupingBy(glyph -> Math.round(glyph.getFontSizeInPt() * 2) / 2f, Collectors.counting()));
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        }

        private static boolean mostlyBold(List<TextPosition> glyphs) {
            List<TextPosition> visible = glyphs.stream().filter(glyph -> !glyph.getUnicode().isBlank()).toList();
            long bold = visible.stream().filter(glyph -> isBold(glyph.getFont())).count();
            return !visible.isEmpty() && bold * 10 >= visible.size() * 6L;
        }

        private static boolean isBold(PDFont font) {
            if (font == null) {
                return false;
            }
            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600)) {
                return true;
            }
            String name = font.getName();
            return name != null && BOLD_FONT.matcher(name).find();
        }
    }

    /** A line still being assembled, with the glyphs it was drawn from. */
    private record PendingLine(StringBuilder text, List<TextPosition> glyphs) {

        /** One or two glyphs drawn apart from any word: a candidate for the line it belongs to. */
        boolean isStray() {
            String content = text.toString().strip();
            return !content.isEmpty() && content.length() <= 2 && content.indexOf(' ') < 0;
        }

        double startX() {
            return glyphs.get(0).getXDirAdj();
        }

        void prepend(PendingLine stray, String separator) {
            text.insert(0, stray.text.toString().strip() + separator);
            glyphs.addAll(0, stray.glyphs);
        }
    }
}
