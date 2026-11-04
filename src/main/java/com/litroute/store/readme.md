# The `store` module (M7)

**In one sentence:** the local JSON files that keep the pipeline's progress between runs. Each store sits on the boundary between two stages, so a later run (or a crash recovery) continues from saved results instead of redoing work.

All files live in `~/.litroute/data/` by default (each class has `atDefaultLocation()`). Every write goes through [AtomicFiles.writeAtomically](../util/AtomicFiles.java), so a crash during a write leaves the previous version intact rather than a half-written, corrupt file. A missing file always means "nothing saved yet"; a corrupt one is reported as an `IOException`, never silently treated as empty.

## The stores at a glance

| Store | File | Boundary | Holds |
|---|---|---|---|
| **M7a** [EmailCacheStore](EmailCacheStore.java) | `email_cache.json` | M1 → M2 | The Message-IDs of emails already processed |
| **M7c** [EnrichmentCacheStore](EnrichmentCacheStore.java) | `enrichment_cache.json` | M3/M4 → M6 | The enriched, deduplicated candidates, plus the lookup cache |
| **M7d** [ProfileStore](ProfileStore.java) | `profiles.json` | M5 → M6 | Every student's [Profile](../model/Profile.java) (vectors, text and keywords) |
| **M7e** [ScoredQueueStore](ScoredQueueStore.java) | `scored_queue.json` + `matched/<profile>/` | M6 → M8/M9 | The professor's review queue of [ScoredPaper](../model/ScoredPaper.java)s, and each student's matched PDFs |
| [SentPaperStore](SentPaperStore.java) | `sent_papers.json` | M10 → M9 | Every paper emailed to its student, and when; such a paper is never queued for that student again |
| [PriorityAuthorStore](PriorityAuthorStore.java) | `priority_authors.json` | M8 → M6 | The authors whose papers M6 favours: a setting, not a cache |

There is no M7b: candidates are written only once they are enriched and deduplicated, straight into M7c, so no half-finished snapshot is ever stored.

## How data flows through the stores

```
                         ┌───────────────── M7a email_cache.json ◀──────────────┐ (3) save IDs LAST
                         │ already-processed IDs                                │
                         ▼                                                      │
Gmail ──▶ M1 GmailEmailSource ──RawEmail──▶ M2/M3 PaperExtractor ──▶ M4 Deduplicator
                                                 ▲        │ lookups              │ (2) save
                                                 │        ▼                      ▼
                                         M7c enrichment_cache.json  { enriched: [...], lookups: {...} }
                                                                          │ enriched
student PDFs ──▶ M5 ProfileBuilder ──▶ M7d profiles.json ──┐              │
                                                           ▼              ▼
                                         M6 PaperMatcher.match(...)  (RelevanceScorer)
                                                           │ addAll(Map<student, List<ScoredPaper>>)
                                                           ▼
                                            M7e scored_queue.json ◀── setStatus ── M8 GUI (professor)
                                                matched/<student>/*.pdf     ▲ skip papers already sent
                                                           │ APPROVED       │
                                                           ▼                │
                                                       M10 ApprovedPaperSender ──▶ Gmail SMTP ──▶ students
                                                           │ (1) record, (2) remove from the queue
                                                           ▼
                                                     sent_papers.json
```

**Write order is what makes crashes safe.** [AlertBatchProcessor](../ingestion/extract/AlertBatchProcessor.java) loads both stores first (so a corrupt store fails before any network calls are made), extracts and enriches the batch in memory, saves M7c in one write, and saves M7a **last**. If the process crashes at any point, the emails are still unmarked, so the whole batch is processed again on the next run instead of being silently skipped. Papers from the re-run replace that batch's earlier entries, and PDFs already downloaded are reused.

## Who reads and writes each store

