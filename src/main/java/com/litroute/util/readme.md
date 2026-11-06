# `util`: small stateless helpers

These helpers make no pipeline decisions; the thresholds belong to their callers.

| Class | What it does | Used by |
|---|---|---|
| [AtomicFiles](AtomicFiles.java) | `writeAtomically(target, bytes)`: writes a temp file in the same folder, then moves it over the target (`ATOMIC_MOVE`). A reader sees either the old file or the new one, never half of one. | every M7 store, CredentialStore, PdfDownloader |
| [Dois](Dois.java) | `find(text)`: the first bare DOI in a text or URL. Percent-decodes, stops at query strings, and strips trailing punctuation, `.pdf` / `/full` / `/abstract` suffixes and an unmatched `)`. | M2 |
| [Titles](Titles.java) | `compact(title)`: strips accents, lowercases, keeps only `a-z0-9`. `similarity(a, b)`: 1 − Levenshtein / longer length on the compacted titles (two empty titles → 0). | M3 hit matching (≥ 0.88), M4 (≥ 0.92), cache and queue keys |
| [PersonNames](PersonNames.java) | `matches(first, last, printed)`, strict: the printed name starts with the first name and ends with the last name, with only middle names or initials between. Case, accents and in-word punctuation don't matter, and a hyphenated name stays one name. | AuthorName, PriorityAuthors, M6 |
| [Vectors](Vectors.java) | `cosine`, `norm`, `normalize` (returns a new copy), `mean`, `weightedSum`. A zero vector has cosine 0 with everything (never NaN); vectors of different lengths are rejected. | embedding, M5, M6 |

How strict `PersonNames` is, for Alice Carter:

| Matches | Doesn't match |
|---|---|
| Alice Carter, Alice K. Carter, Alice Kate Carter, ALICE CARTER, "Carter, Alice K." | Alice, Carter, A. Carter, AK Carter, Alicia Carter, Dr. Alice Carter |

**Tests:** [DoisTest](../../../../../test/java/com/litroute/util/DoisTest.java), [PersonNamesTest](../../../../../test/java/com/litroute/util/PersonNamesTest.java) and [VectorsTest](../../../../../test/java/com/litroute/util/VectorsTest.java). `AtomicFiles` and `Titles` are tested through the store, dedup and enrichment tests.
