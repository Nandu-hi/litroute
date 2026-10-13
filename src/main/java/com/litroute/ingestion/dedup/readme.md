# The `dedup` module (M4)

**In one sentence:** it merges candidates that are the same paper into one record, so the professor never reviews a paper twice.

The same paper often reaches the pipeline more than once: two publishers send alerts for it, a Google Scholar recommendation repeats a publisher's alert, a digest repeats last week's entry, or a batch is processed again after a crash.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | `List<`[PaperCandidate](../../model/PaperCandidate.java)`>` | The enriched candidates (M2 + M3) **in priority order**: the papers already held in M7c first, then this batch's new ones. Earlier entries win conflicting fields. |
| **Output** | `List<PaperCandidate>` | One entry per distinct paper, in first-seen order, unmodifiable. |

## How it works

[AlertBatchProcessor](../extract/AlertBatchProcessor.java) calls it once per batch, after every email has been extracted and enriched:

```
M7c enriched papers (already held)  +  this batch's extracted papers
   │      (papers from re-run emails are dropped first, so the fresh copy replaces them)
   ▼
Deduplicator.deduplicate(held)
   │   for each candidate, in order:
   │      same paper as one already kept? ──yes──▶ merge into it (kept one wins)
   │                                       └─no──▶ keep as a new paper
   ▼
deduplicated list ──▶ EnrichmentCacheStore.save(...)   (M7c, the input to M6 scoring)
```

### [Deduplicator](Deduplicator.java)

**[deduplicate(candidates)](Deduplicator.java#L37)** goes through the list once. Each candidate is compared with the papers kept so far. If it matches one, the two are merged; otherwise it is added as a new paper.

**[samePaper(a, b)](Deduplicator.java#L59)** decides whether two candidates are the same paper:

1. **Both have a DOI:** they are the same paper exactly when the DOIs are equal, ignoring case. DOIs are the authoritative identity, so two papers with different DOIs are **never** merged, even if their titles match.
2. **At most one has a DOI** (so the DOIs can't disagree): fall back to fuzzy matching. Both conditions must hold:
   - **The titles are similar enough.** [Titles.similarity](../../util/Titles.java#L21) must be at least **0.92** ([SAME_TITLE_THRESHOLD](Deduplicator.java#L31)). Titles are compared with accents, case, spaces and punctuation removed, so "Wave Loads: A Review" and "wave loads - a review" match.
   - **The authors agree** ([authorsAgree](Deduplicator.java#L74)). If either author list is empty, this check passes, because most alerts list no authors and requiring a match would stop fuzzy merging altogether. If both lists are non-empty, they must share at least one surname (the last word of a name, compacted). This stops two different groups' papers with nearly identical titles from being merged.

**[merge(winner, duplicate)](Deduplicator.java#L88)** combines two records as a **union, never a replacement**. The paper seen first keeps every field it has, and the duplicate only fills fields the first one left empty (`winner.venue().or(duplicate::venue)` and so on). Enrichment data is therefore never lost when a sparser copy of the same paper arrives later. `sourceMessageId` and `title` always come from the first copy.

## How it's tested

The test papers are real ones from the client's sample alert emails, since the problem this module solves (the same paper arriving from a publisher alert and a Google Scholar recommendation, written differently) is a real one.

| Test class | What it covers |
|---|---|
| [DeduplicatorTest](../../../../../../test/java/com/litroute/ingestion/dedup/DeduplicatorTest.java) | Empty input gives empty output. The same DOI from two alerts merges into one, and DOIs are compared ignoring case. Different DOIs are never merged, even with matching titles. Candidates without a DOI merge on title. A DOI-less candidate merges into the enriched copy that has a DOI. Different papers are both kept. Two papers sharing only a title, with no common author, are not merged. One shared surname is enough. The first candidate wins every field it has and the duplicate fills the rest. A third copy merges into the already merged record. First-seen order is preserved. |