| Store | Written by | Read by |
|---|---|---|
| **M7a** `email_cache.json` | [AlertBatchProcessor](../ingestion/extract/AlertBatchProcessor.java) (`process`: last; `forgetAll`: last) | [PipelineRunner](../pipeline/PipelineRunner.java) (IDs for M1 to skip; IDs to mark unread on reset), AlertBatchProcessor, [PipelinePane](../ui/PipelinePane.java) (count) |
| **M7c** `enrichment_cache.json` | AlertBatchProcessor (papers + lookups in one write; empty on `forgetAll`) | AlertBatchProcessor (held papers, live lookups for [CachingPaperEnricher](../ingestion/enrich/CachingPaperEnricher.java)), [RelevanceScorerCli](../scoring/RelevanceScorerCli.java) |
| **M7d** `profiles.json` | [ProfileBuilder](../profile/ProfileBuilder.java) (`save`, `remove`) | ProfileBuilder, PipelineRunner (profiles for M6), [ApprovedPaperSender](../delivery/ApprovedPaperSender.java) (addresses), [ProfilesPane](../ui/ProfilesPane.java), the CLIs |
| **M7e** `scored_queue.json` + `matched/` | PipelineRunner (`addAll`, `clear`), [ReviewPane](../ui/ReviewPane.java) (`setStatus`, `move`), ApprovedPaperSender (`remove`), PriorityAuthors (`removeUnmatched` when an author is added), RelevanceScorerCli (`addAll`) | ReviewPane, PipelineRunner (already-scored papers, counts), ApprovedPaperSender (approved papers and their PDF copies) |
| `sent_papers.json` | ApprovedPaperSender (`addAll` before removing from M7e), [DataCaches](DataCaches.java) (`clear`) | PipelineRunner (never re-queue), ApprovedPaperSender (`settle`), ReviewPane (**Show: Sent**), [PriorityAuthors](../pipeline/PriorityAuthors.java) |
| `priority_authors.json` | [PriorityAuthors](../pipeline/PriorityAuthors.java) | PriorityAuthors, PipelineRunner and RelevanceScorerCli (the list for M6) |

All stores share the same rules: the default file is under `~/.litroute/data/` (`atDefaultLocation()`), a missing file loads as empty, a corrupt one is an `IOException`, and every write is a whole-file atomic replace. M7e and the sent papers, which the UI and a pipeline run may change at the same time, also take one lock per file path.

## The classes

### [EmailCacheStore](EmailCacheStore.java): M7a

A set of Message-IDs stored as a JSON array. `load()` returns a modifiable `LinkedHashSet` (empty if there is no file), and `save(ids)` writes it back. M1 skips every message whose ID is in this set.

### [EnrichmentCacheStore](EnrichmentCacheStore.java): M7c

One JSON file in two parts, loaded as a `Contents` record:
- **`enriched`:** the enriched, deduplicated candidates that scoring reads.
- **`lookups`:** the lookup cache, enriched candidates keyed by compacted title. [CachingPaperEnricher](../ingestion/enrich/CachingPaperEnricher.java) reads and adds to this **live** map during a run, so a paper seen again is answered from disk instead of the network.

`Contents` holds **modifiable copies**, so the pipeline can add to them and save them back. Either part may be missing from a hand-edited file. The store only saves and loads; the enricher decides what goes into the lookups and the deduplicator decides the candidate list.

### [ProfileStore](ProfileStore.java): M7d

All profiles in one JSON object keyed by profile name, kept in the order they were first saved.
- `loadAll()`, `load(name)`, `save(profile)` (add or replace by name), `remove(name)`.
- Profiles are saved through private `StoredProfile` / `StoredPaper` records with plain nullable fields, so Jackson needs no `Optional` or `Instant` modules. `Instant` is stored as an ISO string, and missing optional vectors and text are left out of the JSON.
- Stores written **before text was kept** still load; those papers just have no `abstractText` or `bodyText`. Likewise stores written before keywords were kept: those profiles have no `keywords`, and their papers none either (a paper read for keywords that lists none stores `[]`).
- Stores written **while profiles were split into interest clusters** still load: the `clusters` field is ignored, and it is left out the next time the store is written.
- A missing file or name means "no profile yet", which should lead to onboarding rather than an error.

### [ScoredQueueStore](ScoredQueueStore.java): M7e

