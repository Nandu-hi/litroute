# `store` (M7): JSON files under `~/.litroute/data/`

They keep progress between runs, so a later run or crash recovery continues instead of redoing work. Every write is an atomic whole-file replace ([AtomicFiles](../util/AtomicFiles.java)). A missing file loads as empty; a corrupt one is an `IOException`, never treated as empty.

| Store | File | Holds |
|---|---|---|
| M7a [EmailCacheStore](EmailCacheStore.java) | `email_cache.json` | Processed Message-IDs |
| M7c [EnrichmentCacheStore](EnrichmentCacheStore.java) | `enrichment_cache.json` | Deduplicated papers + the M3 lookup cache |
| M7d [ProfileStore](ProfileStore.java) | `profiles.json` | Every profile |
| M7e [ScoredQueueStore](ScoredQueueStore.java) | `scored_queue.json`, `matched/<student>/` | Review queue + students' PDF copies |
| [SentPaperStore](SentPaperStore.java) | `sent_papers.json` | Every paper sent, and when |
| [PriorityAuthorStore](PriorityAuthorStore.java) | `priority_authors.json` | The authors list |

There is no M7b: candidates go straight into M7c after dedup.

## Crash safety is write order

- **Ingestion:** save M7c → save M7a → mark emails read.
- **Delivery:** record as sent → remove from M7e (`settle()` cleans up between).
- **Clearing:** queue before sent papers; M7c before M7a.

## M7e, the review queue

- **Re-scoring** a queued paper updates its score but keeps its place and the professor's decision.
- **PDF copies** go to `matched/<student>/` (identical bytes reused, name clashes → `name-2.pdf`, `../x` refused). `UNMATCHED` papers get a copy only once the professor acts.
- **`move`** takes the new student's stored fit; the PDF copy follows.
- **Concurrency:** UI and pipeline share one lock per file path (also for `SentPaperStore`).

[DataCaches](DataCaches.java) measures and clears each cache for the Caches tab, through its owning store. The authors list is never cleared.

**Tests:** one class per store in [test/store](../../../../../test/java/com/litroute/store/): round-trips, corrupt files, decisions kept, concurrent writes, PDF naming, old formats.
