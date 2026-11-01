package com.litroute.pipeline;

import com.litroute.model.AuthorName;
import com.litroute.model.PaperCandidate;
import com.litroute.model.ScoredPaper;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.PriorityAuthorStore;
import com.litroute.store.ScoredPaperLog;
import com.litroute.store.ScoredQueueStore;
import com.litroute.store.SentPaperStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Changes the list of authors M6 favours. M6 scores a paper once, so a paper an author on the list wrote
 * may already have been scored, and turned down, before the author was added. Adding an author therefore
 * forgets those papers in the scored log, so the next run gives them one more look under the lower
 * cutoff. A paper that is queued or was sent has had its decision and stays as it is.
 *
 * Not safe to run alongside a pipeline run, which writes the same log: the UI runs it as a job.
 */
public final class PriorityAuthors {

    private final PriorityAuthorStore authors;
    private final EnrichmentCacheStore held;
    private final ScoredPaperLog scored;
    private final ScoredQueueStore queue;
    private final SentPaperStore sent;

    /**
     * Input: the authors list, M7c, the scored log, M7e and the sent papers.
     * Output: the list's editor.
     * Used by: {@link #atDefaultLocations}; PriorityAuthorsTest with a temp folder.
     * Example: {@code new PriorityAuthors(authors, m7c, log, queue, sent)}
     */
    public PriorityAuthors(PriorityAuthorStore authors, EnrichmentCacheStore held, ScoredPaperLog scored,
            ScoredQueueStore queue, SentPaperStore sent) {
        this.authors = authors;
        this.held = held;
        this.scored = scored;
        this.queue = queue;
        this.sent = sent;
    }

    /**
     * Input: none.
     * Output: the editor over the stores in ~/.litroute/data.
     * Used by: AuthorsPane (M8).
     * Example: {@code atDefaultLocations().add(new AuthorName("Alice", "Carter"))}
     */
    public static PriorityAuthors atDefaultLocations() {
        return new PriorityAuthors(PriorityAuthorStore.atDefaultLocation(), EnrichmentCacheStore.atDefaultLocation(),
                ScoredPaperLog.atDefaultLocation(), ScoredQueueStore.atDefaultLocation(), SentPaperStore.atDefaultLocation());
    }

    /**
     * Input: none.
     * Output: the names on the list, in the order they were added.
     * Used by: AuthorsPane (M8).
     * Example: -> [Alice Carter, John van de Lindt]
     */
    public List<AuthorName> list() throws IOException {
        return authors.load();
    }

    /**
     * Adds an author, unless they are listed, and forgets their held papers in the scored log.
     * Input: a first and a last name.
     * Output: how many held papers the next run will score again; the list is saved first, then the log.
     * Used by: AuthorsPane (M8) Add.
     * Example: M7c holds a paper by "Alice K. Carter", scored and not queued -> add(Alice Carter) returns 1
     */
    public int add(AuthorName name) throws IOException {
        List<AuthorName> names = authors.load();
        if (names.stream().noneMatch(name::sameAs)) {
            names.add(name);
            authors.save(names);
        }
        Set<String> decided = new HashSet<>();
        queue.load().forEach(paper -> decided.add(ScoredPaper.paperKey(paper.candidate())));
        sent.load().forEach(entry -> decided.add(ScoredPaper.paperKey(entry.paper().candidate())));
        List<String> again = new ArrayList<>();
        for (PaperCandidate paper : held.load().enriched()) {
            if (!decided.contains(ScoredPaper.paperKey(paper))
                    && paper.authors().stream().anyMatch(name::matches)) {
                again.add(ScoredPaper.paperKey(paper));
            }
        }
        return scored.removeAll(again);
    }

    /**
     * Takes an author off the list. Papers already queued for their sake stay queued.
     * Input: a name on the list.
     * Output: none; the list is saved without it.
     * Used by: AuthorsPane (M8) Remove.
     * Example: remove(Alice Carter) -> the next run's matcher no longer favours their papers
     */
    public void remove(AuthorName name) throws IOException {
        List<AuthorName> names = authors.load();
        if (names.removeIf(name::sameAs)) {
            authors.save(names);
        }
    }
}
