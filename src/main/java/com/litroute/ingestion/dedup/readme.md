# `dedup` (M4): one record per paper

A paper can arrive twice: from two publishers, from Scholar repeating a publisher, or from a re-run. [Deduplicator](Deduplicator.java) merges the copies. Input is M7c's held papers followed by the batch; the output replaces M7c's list in first-seen order.

**Same paper?**
1. Both have a DOI: same exactly when the DOIs are equal, ignoring case. Different DOIs never merge.
2. Otherwise: title similarity ≥ **0.92** ([Titles](../../util/Titles.java)), **and** the authors agree (either list empty, or a shared surname).

**Merge:** a union. The first copy keeps every field it has, and the later one only fills gaps.

**Tests:** [DeduplicatorTest](../../../../../../test/java/com/litroute/ingestion/dedup/DeduplicatorTest.java) on real alert papers: DOI and title merging, no author → no merge, first copy wins, chains, order.
