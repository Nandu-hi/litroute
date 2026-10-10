package com.litroute.scoring;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.profile.PaperText;
import com.litroute.profile.PdfTextExtractor;
import com.litroute.profile.UnreadablePdfException;
import com.litroute.testing.MatchTestSet;
import com.litroute.testing.MatchTestSet.Filed;
import com.litroute.testing.TestProfiles;
import com.litroute.util.Vectors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

/** The wording half of M6 on the students' real reference papers and the papers the professor filed. */
public class LexicalIndexTest {

    private static LexicalIndex index() {
        Map<String, List<ProfilePaper>> papers = new LinkedHashMap<>();
        TestProfiles.profiles().forEach((name, profile) -> papers.put(name, profile.papers()));
        return new LexicalIndex(papers);
    }

    /**
     * Leave-one-out on words alone: each reference paper, held out with any copy of it, is closest to
     * its own student for most papers. Students' fields overlap (two work on multi-hazard problems,
     * two on wildfire), and the folders hold general background documents, so the bar is a clear majority.
     */
    @Test
    public void aHeldOutReferencePaperIsClosestToItsOwnStudent() {
        Map<String, Profile> profiles = TestProfiles.profiles();
        assumeTrue("needs at least two test profiles", profiles.size() >= 2);
        Map<String, Integer> foldersPerPaper = new HashMap<>();
        profiles.values().forEach(p -> p.papers().forEach(paper -> foldersPerPaper.merge(paper.sha256(), 1, Integer::sum)));
        LexicalIndex index = index();

        int checked = 0;
        List<String> misses = new ArrayList<>();
        for (Profile own : profiles.values()) {
            for (ProfilePaper paper : own.papers()) {
                Map<String, LexicalIndex.Closeness> closeness = index.closeness(index.vector(LexicalIndex.documentText(paper)),
                        other -> other.sha256().equals(paper.sha256())
                                || Vectors.cosine(other.embedding(), paper.embedding()) >= RelevanceScorer.SAME_PAPER);
                if (foldersPerPaper.get(paper.sha256()) > 1 || !closeness.containsKey(own.name())) {
                    continue; // a PDF in two folders belongs to both; a lone paper has nothing left to match
                }
                checked++;
                String best = closeness.entrySet().stream()
                        .max(Map.Entry.comparingByValue((a, b) -> Double.compare(a.similarity(), b.similarity())))
                        .orElseThrow().getKey();
                if (!best.equals(own.name())) {
                    misses.add(own.name() + "/" + paper.relativePath() + " -> " + best);
                }
            }
        }
        String detail = (checked - misses.size()) + " of " + checked + " closest to their own student; misses:\n"
                + String.join("\n", misses);
        assertTrue(detail, checked - misses.size() >= 0.75 * checked);
    }

    /** A new paper on the Kemeny problem, filed for the student who works on it, shares that word with the student's papers. */
    @Test
    public void sharedTermsNameWhatAPaperAndAStudentHaveInCommon() {
        PdfTextExtractor extractor = new PdfTextExtractor();
        LexicalIndex index = index();
        int checked = 0;
        for (Filed filed : MatchTestSet.filed()) {
            PaperText text;
            try {
                text = extractor.extract(filed.pdf());
            } catch (UnreadablePdfException e) {
                continue;
            }
            if (!text.title().toLowerCase(Locale.ROOT).contains("kemeny")) {
                continue;
            }
            checked++;
            List<String> shared = index.sharedTerms(
                    index.vector(LexicalIndex.text(text.title(), text.abstractText(), text.bodyText())), filed.profileName(), 5);
            assertTrue(filed.pdf().getFileName() + " shares " + shared, shared.contains("kemeny"));
        }
        assumeTrue("no filed paper on the Kemeny problem", checked > 0);
    }
}
