# The `store` module (M7)

**In one sentence:** the local JSON files that keep the pipeline's progress between runs. Each store sits on the boundary between two stages, so a later run (or a crash recovery) continues from saved results instead of redoing work.

All files live in `~/.litroute/data/` by default (each class has `atDefaultLocation()`). Every write goes through [AtomicFiles.writeAtomically](../util/AtomicFiles.java), so a crash during a write leaves the previous version intact rather than a half-written, corrupt file. A missing file always means "nothing saved yet"; a corrupt one is reported as an `IOException`, never silently treated as empty.

## The stores at a glance

| Store | File | Boundary | Holds |
|---|---|---|---|
| **M7a** [EmailCacheStore](EmailCacheStore.java) | `email_cache.json` | M1 → M2 | The Message-IDs of emails already processed |
| **M7c** [EnrichmentCacheStore](EnrichmentCacheStore.java) | `enrichment_cache.json` | M3/M4 → M6 | The enriched, deduplicated candidates, plus the lookup cache |
| **M7d** [ProfileStore](ProfileStore.java) | `profiles.json` | M5 → M6 | Every student's [Profile](../model/Profile.java) (vectors and text) |
| **M7e** [ScoredQueueStore](ScoredQueueStore.java) | `scored_queue.json` | M6 → M8/M9 | The professor's review queue of [ScoredPaper](../model/ScoredPaper.java)s |

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
                                                    M6 RelevanceScorer.match(...)
                                                           │ addAll
                                                           ▼
                                            M7e scored_queue.json ◀── setStatus ── M8 GUI (professor)
                                                           │ APPROVED
                                                           ▼
                                                       M10 send
```

**Write order is what makes crashes safe.** [AlertBatchProcessor](../ingestion/extract/AlertBatchProcessor.java) loads both stores first (so a corrupt store fails before any network calls are made), extracts and enriches the batch in memory, saves M7c in one write, and saves M7a **last**. If the process crashes at any point, the emails are still unmarked, so the whole batch is processed again on the next run instead of being silently skipped. Papers from the re-run replace that batch's earlier entries, and PDFs already downloaded are reused.

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
- Stores written **before text was kept** still load; those papers just have no `abstractText` or `bodyText`.
- Stores written **while profiles were split into interest clusters** still load: the `clusters` field is ignored, and it is left out the next time the store is written.
- A missing file or name means "no profile yet", which should lead to onboarding rather than an error.

### [ScoredQueueStore](ScoredQueueStore.java): M7e

The review queue: a JSON list of scored (paper, student) pairs in the order they were first queued.
- **[addAll(scored)](ScoredQueueStore.java#L85):** adds newly scored papers. A paper already queued for the same student (same [queueKey()](../model/ScoredPaper.java#L57)) takes the new score, rationale and recommendation but **keeps its place and its review status**, so re-scoring never undoes the professor's decision.
- **[setStatus(paper, status)](ScoredQueueStore.java#L103):** records the professor's decision. Returns `false` if that paper isn't queued for that student.
- **Concurrency:** the GUI (M8) changes statuses while the pipeline (M9) adds papers. Every change is a read-modify-write inside `synchronized` on **one lock per file path**, shared by all store objects for that file, so neither side can lose the other's update.
- Queues saved before recommendations existed load with `recommended = false`.

### [StoredCandidate](StoredCandidate.java): the shared JSON shape of a `PaperCandidate`

A package-private record used by M7c and M7e. Empty `Optional`s are left out of the JSON, and the PDF path is stored as a string. `from(candidate)` and `toCandidate()` convert in each direction. A stored entry that is no longer a valid candidate (for example, a hand-edited file that lost its title) is reported as an `IOException`.

## How it's tested

The store fixtures use a real paper's metadata (DOI, title, authors and venue as Crossref holds them) and profiles built from the real reference PDFs.

| Test class | What it covers |
|---|---|
| [EmailCacheStoreTest](../../../../../test/java/com/litroute/store/EmailCacheStoreTest.java) | A missing file loads as an empty set. Saved IDs load back unchanged. Saving overwrites the previous contents atomically. |
| [EnrichmentCacheStoreTest](../../../../../test/java/com/litroute/store/EnrichmentCacheStoreTest.java) | A missing file loads as empty contents. Enriched records and the lookup cache load back equal. Loaded contents can be added to and saved again. Saving replaces the previous contents. A file missing one section still loads. An unreadable file, or an entry that lost its title, is reported as an `IOException`. |
| [ProfileStoreTest](../../../../../test/java/com/litroute/store/ProfileStoreTest.java) | A missing file means no profile yet. Built profiles load back with identical vectors. Saving replaces a profile by name, and removing deletes only that one. A store saved before text was kept still loads. A corrupt file is reported, not treated as empty. |
| [ScoredQueueStoreTest](../../../../../test/java/com/litroute/store/ScoredQueueStoreTest.java) | An empty queue when nothing is queued. Review statuses load back correctly. Re-scoring a queued paper keeps the professor's decision. The same paper for another student is queued separately. An unknown paper can't change status. Concurrent status changes and additions are all kept. The recommendation survives reloading, and older queues load as not recommended. A corrupt file is reported, not treated as empty. |
