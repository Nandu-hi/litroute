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
    private ScoredQueueStore queue;
    private SentPaperStore sent;
    private PriorityAuthors authors;

    /** Both real papers are held and were scored, and neither was matched: both wait in the queue as not matched. */
    @Before
    public void setUp() throws IOException {
        Path data = Files.createTempDirectory("litroute-authors");
        list = new PriorityAuthorStore(data.resolve("priority_authors.json"));
        EnrichmentCacheStore held = new EnrichmentCacheStore(data.resolve("enrichment_cache.json"));
        held.save(new EnrichmentCacheStore.Contents(List.of(facades, ssi), Map.of()));
        queue = new ScoredQueueStore(data.resolve("scored_queue.json"));
        queue.addAll(List.of(scored(facades, ReviewStatus.UNMATCHED), scored(ssi, ReviewStatus.UNMATCHED)));
        sent = new SentPaperStore(data.resolve("sent_papers.json"));
        authors = new PriorityAuthors(list, held, queue, sent);
    }

    private static ScoredPaper scored(PaperCandidate paper, ReviewStatus status) {
        return new ScoredPaper(paper, "RP1-mh-risk", 0.5, "...", status);
    }

    private Set<String> unmatched() throws IOException {
        return queue.load().stream().filter(paper -> paper.status() == ReviewStatus.UNMATCHED)
                .map(paper -> ScoredPaper.paperKey(paper.candidate())).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    public void addingAnAuthorTakesOnlyTheirUnmatchedPapersOutOfTheQueue() throws IOException {
        assertEquals(1, authors.add(new AuthorName("  Elton ", " Bauer ")));

        assertEquals(List.of(BAUER), authors.list());
        assertEquals("only their paper is looked at again", Set.of(ScoredPaper.paperKey(ssi)), unmatched());
    }

    /** The paper prints "Imtiyaz Akbar Najar". */
    @Test
    public void aMiddleNameOnThePaperDoesNotHideAnAuthor() throws IOException {
        assertEquals(1, authors.add(NAJAR));

        assertEquals(Set.of(ScoredPaper.paperKey(facades)), unmatched());
    }

    @Test
    public void aDifferentFirstNameIsSomeoneElse() throws IOException {
        assertEquals(0, authors.add(new AuthorName("Edward", "Bauer")));

        assertEquals(2, unmatched().size());
    }

    @Test
    public void addingAnAuthorWithNoHeldPapersForgetsNothing() throws IOException {
        assertEquals(0, authors.add(ALICE));

        assertEquals(2, unmatched().size());
        assertEquals(List.of(ALICE), list.load());
    }

    /** A paper the professor has acted on keeps its place, and one sent is never looked at again. */
    @Test
    public void aDecidedOrSentPaperKeepsItsPlace() throws IOException {
        queue.setStatus(scored(facades, ReviewStatus.UNMATCHED), ReviewStatus.DROPPED);
        queue.remove(List.of(scored(ssi, ReviewStatus.UNMATCHED)));
        sent.addAll(List.of(scored(ssi, ReviewStatus.APPROVED)), Instant.parse("2026-10-25T10:00:00Z"));

        assertEquals(0, authors.add(BAUER));
        assertEquals(0, authors.add(NAJAR));

        assertEquals(List.of(ReviewStatus.DROPPED), queue.load().stream().map(ScoredPaper::status).toList());
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
