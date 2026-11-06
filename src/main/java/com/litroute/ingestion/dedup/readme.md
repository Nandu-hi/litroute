# `dedup` (M4): one record per paper

The same paper can arrive more than once: from two publishers, from Google Scholar repeating a publisher's alert, or from a re-run after a crash. [Deduplicator.deduplicate(list)](Deduplicator.java) merges the copies, so the professor never reviews a paper twice.

- **Input:** M7c's held papers first, then this batch. Earlier entries win.
- **Output:** one entry per paper, in first-seen order. It replaces M7c's `enriched` list.

**Same paper?** ([samePaper](Deduplicator.java))

1. **Both have a DOI:** they are the same paper exactly when the DOIs are equal (ignoring case). Different DOIs are never merged.
2. **Otherwise:** both of these must hold:
   - title similarity ≥ **0.92** ([Titles.similarity](../../util/Titles.java), which ignores accents, case and punctuation);
   - the authors agree: either list is empty, or the two lists share a surname.

**Merge:** a union, never a replacement. The first copy keeps every field it has, including `title` and `sourceMessageId`, and the duplicate only fills the gaps.

M7e identifies papers the same way (DOI, else compacted title), so a paper is never queued twice for one student.

**Tests:** [DeduplicatorTest](../../../../../../test/java/com/litroute/ingestion/dedup/DeduplicatorTest.java) uses real papers from the alerts. It checks DOI merging (case-insensitive), that different DOIs are never merged, title merging, that a shared title without a shared author is not merged, that the first copy wins its fields, chains of three copies, and that order is preserved.
