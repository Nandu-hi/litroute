# The `util` module

**In one sentence:** small, stateless helpers that several stages share: safe file writes, finding DOIs, comparing titles, and vector arithmetic. None of them makes a decision for the pipeline; thresholds belong to the callers.

## Who uses what

```
            ┌──────────────────────── AtomicFiles.writeAtomically ─────────────────────────┐
            │  M7a EmailCacheStore · M7c EnrichmentCacheStore · M7d ProfileStore ·          │
            │  M7e ScoredQueueStore · CredentialStore · PdfDownloader (PDF files)           │
            └───────────────────────────────────────────────────────────────────────────────┘

alert HTML link / text ──▶ Dois.find ──▶ PaperCandidate.doi                  (M2 AlertHtmlParser)

two titles ──▶ Titles.similarity ──▶ 0..1 ──▶ "same paper?"   M3 Crossref/OpenAlex hit matching,
                                                             M4 Deduplicator (≥ 0.92)
one title  ──▶ Titles.compact    ──▶ key   ──▶ M3 lookup cache key, M7e queueKey,
                                              M5 PdfTextExtractor title checks

SPECTER2 output ──▶ Vectors.normalize ──▶ unit vector                (embedding)
view vectors    ──▶ Vectors.weightedSum / mean ──▶ combined vector   (M5 PaperEmbedder; M6 profile mean)
two vectors     ──▶ Vectors.cosine ──▶ similarity                    (M6 scoring)
```

## The classes

### [AtomicFiles](AtomicFiles.java): crash-safe file writes

`writeAtomically(target, bytes)` creates the parent folder if needed, writes the bytes to a temporary file **in the same folder**, then moves it over the target with `ATOMIC_MOVE`. A `finally` block deletes the temporary file if anything failed.

Anyone reading the target sees either the complete old version or the complete new version, never a half-written file. The temporary file is in the same folder because an atomic rename only works within one file system.

It is used by every JSON store (M7a, c, d, e), the credential file, and [PdfDownloader](../ingestion/enrich/PdfDownloader.java).

### [Dois](Dois.java): find a bare DOI anywhere

`find(text)` returns the first DOI (`10.<4–9 digits>/…`) in any text or URL, as a bare DOI:
- if the text contains `%`, it is percent-decoded first, keeping a literal `+`;
- the match stops at whitespace, quotes, `< > ? # & \`, so query strings are excluded;
- [clean](Dois.java#L38) repeatedly removes trailing punctuation (`.,;:`), publisher suffixes (`.pdf`, `/pdf`, `/epdf`, `/full`, `/fulltext`, `/abstract`, `/abs`, `/meta`, `/references`), and an unmatched closing `)` or `]` that belongs to the surrounding sentence, as in `(doi:10.1/x)`;
- a match with nothing after the `/` is ignored.

Example: `https://link.springer.com/content/pdf/10.1186/s43065-024-00115-x.pdf?utm_source=alert` → `10.1186/s43065-024-00115-x`.

### [Titles](Titles.java): compare titles written differently

- **[compact(title)](Titles.java#L32):** removes accents (NFD + delete combining marks), lowercases with `Locale.ROOT`, and keeps only `a-z0-9`. "Wave Loads: A Review" → `waveloadsareview`. Publisher quirks such as missing spaces, en-dash vs hyphen or curly quotes therefore don't matter. This is the form titles are compared and cached in.
- **[similarity(a, b)](Titles.java#L21):** `1 − editDistance / longerLength` on the compacted titles, from 0 to 1 (1 means identical after compacting). The Levenshtein distance uses two rolling rows. Two empty titles score 0, not 1.
- What counts as "similar enough" is decided by the caller, because M3 matching an API result and M4 merging two alerts need different thresholds.

### [Vectors](Vectors.java): embedding arithmetic

- `cosine(a, b)`: −1 to 1, accumulated in `double` for precision.
- `norm(v)`, `normalize(v)` (returns a **new** unit-length copy).
- `mean(vectors)` and `weightedSum(vectors, weights)` (Σ wᵢ·vᵢ).
- **A zero vector is treated as similar to nothing.** Its cosine with anything is 0, and normalizing it returns it unchanged. This avoids producing NaN, which would otherwise spread silently into every score computed from it.
- Vectors of different lengths are rejected with `IllegalArgumentException`.

## How it's tested

| Test class | What it covers |
|---|---|
| [DoisTest](../../../../../test/java/com/litroute/util/DoisTest.java) | Finds the DOI in common publisher URL shapes, removing query strings and PDF suffixes. Finds a DOI in plain text and drops trailing punctuation. Handles a percent-encoded DOI. Ignores text that only looks numeric. |
| [VectorsTest](../../../../../test/java/com/litroute/util/VectorsTest.java) | Cosine of parallel, orthogonal and opposite vectors. A zero vector is similar to nothing instead of NaN. Normalizing gives unit length without changing the input. Mean and weighted sum. Different lengths are rejected. |

`AtomicFiles` and `Titles` have no test class of their own. They are tested through the stores (atomic overwrite) and through the dedup and enrichment tests (title matching).
