package com.litroute.pipeline;

import com.litroute.model.AuthorName;
import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.store.EnrichmentCacheStore;
import com.litroute.store.PriorityAuthorStore;
import com.litroute.store.ScoredQueueStore;
import com.litroute.store.SentPaperStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Changes the list of authors M6 favours. M6 scores a paper once, so a paper an author on the list wrote
 * may already have been scored, and turned down, before the author was added: it waits in the review
 * queue as not matched ({@link ReviewStatus#UNMATCHED}). Adding an author therefore takes those entries
 * out of the queue, so the next run gives them one more look under the lower cutoff. A paper the
 * professor has acted on, or that was sent, has had its decision and stays as it is.
 *
 * Not safe to run alongside a pipeline run, which writes the same queue: the UI runs it as a job.
 */
public final class PriorityAuthors {

    private final PriorityAuthorStore authors;
    private final EnrichmentCacheStore held;
    private final ScoredQueueStore queue;
    private final SentPaperStore sent;

    /**
     * Edits the authors list over these stores.
     *
     * @param authors the authors list
     * @param held    M7c, searched for the added author's papers
     * @param queue   M7e, whose unmatched entries for them are released
     * @param sent    the sent-paper record
     */
    public PriorityAuthors(PriorityAuthorStore authors, EnrichmentCacheStore held, ScoredQueueStore queue,
            SentPaperStore sent) {
        this.authors = authors;
        this.held = held;
        this.queue = queue;
        this.sent = sent;
    }

    /** {@return the editor over the stores in {@code ~/.litroute/data}} */
    public static PriorityAuthors atDefaultLocations() {
        return new PriorityAuthors(PriorityAuthorStore.atDefaultLocation(), EnrichmentCacheStore.atDefaultLocation(),
                ScoredQueueStore.atDefaultLocation(), SentPaperStore.atDefaultLocation());
    }

    /**
     * {@return the names on the list, in the order they were added}
     *
     * @throws IOException if the list can't be read
     */
    public List<AuthorName> list() throws IOException {
        return authors.load();
    }

    /**
     * Adds an author, unless listed, and takes their unmatched papers out of the queue so the next run scores
     * them again. The list is saved first, then the queue.
     *
     * @param name a first and a last name
     * @return how many held papers the next run will score again
     * @throws IOException if a store can't be read or written
     */
    public int add(AuthorName name) throws IOException {
        List<AuthorName> names = authors.load();
        if (names.stream().noneMatch(name::sameAs)) {
            names.add(name);
            authors.save(names);
        }
        Set<String> decided = new HashSet<>();
        queue.load().stream().filter(paper -> paper.status() != ReviewStatus.UNMATCHED)
                .forEach(paper -> decided.add(ScoredPaper.paperKey(paper.candidate())));
        sent.load().forEach(entry -> decided.add(ScoredPaper.paperKey(entry.paper().candidate())));
        List<String> again = new ArrayList<>();
        for (PaperCandidate paper : held.load().enriched()) {
            if (!decided.contains(ScoredPaper.paperKey(paper))
                    && paper.authors().stream().anyMatch(name::matches)) {
                again.add(ScoredPaper.paperKey(paper));
            }
        }
        return queue.removeUnmatched(again);
    }

    /**
     * Takes an author off the list. Papers already queued for their sake stay queued.
     *
     * @param name a name on the list
     * @throws IOException if the list can't be read or written
     */
    public void remove(AuthorName name) throws IOException {
        List<AuthorName> names = authors.load();
        if (names.removeIf(name::sameAs)) {
            authors.save(names);
        }
    }
}
