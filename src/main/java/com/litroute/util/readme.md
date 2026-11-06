# `util`: small stateless helpers

No pipeline decisions here; the thresholds belong to the callers.

| Class | Does | Used by |
|---|---|---|
| [AtomicFiles](AtomicFiles.java) | Temp file + `ATOMIC_MOVE`: readers see the old or new file, never half | Every store, PdfDownloader |
| [Dois](Dois.java) | First bare DOI in a text or URL, without query strings or `.pdf`/`/full` suffixes | M2 |
| [Titles](Titles.java) | `compact` (lowercase `a-z0-9`, no accents); `similarity` (1 − Levenshtein / length) | M3 (≥ 0.88), M4 (≥ 0.92), keys |
| [PersonNames](PersonNames.java) | Strict name match: first name … last name, only middles between | AuthorName, M6 |
| [Vectors](Vectors.java) | `cosine`, `norm`, `normalize`, `mean`, `weightedSum`; zero vector → cosine 0 | Embedding, M5, M6 |

`PersonNames` for Alice Carter: matches *Alice K. Carter*, *ALICE CARTER*, *Carter, Alice K.*; not *A. Carter*, *Alicia Carter*, *Dr. Alice Carter*.

**Tests:** [DoisTest](../../../../../test/java/com/litroute/util/DoisTest.java), [PersonNamesTest](../../../../../test/java/com/litroute/util/PersonNamesTest.java), [VectorsTest](../../../../../test/java/com/litroute/util/VectorsTest.java); the rest through the store, dedup and enrich tests.
