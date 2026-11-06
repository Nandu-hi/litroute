# `store` (M7): the JSON files under `~/.litroute/data/`

These stores keep the pipeline's progress between runs, so a later run (or a crash recovery) continues from saved results instead of redoing work.

**Shared rules:** every store has `atDefaultLocation()`. Every write is a whole-file atomic replace ([AtomicFiles](../util/AtomicFiles.java)). A missing file loads as empty; a corrupt one is an `IOException`, never treated as empty. Records are converted through each store's own plain JSON shape, so loading runs the record's validation.

| Store | File | Holds | Written by |
|---|---|---|---|
| **M7a** [EmailCacheStore](EmailCacheStore.java) | `email_cache.json` | Message-IDs already processed | AlertBatchProcessor, **last** |
| **M7c** [EnrichmentCacheStore](EnrichmentCacheStore.java) | `enrichment_cache.json` | `enriched`: the deduplicated papers; `lookups`: the M3 cache, keyed by compacted title | AlertBatchProcessor, in one write |
| **M7d** [ProfileStore](ProfileStore.java) | `profiles.json` | every [Profile](../model/Profile.java), by name | ProfileBuilder |
| **M7e** [ScoredQueueStore](ScoredQueueStore.java) | `scored_queue.json` + `matched/<student>/` | the review queue and each student's PDF copies | PipelineRunner, ReviewPane, ApprovedPaperSender, PriorityAuthors |
| [SentPaperStore](SentPaperStore.java) | `sent_papers.json` | every paper sent, and when | ApprovedPaperSender |
| [PriorityAuthorStore](PriorityAuthorStore.java) | `priority_authors.json` | the professor's authors list (a setting, not a cache) | PriorityAuthors |

There is no M7b: candidates are written only after enrichment and deduplication, straight into M7c.

## Crash safety comes from write order

- **Ingestion:** load M7c and M7a → work in memory → save M7c → save M7a → mark the emails read. A crash anywhere repeats the batch, and the re-run's papers replace the earlier copies.
- **Delivery:** record in `sent_papers.json` **before** removing the paper from M7e. A crash in between leaves the paper in both, and the next send's `settle()` removes it from the queue.
- **Clearing:** queue before sent papers, M7c before M7a.

## M7e, the review queue

- **`addAll`:** a paper already queued for that student (same [queueKey](../model/ScoredPaper.java): DOI, else compacted title) takes the new score but **keeps its place and the professor's decision**.
- **PDF copies:** a queued paper's PDF is copied into `matched/<student>/`, and the entry points to the copy. Identical bytes are reused; a different file with the same name becomes `name-2.pdf`. A profile name that would escape the folder (`../x`) is refused.
- **Not matched:** papers M6 matched to no one are queued as `UNMATCHED` under their closest student, so the queue is also the record of what has been scored. Their PDF is copied only once the professor acts. `removeUnmatched(keys)` takes them back out when an author is added to the list.
- **`move(paper, student)`:** the paper takes that student's stored fit. A dropped or unmatched paper becomes pending. The PDF copy follows the paper, and the old copy is deleted once nothing else points to it.
- **`clear()`:** empties the queue and deletes only its own copies in `matched/`.
- **Concurrency:** the UI and a pipeline run can change the queue at the same time. Each change is a read-modify-write under **one lock per file path**. `SentPaperStore` works the same way.
- **Older files** still load: missing `recommended` → false, missing `relevance` → read from the rationale, a `clusters` field in old profiles → ignored.

## [DataCaches](DataCaches.java): the Caches tab

DataCaches measures and clears each cache through the store that owns it: the review queue, sent papers (plus PDF copies only they point to), extracted papers, processed emails (Gmail isn't touched), downloaded `pdfs/`, and student profiles (the folders stay). The authors list and stray files are measured but never cleared.

## Tests

Fixtures use real paper metadata and recorded PDFs. One test class per store ([test/store](../../../../../test/java/com/litroute/store/)) covers:

- round-trips;
- corrupt files reported, not emptied;
- re-scoring keeps decisions;
- concurrent changes all kept;
- PDF copying and naming;
- older file formats;
- what each cache clear leaves behind.
