package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.model.PaperCandidate;
import com.litroute.model.RawEmail;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

/**
 * M2 PaperExtractor against the six alert emails the client (Prof. Prakash Badal) forwarded as
 * references: SpringerOpen (x2), Taylor &amp; Francis, Google Scholar, ScienceDirect and ASCE. Each
 * fixture in src/test/resources/samples/ reproduces the forward exactly as Gmail delivers one: the
 * forwarder's note and signature, the "Forwarded message" header block, then the original HTML.
 *
 * The visible text is copied from the PDF prints of those mails. A PDF print keeps no hrefs, so the
 * link targets were rebuilt: each paper's real DOI, looked up in Crossref from the title the mail
 * shows, dropped into that publisher's real link style - a direct DOI link, a redirect wrapper
 * carrying the real URL, or an opaque tracking token. So a DOI asserted here is the paper's own.
 *
 * What the tests pin down:
 * - one candidate per paper, in email order, no matter how many papers or links the email has;
 * - the paper is never lost when its link is unusable (opaque token): it comes out without a DOI and
 *   M3 resolves it by title;
 * - a link that IS decodable (percent-encoded wrapper, DOI in the path) yields a bare DOI/URL;
 * - alert furniture (journal-name link, Full Text/PDF, unsubscribe, footers) and the forwarder's own
 *   note/signature never become papers or authors.
 */
public class PaperExtractorTest {

    private final PaperExtractor extractor = PaperExtractor.offline();

    // ---------------------------------------------------------------- SpringerOpen