The review queue: a JSON list of scored (paper, student) pairs in the order they were first queued, plus a folder of matched PDFs per student.
- **[addAll(matches)](ScoredQueueStore.java#L170):** takes a matcher's output, `Map<student, List<ScoredPaper>>`, as it is; [addAll(scored)](ScoredQueueStore.java#L183) takes the papers in one list. A paper already queued for the same student (same [queueKey()](../model/ScoredPaper.java#L113)) takes the new score, rationale and recommendation but **keeps its place and its review status**, so re-scoring never undoes the professor's decision.
- **Matched PDFs per student:** a queued paper's PDF is copied into [pdfFolder(student)](ScoredQueueStore.java#L143) (`~/.litroute/data/matched/<student>/` by default; next to the queue file when it is constructed with only a file), keeping its file name, and the queue entry's `pdfPath` points to the copy, so M10 can attach it.
  - A paper queued for two students is copied into both folders.
  - Queuing a paper again finds its copy (same bytes) instead of copying it twice; another paper's PDF with the same file name is saved as `name-2.pdf`.
  - A paper without a PDF (paywalled), or whose download has since gone, is still queued, with no file; a gone PDF is logged.
  - A profile name that would lead outside the folder (`../x`) is refused.
- **[setStatus(paper, status)](ScoredQueueStore.java):** records the professor's decision. Returns `false` if that paper isn't queued for that student.
- **Not matched.** The queue holds every paper M6 judged, so it is also the record of what is already scored. A paper M6 matched to no one is queued as `UNMATCHED` under its closest student, and keeps pointing at its download in `pdfs/` until the professor decides on it or moves it; only then is its PDF copied into a student's folder. [removeUnmatched(keys)](ScoredQueueStore.java) takes such entries out again (and only those) when an author is added to the list, so the next run scores them once more.
- **[move(paper, student)](ScoredQueueStore.java):** gives a queued paper to another student instead ([ScoredPaper.movedTo](../model/ScoredPaper.java)). It keeps its place in the queue and takes M6's score, relevance and reasoning for that student, kept in the entry's `fits`. A dropped or not-matched paper becomes pending; a pending or approved one keeps its status. Its PDF is copied into the new student's folder, and the old copy is deleted once nothing else points to it, after the queue is written. Moving to a student who already has the paper queued is refused.
- **[clear()](ScoredQueueStore.java):** empties the queue, decisions included, and deletes the PDF copies its entries point to in the students' folders (and any folder left empty). A file outside `matched/` is never touched. Used by M9's **Clear and re-run**.
- **Concurrency:** the GUI (M8) changes statuses while the pipeline (M9) adds papers. Every change is a read-modify-write inside `synchronized` on **one lock per file path**, shared by all store objects for that file, so neither side can lose the other's update.
- Queues saved before recommendations existed load with `recommended = false`.
- Each entry stores its `relevance` (null if none was recorded). Entries queued before relevance was stored take it from their rationale, which states it ("Relevance 0.82 (…").

### [SentPaperStore](SentPaperStore.java)

The papers already emailed to their students, kept **apart from the review queue**: a sent paper leaves the queue and is recorded here with the time it went (status `SENT`, in the queue entry's own JSON shape).

- **A student never gets the same paper twice.** M9 doesn't queue a paper for a student it was sent to, not even after **Clear and re-run**, which leaves this file alone. A paper is identified as in the queue: the student plus the paper's DOI, else its compacted title.
- **[addAll(papers, sentAt)](SentPaperStore.java):** records papers as sent. A paper sent to the same student again keeps one entry, with the new time.
- **[sentKeys()](SentPaperStore.java):** the queue keys of everything sent, for M9 and M10 to check against.
- **[queue.remove(papers)](ScoredQueueStore.java)** takes sent papers out of the queue, leaving their PDF copies in the students' folders.
- One lock per file path, like the queue.


### [PriorityAuthorStore](PriorityAuthorStore.java)

The professor's authors list, as a JSON array of [AuthorName](../model/AuthorName.java)s (`firstName`, `lastName`). The store only persists; [PriorityAuthors](../pipeline/PriorityAuthors.java) changes the list.

### [DataCaches](DataCaches.java): the Caches tab's view of this folder

Measures and clears what the stores keep, one `Cache` per row of the tab. It holds no data of its own and clears each cache through the store that owns it, so that store's rules still apply.

| Cache | Measures | Clearing it |
|---|---|---|
| Review queue | `scored_queue.json` + the queue's PDF copies | `ScoredQueueStore.clear` |
| Sent papers | `sent_papers.json` + PDF copies only the sent papers point to | `SentPaperStore.clear`, then those copies |
| Extracted papers | `enrichment_cache.json` | saves empty contents |
| Processed emails | `email_cache.json` | saves an empty set, and doesn't touch Gmail |
| Downloaded PDFs | `pdfs/` | deletes what is in the folder |
| Student profiles | `profiles.json` | `ProfileStore.clear`; the students' PDF folders stay |

Caches are cleared in that order, whichever were chosen: the queue before the sent papers and M7c before M7a, as **Clear and re-run** does, so a crash repeats work rather than losing it. `report()` says how big everything else in the folder is (the authors list, backups, stray copies) and nothing clears that. A corrupt store still shows its size and says it can't be read, though clearing the queue or the sent papers then fails, since each has to read its entries to find their PDF copies.

### [StoredCandidate](StoredCandidate.java): the shared JSON shape of a `PaperCandidate`

A package-private record used by M7c and M7e. Empty `Optional`s are left out of the JSON, and the PDF path is stored as a string. `from(candidate)` and `toCandidate()` convert in each direction. A stored entry that is no longer a valid candidate (for example, a hand-edited file that lost its title) is reported as an `IOException`.

## How it's tested

The store fixtures use real papers' metadata (DOI, title, authors and venue as Crossref holds them), their PDFs as recorded from the client's alerts (`src/test/resources/http/`), and profiles built from the real reference PDFs.

| Test class | What it covers |
|---|---|
| [EmailCacheStoreTest](../../../../../test/java/com/litroute/store/EmailCacheStoreTest.java) | A missing file loads as an empty set. Saved IDs load back unchanged. Saving overwrites the previous contents atomically. |
| [EnrichmentCacheStoreTest](../../../../../test/java/com/litroute/store/EnrichmentCacheStoreTest.java) | A missing file loads as empty contents. Enriched records and the lookup cache load back equal. Loaded contents can be added to and saved again. Saving replaces the previous contents. A file missing one section still loads. An unreadable file, or an entry that lost its title, is reported as an `IOException`. |
| [ProfileStoreTest](../../../../../test/java/com/litroute/store/ProfileStoreTest.java) | A missing file means no profile yet. Built profiles load back with identical vectors. Saving replaces a profile by name, and removing deletes only that one. A store saved before text was kept still loads. A corrupt file is reported, not treated as empty. |
| [DataCachesTest](../../../../../test/java/com/litroute/store/DataCachesTest.java) | An empty folder holds nothing. Each cache reports its files and contents, and every byte in the folder is counted once. A corrupt store still shows its size. Clearing the sent papers deletes only their record and copies, keeping a copy the queue points to; clearing the queue leaves the sent papers; clearing the downloads leaves the students' copies; clearing everything leaves the authors list and other files. |
| [ScoredQueueStoreTest](../../../../../test/java/com/litroute/store/ScoredQueueStoreTest.java) | An empty queue when nothing is queued. Review statuses load back correctly. Re-scoring a queued paper keeps the professor's decision. The same paper for another student is queued separately. An unknown paper can't change status. Concurrent status changes and additions are all kept. The recommendation survives reloading, and older queues load as not recommended. Relevance survives reloading, and older entries read it from their rationale. The listed author survives reloading, and older entries have none. Clearing empties the queue and deletes only its own PDF copies. A matched PDF is copied into the student's folder and the entry points to it; a paper matched to two students is in both folders; queuing it again doesn't copy it twice; another paper's PDF with the same name gets a numbered name; a paper without a PDF is queued without a file; a profile name can't lead outside the folder. A corrupt file is reported, not treated as empty. |
