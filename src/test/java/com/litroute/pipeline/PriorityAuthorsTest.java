package com.litroute.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.litroute.model.AuthorName;
import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.store.Candidates;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.PriorityAuthorStore;
import com.litroute.store.ScoredPaperLog;
import com.litroute.store.ScoredQueueStore;
import com.litroute.store.SentPaperStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class PriorityAuthorsTest {

    private static final AuthorName ALICE = new AuthorName("Alice", "Carter");
    private static final AuthorName BAUER = new AuthorName("Elton", "Bauer");
    private static final AuthorName NAJAR = new AuthorName("Imtiyaz", "Najar");

    private final PaperCandidate facades = Candidates.full().withPdfPath(Optional.empty());
    private final PaperCandidate ssi = Candidates.ssiReview(Path.of("/nowhere.pdf")).withPdfPath(Optional.empty());

    private PriorityAuthorStore list;
    private ScoredPaperLog scored;
    private ScoredQueueStore queue;
    private SentPaperStore sent;
    private PriorityAuthors authors;

    /** Both real papers are held and were scored, and neither was matched. */
    @Before
    public void setUp() throws IOException {
        Path data = Files.createTempDirectory("litroute-authors");
        list = new PriorityAuthorStore(data.resolve("priority_authors.json"));
        EnrichmentCacheStore held = new EnrichmentCacheStore(data.resolve("enrichment_cache.json"));
        held.save(new EnrichmentCacheStore.Contents(List.of(facades, ssi), Map.of()));
        scored = new ScoredPaperLog(data.resolve("scored_papers.json"));
        scored.addAll(List.of(ScoredPaper.paperKey(facades), ScoredPaper.paperKey(ssi)));
        queue = new ScoredQueueStore(data.resolve("scored_queue.json"));
        sent = new SentPaperStore(data.resolve("sent_papers.json"));
        authors = new PriorityAuthors(list, held, scored, queue, sent);
    }

    private ScoredPaper match(PaperCandidate paper) {
        return new ScoredPaper(paper, "RP1-mh-risk", 0.5, "...", ReviewStatus.PENDING);
    }

    @Test
    public void addingAnAuthorForgetsOnlyTheirHeldPapers() throws IOException {
        assertEquals(1, authors.add(new AuthorName("  Elton ", " Bauer ")));

        assertEquals(List.of(BAUER), authors.list());
        assertEquals("only their paper is looked at again", Set.of(ScoredPaper.paperKey(ssi)), scored.load());
    }

    /** The paper prints "Imtiyaz Akbar Najar". */
    @Test
    public void aMiddleNameOnThePaperDoesNotHideAnAuthor() throws IOException {
        assertEquals(1, authors.add(NAJAR));

        assertEquals(Set.of(ScoredPaper.paperKey(facades)), scored.load());
    }

    @Test
    public void aDifferentFirstNameIsSomeoneElse() throws IOException {
        assertEquals(0, authors.add(new AuthorName("Edward", "Bauer")));

        assertEquals(2, scored.load().size());
    }

    @Test
    public void addingAnAuthorWithNoHeldPapersForgetsNothing() throws IOException {
        assertEquals(0, authors.add(ALICE));

        assertEquals(2, scored.load().size());
        assertEquals(List.of(ALICE), list.load());
    }

    /** A queued paper has a decision pending, and once sent would be looked at again if the log forgot it. */
    @Test
    public void aQueuedOrSentPaperKeepsItsPlaceInTheLog() throws IOException {
        queue.addAll(List.of(match(facades)));
        sent.addAll(List.of(match(ssi)), Instant.parse("2026-10-25T10:00:00Z"));

        assertEquals(0, authors.add(BAUER));
        assertEquals(0, authors.add(NAJAR));

        assertEquals(2, scored.load().size());
    }

    @Test
    public void anAuthorIsListedOnce() throws IOException {
        authors.add(ALICE);
        authors.add(new AuthorName("alice", "CARTER"));

        assertEquals(List.of(ALICE), authors.list());
    }

    @Test
    public void removingAnAuthorLeavesTheRest() throws IOException {
        authors.add(ALICE);
        authors.add(BAUER);

        authors.remove(ALICE);

        assertEquals(List.of(BAUER), authors.list());
        authors.remove(new AuthorName("Not", "Listed"));
        assertTrue(authors.list().contains(BAUER));
    }

    @Test
    public void bothNamesMustBeSpelledOut() {
        for (String[] name : new String[][] {{"", "Carter"}, {"Alice", " "}, {"A.", "Carter"}, {"A", "Carter"}, {"Alice", "C."}}) {
            try {
                new AuthorName(name[0], name[1]);
                fail(name[0] + " " + name[1] + " was accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("name"));
            }
        }
    }
}