    @Test
    public void springerOpenRelevantMailYieldsOnlyTheReviewArticle() {
        List<PaperCandidate> papers = extract("springeropen_relevant.html",
                "New Articles From Journal of Infrastructure Preservation and Resilience");

        // Not also the journal-name link, "Full Text", "PDF", the signature link, or "unsubscribe here".
        assertEquals(1, papers.size());
        PaperCandidate paper = papers.get(0);
        // The title is wrapped across two lines (<br>) in the email; it must come out as one line.
        assertEquals("Advancing soil-structure interaction (SSI): a comprehensive review of current "
                + "practices, challenges, and future directions", paper.title());
        // "A, B … C": the ellipsis marks omitted authors; the three visible names are kept, "…" is not a name.
        assertEquals(List.of("Imtiyaz Akbar Najar", "Raudhah Ahmadi", "Shanshan Tang"), paper.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), paper.venue());
        assertEquals(Optional.of(2025), paper.year());
        assertTrue(paper.articleType().orElseThrow().equalsIgnoreCase("Review"));
        // Full Text link: ".../articles/<doi>?utm_..."; PDF link: ".../pdf/<doi>.pdf". Both must give the same bare DOI.
        assertEquals(Optional.of("10.1186/s43065-025-00118-2"), paper.doi());
        assertTrue(paper.url().orElseThrow().startsWith("https://jipr.springeropen.com/articles/10.1186/s43065-025-00118-2"));
    }

    /** Same journal, same layout, but every link is an opaque tracking token: the paper must survive without a DOI. */
    @Test
    public void springerOpenIrrelevantMailKeepsThePaperEvenThoughItsLinksAreOpaque() {
        List<PaperCandidate> papers = extract("springeropen_irrelevant.html",
                "New Articles From Journal of Infrastructure Preservation and Resilience");

        assertEquals(1, papers.size());
        PaperCandidate paper = papers.get(0);
        // Non-ASCII letters must be preserved as-is.
        assertEquals("Hygrothermal analysis of ceramic coating durability under thermal fluctuations "
                + "on building facades in Brasília", paper.title());
        assertEquals(List.of("Gilberto Gomes", "Luis Silva", "Elton Bauer"), paper.authors());
        assertEquals(Optional.of("Journal of Infrastructure Preservation and Resilience"), paper.venue());
        assertEquals(Optional.of(2025), paper.year());
        assertTrue(paper.articleType().orElseThrow().equalsIgnoreCase("Research"));
        assertEquals("nothing to decode in an opaque link; M3 will resolve this one by title", Optional.empty(), paper.doi());
    }

    // ---------------------------------------------------------------- Taylor & Francis

    @Test
    public void tandfMailYieldsOnePaperEvenThoughTitleAndReadArticleBothLinkToIt() {
        List<PaperCandidate> papers = extract("tandfonline_alert.html",
                "New articles for Sustainable and Resilient Infrastructure are now available online");

        // Not the "New articles for <journal>..." headline, "View latest articles", "Read article", "manage your alerts".
        assertEquals(1, papers.size());
        PaperCandidate paper = papers.get(0);
        assertEquals("Readiness for sensing technologies and data analytics in multi-hazard disaster risk "
                + "reduction: a case study from Greater Bandung, Indonesia", paper.title());
        // "... M. A. Viqolbi & others": initials with periods stay intact, "others" is not an author.
        assertEquals(List.of("R. Wirahadikusumah", "M. Abduh", "P. Kusumaningrum", "D. Apoji", "M. A. Viqolbi"),
                paper.authors());
        // The alert names the journal only in its headline, not in the paper's own block.
        assertEquals(Optional.of("Sustainable and Resilient Infrastructure"), paper.venue());
        assertEquals("the alert states no publication year; the forward's Sep 2026 date must not be used",
                Optional.empty(), paper.year());
        assertEquals(Optional.of("Research Article"), paper.articleType());
        assertEquals("query string (?src=alert&utm_...) must be stripped",
                Optional.of("10.1080/23789689.2026.2723803"), paper.doi());
        assertTrue(paper.abstractSnippet().orElseThrow().startsWith("Greater Metropolitan Bandung, Indonesia"));
    }

    // ---------------------------------------------------------------- Google Scholar

    @Test
    public void googleScholarMailUnwrapsTheRedirectLinkAndSplitsTheCitationLine() {
        List<PaperCandidate> papers = extract("google_scholar_alert.html", "Recommended articles");

        // Not "See all recommendations", "LIST ALERTS", "CANCEL ALERT", "your profile", or the four icon-only share links.
        assertEquals(1, papers.size());
        PaperCandidate paper = papers.get(0);
        // The "[PDF]" tag is a link-type marker, not part of the title. Scholar uses U+2010 hyphens, kept as-is.
        assertEquals("An Intensity Measure‐Based Methodology for Ground Motion Orientation in "
                + "Seismic Structural Assessment", paper.title());
        // "S Londono-Lopez, R Cucuzza, F Mollaioli, GC Marano… - Earthquake Engineering & …, 2026"
        assertEquals(List.of("S Londono‐Lopez", "R Cucuzza", "F Mollaioli", "GC Marano"), paper.authors());
        // Scholar truncates the venue itself ("Earthquake Engineering & …"); keep what is there, minus the ellipsis.
        assertTrue(paper.venue().orElseThrow().startsWith("Earthquake Engineering"));
        assertFalse(paper.venue().orElseThrow().contains("…"));
        assertEquals(Optional.of(2026), paper.year());
        // scholar_url?url=<percent-encoded real target>: unwrap it, then read the DOI from the real target.
        assertTrue(paper.url().orElseThrow().startsWith("https://onlinelibrary.wiley.com/doi/pdf/10.1002/eqe.70286"));
        assertFalse(paper.url().orElseThrow().contains("scholar.google.com"));
        assertEquals(Optional.of("10.1002/eqe.70286"), paper.doi());
        assertTrue(paper.abstractSnippet().orElseThrow().startsWith("In current dynamic time history assessment protocols"));
    }

    // ---------------------------------------------------------------- ScienceDirect / Elsevier

    private record ScienceDirectPaper(String title, List<String> authors, String venue, int year, String type) {
    }

    private static final List<ScienceDirectPaper> SCIENCEDIRECT_PAPERS = List.of(
            new ScienceDirectPaper(
                    "Drought-flood coexistence: Risk perception and adaptation of urban and rural households in Southwest Madagascar",
                    List.of("Jonathan Ahadi Mahamba", "Moïse Musubao Kapiri", "Wendzoodo Amédée Baga",
                            "Lila Norolalaina Randrianandrasana", "Florence De Longueville"),
                    "International Journal of Disaster Risk Reduction", 2024, "Research article"),
            new ScienceDirectPaper(
                    "Fragility evaluation of bridge shallow foundation piers under floods by coupling simulation in structural and hydraulic fields",
                    List.of("Daniu Zhang", "Wen Xiong", "Xiaolong Ma", "Dihui Zhou", "C.S. Cai"),
                    "Ocean Engineering", 2024, "Research article"),
            new ScienceDirectPaper(
                    "Evaluation and prediction of compound geohazards in highly urbanized regions across China's Greater Bay Area",
                    List.of("Kunlong He", "Xiaohong Chen", "Xuan Yu", "Chunyu Dong", "Dongmei Zhao"),
                    "Journal of Cleaner Production", 2024, "Research article"),
            new ScienceDirectPaper(
                    "A review of deformation and fragmentation behavior of high-strength steels under impact: Mechanisms, models, and future directions",
                    List.of("Yilei Yu", "Minghui Ma", "Chengjuan Xie", "Guangfa Gao"),
                    "International Journal of Impact Engineering", 2025, "Review Article"),
            new ScienceDirectPaper(
                    "An efficient ground motion model coupling spatial correlations for large-scale field simulation",
                    List.of("Wenji Fang", "Xizhong Cui", "Duofa Ji", "Changhai Zhai"),
                    "Soil Dynamics and Earthquake Engineering", 2025, "Research article"));

    @Test
    public void scienceDirectMailYieldsAllFivePapersWithEachOnesOwnJournalAndAuthors() {
        List<PaperCandidate> papers = extract("sciencedirect_recommendations.html",
                "New recommendations available on ScienceDirect");

        // Not the "Hello Prakash, ..." greeting, "View more recommendations", "Support | Unsubscribe", or the footer IDs.
        assertEquals(SCIENCEDIRECT_PAPERS.size(), papers.size());
        for (int i = 0; i < SCIENCEDIRECT_PAPERS.size(); i++) {
            ScienceDirectPaper expected = SCIENCEDIRECT_PAPERS.get(i);
            PaperCandidate actual = papers.get(i);
            String which = "paper #" + (i + 1);
            assertEquals(which, expected.title(), actual.title());
            assertEquals(which, expected.authors(), actual.authors());
            // "Ocean Engineering, Volume 311, Part 2, August 2024, Article Number 118952" -> just the journal.
            assertEquals(which, Optional.of(expected.venue()), actual.venue());
            // ...and the year comes from that same line ("August 2024"), not from the forward's date (2026).
            assertEquals(which, Optional.of(expected.year()), actual.year());
            assertEquals(which, Optional.of(expected.type()), actual.articleType());
        }
    }

    @Test
    public void scienceDirectPapersWithOpaqueTrackingLinksAreKeptAndNeverGetABogusDoi() {
        List<PaperCandidate> papers = extract("sciencedirect_recommendations.html",
                "New recommendations available on ScienceDirect");

        // Papers 1-3: click-tracking wrapper with the real ScienceDirect URL embedded percent-encoded -> unwrapped.
        for (int i = 0; i < 3; i++) {
            String url = papers.get(i).url().orElseThrow();
            assertTrue("paper #" + (i + 1) + " url was " + url,
                    url.startsWith("https://www.sciencedirect.com/science/article/pii/S000000000000000" + (i + 1)));
        }
        // Papers 4-5: fully opaque tokens. Still emitted with full text metadata, just nothing decodable.
        for (int i = 3; i < 5; i++) {
            assertFalse(papers.get(i).title().isBlank());
            assertFalse(papers.get(i).authors().isEmpty());
        }
        // A ScienceDirect "pii" is not a DOI, and no ID-looking string in the mail (Subscription ID etc.) may become one.
        for (PaperCandidate paper : papers) {
            assertEquals(Optional.empty(), paper.doi());
        }
    }

    // ---------------------------------------------------------------- ASCE Library (22 papers in one mail)

    private static final List<String> ASCE_TITLES = List.of(
            "Research Hotspots and Evolutionary Trends ofEarthquake-Induced Landslides: A BibliometricAnalysis Based on Web of Science",
            "Multidimensional Fragility Analysis of Earth Slopes under Pulse-Like Ground Motions",
            "Mapping and Prioritizing Barriers to Timely Rural Recovery Affected by Hurricanes",
            "Integrated Risk Assessment of Landfill Flowslides: Sensitivity Analysis of the Initiation Mechanism and Hazard Potential",
            "Risk Identification and Resilience Enhancement for Rainstorm Disaster Chains: An Event Evolutionary Graph–Driven Approach",
            "Seismic Stability Analysis of Vertically Expanded Landfills under Different Leachate Buildup Conditions",
            "Key Determinants of Willingness to Adopt Building Seismic Retrofitting in Taiwan",
            "Investigating How the Public’s Understanding of Coastal Flooding Decision Support Systems Differs from the Experts: Results from an Eye-Tracking Study",
            "Roadway Traffic Assessment of the 2023 Maui Wildfires",
            "Improved Landslide Detection in Southeastern Xizang Based on L-DCGAN-Augmented Data and the L-YOLOv8 Model",
            "Comprehensive Seismic Risk Zonation and Structural Fragility Assessment of Urban Building Typologies through Fuzzy and Probabilistic Modeling Approaches: Case Study of Muscat",
            "New Approach for Simulating Population Outmigration: Combining Linear and Agent-Based Models",
            "Urban Waterlogging Risk Measurement Considering Lifeline Safety: A Case Study of Suqian, China",
            "Testing Flood-Resilience Measures with Indices: Case Studies in Lower Kelani Basin, Sri Lanka, and Applicability to the Developing World",
            "Hope for Education Remains in the Aftermath of Destructive Earthquakes: Postdisaster Experiences of Volunteer Teachers in Türkiye",
            "Climate-Adaptive Cost–Benefit Analysis Framework for Flood Protection Investment in Australia",
            "Preparing for Inevitable Surprise: Managing Unexpected Interinfrastructural Connectivities in Post-Disaster Infrastructure Service Restoration",
            "Discussion of “Revisions to the Nepalese Building Code(NBC 105:2020): New Seismic Hazard Provisions”",
            "Closure to “Revisions to the Nepalese Building Code (NBC 105:2020): New Seismic Hazard Provisions”",
            "Review of U.S. Emergency Management in the 21st Century: From Disaster to Catastrophe edited by Susan L. Cutter, Melanie Gall, and Claire B. Rubin",
            "Review of Fire and Flood: Extreme Events and Social Change Past, Present, Future by Thomas Princen",
            "Erratum for “Advances in Landslide Detection through Google Earth Engine: A Comprehensive Review”");

    /** The issue's 22 DOIs in email order, as Crossref holds them for Natural Hazards Review 27(4). */
    private static final List<String> ASCE_DOIS = List.of(
            "10.1061/nhrefo.nheng-2717", "10.1061/nhrefo.nheng-2673",
            "10.1061/nhrefo.nheng-2760", "10.1061/nhrefo.nheng-2770",
            "10.1061/nhrefo.nheng-2781", "10.1061/nhrefo.nheng-2870",
            "10.1061/nhrefo.nheng-2792", "10.1061/nhrefo.nheng-2771",
            "10.1061/nhrefo.nheng-2502", "10.1061/nhrefo.nheng-2872",
            "10.1061/nhrefo.nheng-2752", "10.1061/nhrefo.nheng-2686",
            "10.1061/nhrefo.nheng-2753", "10.1061/nhrefo.nheng-2721",
            "10.1061/nhrefo.nheng-2496", "10.1061/nhrefo.nheng-2859",
            "10.1061/nhrefo.nheng-2711", "10.1061/nhrefo.nheng-2818",
            "10.1061/nhrefo.nheng-2950", "10.1061/nhrefo.nheng-2979",
            "10.1061/nhrefo.nheng-3008", "10.1061/nhrefo.nheng-2953");

    /** Section heading each of the 22 papers sits under, in the same order as ASCE_TITLES. */
    private static final List<String> ASCE_SECTIONS = List.of(
            "State-of-the-Art Reviews",
            "Technical Papers", "Technical Papers", "Technical Papers", "Technical Papers", "Technical Papers",
            "Technical Papers", "Technical Papers", "Technical Papers", "Technical Papers", "Technical Papers",
            "Technical Papers", "Technical Papers",
            "Case Studies", "Case Studies", "Case Studies",
            "Technical Notes",
            "Discussions and Closures", "Discussions and Closures",
            "Book Reviews", "Book Reviews",
            "Corrections");

    private List<PaperCandidate> extractAsce() {
        return extract("asce_natural_hazards_review.html",
                "New articles have been published Natural Hazards Review, Volume 27, Issue 4");
    }

    /**
     * A table-of-contents mail: 22 papers. Discussions, closures, book reviews and an erratum are
     * extracted like any other entry - deciding they are unwanted is a scoring/relevance question,
     * not an extraction one - and titles are kept exactly as the publisher wrote them, including the
     * missing spaces in "ofEarthquake-Induced", "BibliometricAnalysis" and "Code(NBC" (M3's Crossref
     * lookup returns the corrected title).
     */
    @Test
    public void asceMailYieldsEveryTableOfContentsEntryInOrder() {
        List<PaperCandidate> papers = extractAsce();

        assertEquals(ASCE_TITLES, papers.stream().map(PaperCandidate::title).toList());
    }

    @Test
    public void asceEntriesCarryTheIssueHeaderAsVenueAndYearAndTheirSectionAsArticleType() {
        List<PaperCandidate> papers = extractAsce();

        assertEquals(ASCE_TITLES.size(), papers.size());
        for (int i = 0; i < papers.size(); i++) {
            String which = "entry #" + (i + 1);
            // Header "Natural Hazards Review / VOLUME: 27 | NUMBER: 4 | November 2026" applies to every entry.
            assertEquals(which, Optional.of("Natural Hazards Review"), papers.get(i).venue());
            assertEquals(which, Optional.of(2026), papers.get(i).year());
            assertEquals(which, Optional.of(ASCE_SECTIONS.get(i)), papers.get(i).articleType());
        }
    }

    @Test
    public void asceEntriesEachGetTheirOwnDoiFromTheirTitleLink() {
        List<PaperCandidate> papers = extractAsce();

        Set<String> dois = new HashSet<>();
        for (int i = 0; i < papers.size(); i++) {
            assertEquals("entry #" + (i + 1), Optional.of(ASCE_DOIS.get(i)), papers.get(i).doi());
            dois.add(papers.get(i).doi().orElseThrow());
        }
        assertEquals(22, dois.size());
    }

    /** ORCID icons sit between names (rendering as "Chong Xu , Yuandong"), and last names wrap onto "and ...". */
    @Test
    public void asceAuthorListsSurviveOrcidIconsInitialsAndHyphenatedNames() {
        List<PaperCandidate> papers = extractAsce();

        assertEquals(List.of("Qing Yang", "Xiangli He", "Chong Xu", "Yuandong Huang", "Yinxin Zhao", "Yuxue Ma", "Weibo Ji"),
                papers.get(0).authors());
        assertEquals(List.of("Qi-Teng Zheng", "Ying-Ying Meng", "An-Zheng Li", "Shi-Jin Feng", "Yong Zhao", "Ya-Dong Zhou"),
                papers.get(3).authors());
        assertEquals(List.of("H. G. D. Sanduni Ashvini", "N. K. Gunasekara", "W. C. D. K. Fernando", "R. Prasanna"),
                papers.get(13).authors());
        assertEquals(List.of("Rama M. Pokhrel", "Raffaele De Risi", "Flavia De Luca", "Charlotte E. L. Gilder",
                "Maximilian J. Werner", "Anastasios Sextos", "Paul J. Vardanega"), papers.get(18).authors());
        assertEquals(List.of("Mir Mujtaba Laghari", "Surih Sibaghatullah Jagirani", "Nirandoal Cheng", "Muhammad Irfan",
                "Irshad Ali Zardari", "Uwizeyimana David", "Zohaib Akbar", "Yang Zewen", "Muhammad Abu Bakar"),
                papers.get(21).authors());
    }

    /**
     * The title of a book review itself contains "edited by A, B, and C" - that must stay in the title, and
     * the line underneath (the reviewer) is the only author.
     */
    @Test
    public void asceBookReviewKeepsTheEditorsInTheTitleAndTheReviewerAsAuthor() {
        List<PaperCandidate> papers = extractAsce();

        assertTrue(papers.get(19).title().endsWith("edited by Susan L. Cutter, Melanie Gall, and Claire B. Rubin"));
        assertEquals(List.of("Samantha Montano"), papers.get(19).authors());
        assertEquals(List.of("Michelle A. Ritchie"), papers.get(20).authors());
    }

    // ---------------------------------------------------------------- Across all six mails

    private static final List<String> ALL_SAMPLES = List.of(
            "springeropen_relevant.html", "springeropen_irrelevant.html", "tandfonline_alert.html",
            "google_scholar_alert.html", "sciencedirect_recommendations.html", "asce_natural_hazards_review.html");

    private static final Set<String> ALERT_FURNITURE_TEXT = Set.of(
            "full text", "pdf", "here", "read article", "view latest articles", "view more recommendations",
            "see all recommendations", "list alerts", "cancel alert", "unsubscribe", "unsubscribe here",
            "unsubscribe from this alert", "manage your alerts", "update your preferences", "help", "support",
            "privacy policy", "your profile", "sitemap", "terms of use", "subscribe", "contact us",
            "journal of infrastructure preservation and resilience", "sustainable and resilient infrastructure",
            "natural hazards review", "springer nature", "elsevier b.v.", "terms and conditions",
            "prakash s badal", "https://sites.google.com/view/prakashbadal/");

    @Test
    public void noAlertFurnitureOrForwarderTextIsEverExtractedAsAPaperOrAuthor() {
        for (String sample : ALL_SAMPLES) {
            for (PaperCandidate paper : extract(sample, "irrelevant for this check")) {
                String where = sample + " -> " + paper.title();
                assertFalse(where, ALERT_FURNITURE_TEXT.contains(paper.title().toLowerCase()));
                assertFalse(where, paper.url().orElse("").startsWith("mailto:"));
                assertFalse(where, paper.url().orElse("").contains("sites.google.com"));
                assertFalse(where, paper.url().orElse("").toLowerCase().contains("unsubscribe"));
                for (String author : paper.authors()) {
                    assertFalse(where + " author=" + author, author.isBlank());
                    assertFalse(where + " author=" + author, author.equalsIgnoreCase("others"));
                    assertFalse(where + " author=" + author, author.contains("…"));
                    assertFalse(where + " author=" + author, author.contains("Prakash"));
                }
            }
        }
    }

    @Test
    public void everyCandidateNamesItsSourceEmailAndEachPaperAppearsOnlyOncePerMail() {
        for (String sample : ALL_SAMPLES) {
            RawEmail email = forwardedSample(sample, "irrelevant for this check");
            List<PaperCandidate> papers = extractor.extract(email);

            assertFalse(sample, papers.isEmpty());
            Set<String> titles = new HashSet<>();
            for (PaperCandidate paper : papers) {
                assertEquals(sample, email.messageId(), paper.sourceMessageId());
                // "Full Text"/"Read article"/title all point at the same paper: one candidate, not three.
                assertTrue(sample + " emitted twice: " + paper.title(), titles.add(paper.title()));
            }
        }
    }

    /** M2 outputs "zero or more" candidates: a forward with a personal note and no alert content is not an error. */
    @Test
    public void mailWithNoPapersInItYieldsAnEmptyListInsteadOfFailing() {
        RawEmail noteOnly = new RawEmail("<note-only@mail.gmail.com>", "Fwd: something", "aprakashn@gmail.com",
                Instant.parse("2026-09-04T07:28:00Z"),
                Optional.of("<div>Please see my comments on the draft. <a href=\"https://sites.google.com/view/prakashbadal/\">"
                        + "my page</a></div>"),
                Optional.empty());

        assertEquals(List.of(), extractor.extract(noteOnly));
    }

    // ---------------------------------------------------------------- helpers

    private List<PaperCandidate> extract(String sampleFile, String originalSubject) {
        return extractor.extract(forwardedSample(sampleFile, originalSubject));
    }

    /** The mail as M1 hands it over: the client's forward (envelope From is the client, subject is "Fwd: ..."). */
    private static RawEmail forwardedSample(String sampleFile, String originalSubject) {
        return new RawEmail(
                "<sample-" + sampleFile + "@mail.gmail.com>",
                "Fwd: " + originalSubject,
                "aprakashn@gmail.com",
                Instant.parse("2026-09-04T07:28:00Z"),
                Optional.of(readResource("/samples/" + sampleFile)),
                Optional.empty());
    }

    private static String readResource(String path) {
        try (InputStream in = PaperExtractorTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
