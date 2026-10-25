# The `profile` module (M5)

**In one sentence:** it turns each student's folder of reference PDFs into a saved profile: one SPECTER2 vector per paper and the paper's text, which M6 then scores incoming papers against.

**Profiles are uniform.** A student's papers are treated as one body of work: M6 compares a candidate with all of the student's papers together (their mean vector) and with the closest few.

It runs at the start of every pipeline run (M9's [PipelineRunner](../pipeline/PipelineRunner.java)), from the UI's **Profiles → Rebuild profiles** (M8's [ProfilesPane](../ui/ProfilesPane.java)), or from [ProfileBuilderCli](ProfileBuilderCli.java), and saves through M7d's [ProfileStore](../store/ProfileStore.java). M6 ([RelevanceScorer](../scoring/RelevanceScorer.java), [LocalPdfCandidates](../scoring/LocalPdfCandidates.java)) reuses [PdfTextExtractor](PdfTextExtractor.java) and [PaperEmbedder](PaperEmbedder.java), so candidate papers that come with a PDF are embedded exactly like a profile's papers.

The module's design and implementation plan, with the reasons for each decision, is in [m5plan.txt](../../../../../../m5plan.txt).

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | The profiles folder (default `~/.litroute/profiles`, from [ProfileBuilder.defaultProfilesRoot()](ProfileBuilder.java#L108)) | One subfolder per student, named after the student. Inside: their reference PDFs (subfolders are read too) and an optional `email.txt` with the addresses M10 sends approved papers to. |
| **Input** | [EmbeddingProvider](../embedding/EmbeddingProvider.java) | The embedding model (in practice [Specter2EmbeddingProvider](../embedding/Specter2EmbeddingProvider.java)): `embed(title, text)` and a `modelId()`. |
| **Output** | [Profile](../model/Profile.java) per folder, saved in M7d (`~/.litroute/data/profiles.json`) | `name`, `emails`, `papers` (a [ProfilePaper](../model/Profile.java#L80) each: path, SHA-256, title, combined vector, the three view vectors, abstract and body text, author keywords), `keywords` (the student's, from [ProfileKeywords](ProfileKeywords.java)), `modelId`, `updatedAt`. |
| **Output** | [BuildReport](ProfileBuilder.java#L80) | Per folder: how many PDFs were embedded, reused, read again for their text or keywords, skipped (and why), the addresses, and whether a profile was stored. Plus the profiles removed because their folder is gone. |

**The folder is the source of truth.** Adding a folder adds a profile, adding or replacing a PDF updates it, deleting the folder removes the profile at the next sync.

**One bad PDF never stops a build.** A corrupt, password-protected or image-only PDF is logged, reported as skipped, and the profile is built from the rest. A folder with no readable PDF gets no profile (and a previously saved one is removed).

## How it works

```
~/.litroute/profiles/<student>/        (PDFs + optional email.txt)
   │
   ▼  ProfileBuilder.syncAll()
   │     load saved profiles from M7d; index their papers by SHA-256 (same model only)
   │
   │  for each folder, for each PDF:
   │     ├─ same hash already in this folder? ─▶ skipped ("same file as ...")
   │     ├─ hash known (any profile)?         ─▶ reuse vectors (re-read text only if the store lacks it)
   │     └─ new hash:
   │          PdfTextExtractor ──▶ PaperText (title, abstract, headings, body chunks)
   │                │                 └─ UnreadablePdfException ─▶ skipped, with reason
   │                ▼
   │          PaperEmbedder    ──▶ ProfilePaper (3 views blended 0.6 / 0.2 / 0.2)
   │
   ▼  ProfileStore.save(...)   only if something besides the timestamp changed
   ▼
Profile in M7d  +  BuildReport
```

### [ProfileBuilder](ProfileBuilder.java): the orchestrator

[syncAll()](ProfileBuilder.java#L121) runs these steps:

1. **Load what is saved.** All profiles are read from the store. Papers embedded with the **current model** are indexed by SHA-256, across all profiles, so a PDF shared by two students, renamed or moved is never embedded twice. Papers from another model are ignored and re-embedded.
2. **List the folders.** Every non-hidden subfolder of the profiles root, sorted by name. The root is created if missing.
3. **Sync each folder** ([sync](ProfileBuilder.java#L158)):
   - read `email.txt` ([readEmails](ProfileBuilder.java#L290)): one or more addresses per line, comma/semicolon/space separated; `#` lines and blanks ignored; non-addresses logged and dropped; duplicates (ignoring case) kept once;
   - walk every non-hidden `*.pdf`, sorted, and hash it;
   - a duplicate within the folder is skipped; a known hash is reused (with its new relative path); an unknown one is extracted and embedded, with progress logged since this takes seconds per PDF;
   - a known paper saved **before text or keywords were kept** has its PDF read again for them, but its vectors (and any text already kept) are kept: nothing is re-embedded;
   - the student's **keywords** are ranked from the papers' author keywords ([ProfileKeywords](ProfileKeywords.java), below).
4. **Save only real changes.** A profile is saved if it is new, gained text or keywords, or differs in model, addresses, files (path + hash) or keywords. An unchanged profile keeps its `updatedAt`. Each changed profile is saved as soon as it is rebuilt, so an interrupted run keeps the finished ones.
5. **Remove orphans.** A saved profile whose folder no longer exists is deleted from the store.

### [PdfTextExtractor](PdfTextExtractor.java): PDF → `PaperText`

PDFs carry no structure, only positioned glyphs, so the parts of a paper are recognized by typography and wording (using PDFBox). Text is read in the PDF's content order, which keeps two-column layouts intact; at most the first **60 pages** are read.

- **Readability checks first.** Fewer than 500 letters in the whole document (a scan without OCR), or less than half the non-space characters being letters (undecodable fonts, a map or drawing), throws `UnreadablePdfException`. So does a corrupt or password-protected file.
- **Page furniture is dropped:** lines that recur on several pages (ignoring digits) and lines that are only a number.
- **Title:** the PDF's metadata title, if it is a real title (not a file name or a tool leftover) and approximately appears on the first pages. Otherwise the largest-font run of lines on page 1 that is clearly larger than the body text and isn't journal branding or an Elsevier masthead. Falls back to the file name.
- **Abstract:** the text after an "Abstract" (or "Executive Summary") heading, searched for in the first 10 pages, up to keywords, "Introduction" or the next numbered heading. Without one, the opening body text stands in for it.
- **Headings:** short lines set in bold or larger than the body text, that are numbered or lead into a paragraph (bold labels inside figures and tables don't).
- **Keywords:** the list after a "Keywords", "Author keywords", "Key words and phrases" or "Index Terms" label on the first two pages, in the three layouts journals use: run in and separated (`;`, `,`, `·`, `•`, `|`), wrapping over lines, even inside a keyword (ASCE: "...; Markov" / "chain Monte Carlo."), or the label alone and one keyword per line (Elsevier). The list stops at a sentence, the abstract, the introduction, a change of type size, or a line ending in a full stop; classification codes, "Corresponding author" footnotes and a glued "1. Introduction" are cut off. At most 12 per paper, each at most 6 words. About half of the students' PDFs list keywords (156 of 297); reports, theses and books don't.
- **Body:** body-size text after the abstract up to the references, acknowledgements or other end matter (only looked for after the first 25% of the document). Captions, footnotes and tables are dropped, end-of-line hyphenation is undone, and the text is cut into ~1500-character passages (fits SPECTER2's 512 tokens with the title), of which up to **6 are kept, spread evenly** so a long paper is represented beyond its introduction.
- Ligatures, zero-width characters and stray first-letter glyphs that some PDF producers draw separately ("ybrid" for "Hybrid") are repaired.

### [PaperText](PaperText.java): what was read from one PDF

A record of `title`, `abstractText` (both never blank), `headings`, `bodyChunks` and the author `keywords` (possibly none). [bodyText()](PaperText.java#L58) joins headings and chunks into the one text M6's word matching uses.

### [PaperEmbedder](PaperEmbedder.java): `PaperText` → vectors

Embeds a paper as three **views**, each with the title in front (`title[SEP]text`, the shape SPECTER2 was trained on):

| View | Text embedded | Weight |
|---|---|---|
| `abstractView` | title + abstract | 0.6 |
| `structureView` | title + headings joined by `; ` (absent if no headings) | 0.2 |
| `bodyView` | mean of title + each body chunk, normalized (absent if no body) | 0.2 |

**How each view is calculated.** Every SPECTER2 call gets `title [SEP] text`, cut to the model's 512 tokens, and returns a unit-length vector ([Specter2EmbeddingProvider.embed](../embedding/Specter2EmbeddingProvider.java#L119)).
- **`abstractView`** = embed(title, abstract) ([PaperEmbedder.java:57](PaperEmbedder.java#L57)).
- **`structureView`** = embed(title, the headings joined by `; `) ([PaperEmbedder.java:58-60](PaperEmbedder.java#L58)). The headings are the ones `PdfTextExtractor` recognized, and they outline what the paper does. For *Time-to-Functionality Fragilities for Performance Assessment of Buildings* (RP2) the embedded text is:
  ```
  Time-to-Functionality Fragilities for Performance Assessment of Buildings [SEP] Introduction;
  Methodology; Overview; Building Performance Model and Ground Motion; Requirements; Loss Analysis;
  Repair Sequencing Using REDi Methodology; Time-to-Functionality Fragility Curves; Illustrative
  Example: Two-Story CLT Building with; Rocking Walls; Building Overview; Building Performance Model
  and IDA; Structural Fragility Selection; Nonstructural Fragility Selection; Repair Sequencing;
  Synthesis of Time-to-Functionality Fragility Curves; Conclusions
  ```
  Generic headings ("Introduction", "Conclusions") add little. Specific ones ("Repair Sequencing Using REDi Methodology", "Structural Fragility Selection") name methods and materials an abstract may leave out. Detection works from typography, so it isn't perfect: above, a heading that wrapped onto two lines became two. There is no structure view if no heading was recognized.
- **`bodyView`** = the mean of embed(title, passage) over the up to 6 body passages, normalized to unit length ([PaperEmbedder.java:62-68](PaperEmbedder.java#L62)). There is no body view if the paper has no body text.

**The combined vector**, which represents the paper ([combine](PaperEmbedder.java#L94); the formula is on [line 106](PaperEmbedder.java#L106)):
```
combined = normalize(0.6 · abstractView + 0.2 · structureView + 0.2 · bodyView)
```
A missing view is left out of the sum. Because the result is normalized, the remaining weights behave as if rescaled: without headings, abstract 0.75 and body 0.25; without body text, abstract 0.75 and structure 0.25; with neither, the abstract view alone. The abstract dominates because that is what SPECTER2 was trained on; the other views add methods and materials the abstract leaves out. A new paper costs at most 8 model passes (1 abstract + 1 structure + up to 6 passages), which is why embedding takes seconds per PDF.

[embed(text)](PaperEmbedder.java#L56) returns the views (used by M6 for candidates); [embed(text, relativePath, sha256)](PaperEmbedder.java#L80) returns a full `ProfilePaper` with its text.

### How a profile stands for a student

**No single profile vector is stored.** A profile is just its papers, each with its own vectors and text; nothing is summarized or grouped when it is built. M6 computes the student's profile vector, the **centroid**, from those papers each time it builds its scorer ([RelevanceScorer.Space](../scoring/RelevanceScorer.java#L842)):

1. **Take the student's distinct papers.** Copies of one paper (same SHA-256, or vectors at cosine ≥ 0.99) count once, so a duplicate doesn't get double weight ([Index.of](../scoring/RelevanceScorer.java#L809), [sameAs](../scoring/RelevanceScorer.java#L786)).
2. **Pick the vectors that match the candidate's evidence.** Use the papers' combined vectors for a candidate with a full PDF, and their abstract views for a candidate with only a title and abstract, or a title. Each student therefore has two centroids ([RelevanceScorer.java:859](../scoring/RelevanceScorer.java#L859)).
3. **Average with equal weight and scale to unit length** ([RelevanceScorer.java:860](../scoring/RelevanceScorer.java#L860)):
   ```
   centroid = normalize((v₁ + v₂ + … + vₙ) / n)
   ```

Because every paper vector has length 1, a candidate's cosine with the centroid equals its **average cosine with all n papers**, divided by the length of their mean (close to 1 when the papers resemble each other). So the centroid stands for the student's work as a whole. M6 blends it with the student's closest papers ([Space.closeness](../scoring/RelevanceScorer.java#L880); the formula is on [line 891](../scoring/RelevanceScorer.java#L891)):
```
similarity = ½ · cosine(candidate, centroid) + ½ · mean cosine to the student's 3 closest papers
```
The second half lets a candidate that matches only part of a student's work still score well. The [scoring readme](../scoring/readme.md#how-a-paper-is-scored) shows where this fits in scoring. `ProfileBuilderCli`'s "similarity of each paper to the profile as a whole" uses the same centroid of combined vectors ([ProfileBuilderCli.java:96](ProfileBuilderCli.java#L96)).

### [ProfileKeywords](ProfileKeywords.java): the student's keywords

What the student works on in a few words, for M6's language model ([AiPaperMatcher](../scoring/ai/AiPaperMatcher.java)): up to **10** of the author keywords of the student's papers.

1. **One keyword however it is spelled.** Keywords are compared by their word stems run together (case, accents, hyphens and spaces folded; a plural "s", then "-ing"/"-ed" dropped), so "Multi-hazards" and "multihazard" are one keyword, as are "reduced-order models" and "Reduced order modeling". It is written as the first paper to list it wrote it. Two PDFs with the same title count as one paper.
2. **Rank by how many papers list it**, so the keywords running through the student's work come first ("multi-hazard", "fragility").
3. **Break ties by how many papers mention it** in their title, abstract or keywords. A keyword of up to 5 letters ("fire", "GIS") must appear as whole words, so "fire" is not found in "wildfire".

From the real folders: RP1-mh-risk gets *Earthquake; multi-hazard; Resilience; Performance-based design; fragility; wind load; uncertainty; Hurricanes; Repair cost; Tall buildings*; RP7-kem-rap gets *rank aggregation; distance measures; Group decision-making; Kemeny method; ...*. General words some authors list ("Assessment", "impact") can still make the ten.

### [UnreadablePdfException](UnreadablePdfException.java)

The checked exception `PdfTextExtractor` throws for a PDF that can't contribute: corrupt or truncated, password-protected, or with no usable text. Its message becomes the `reason` in the report's `SkippedPdf`.

### [ProfileBuilderCli](ProfileBuilderCli.java): the command-line entry point

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.profile.ProfileBuilderCli [-Dexec.args=PROFILES_DIR]
```

Opens SPECTER2 and the default store, runs `syncAll()`, and prints per profile: counts embedded / unchanged / read again for text or keywords / skipped, the addresses, the keywords, each skipped PDF with its reason, the min / mean / max paper-to-paper similarity, and every paper's similarity to the profile as a whole (the normalized mean of its vectors), most similar first, so a paper that doesn't fit the student stands out.

## The classes

| Class | Role |
|---|---|
| [ProfileBuilder](ProfileBuilder.java) | Syncs every student folder into M7d: hashes PDFs, reuses known embeddings, embeds new ones, saves only real changes, removes orphans. Also the `BuildReport`, `ProfileReport` and `SkippedPdf` records. |
| [PdfTextExtractor](PdfTextExtractor.java) | Reads one PDF into a PaperText (title, abstract, headings, sampled body, author keywords) from typography and wording. Also used by M6 for candidate PDFs. |
| [PaperText](PaperText.java) | The text read from one PDF. |
| [PaperEmbedder](PaperEmbedder.java) | Embeds a PaperText as three views and blends them 0.6 / 0.2 / 0.2. Also used by M6. |
| [ProfileKeywords](ProfileKeywords.java) | Ranks a student's author keywords for the AI matcher's prompt. |
| [UnreadablePdfException](UnreadablePdfException.java) | A PDF that can't contribute; becomes a SkippedPdf reason. |
| [ProfileBuilderCli](ProfileBuilderCli.java) | Terminal entry point: sync and print how well each paper fits its profile. |

## Interaction with M7 (the stores)

M5's only store is **M7d** (`~/.litroute/data/profiles.json`, [ProfileStore](../store/ProfileStore.java)), the M5 → M6 boundary:

```
profiles/<student>/*.pdf + email.txt
        │
        ▼ ProfileBuilder.syncAll()
   (1) store.loadAll()   ── every saved profile; papers indexed by SHA-256 when modelId matches
   (2) per folder: reuse / re-read text / embed
   (3) store.save(profile)   ── as soon as that folder is rebuilt, only if it changed
   (4) store.remove(name)    ── folder gone, or no readable PDF left
        │
        ▼
M7d profiles.json ──▶ M6 RelevanceScorer / AiPaperMatcher (via M9)
                  ──▶ M10 ApprovedPaperSender (each student's emails)
                  ──▶ M8 Profiles tab (the table)
```

- **M7d is a cache of the folders.** Deleting `profiles.json` only costs re-embedding time; the folders are the source of truth.
- **One save per changed profile**, each an atomic rewrite of the whole file, so an interrupted rebuild keeps the profiles already finished and never leaves a half-written file.
- **The hash index spans all profiles**, so the same PDF in two students' folders is embedded once, and a moved or renamed PDF keeps its vectors.
- M5 never touches M7a, M7c or M7e. A profile change does not re-score papers already in M7e; it affects the papers scored from then on.

## How it's tested

Following the project's rule, tests use the **real SPECTER2 model** and the real profile folders in `~/.litroute/profiles`; no fake embedding providers.

| Test class | What it covers |
|---|---|
| [PdfTextExtractorTest](../../../../../test/java/com/litroute/profile/PdfTextExtractorTest.java) | Truncated, password-protected and text-less PDFs are unreadable. Every readable test PDF yields a title, abstract and body. A metadata title matches the page text despite small differences. Every keyword read is one keyword (no separators, at most 6 words), and at least a third of the PDFs give some. The keywords of three real papers come out exactly as listed, one per layout (ASCE run-in wrapping inside a keyword, Elsevier one per line, "Key words and phrases."). Extraction is deterministic. Body sampling keeps chunks spread across the text. |
| [PaperEmbedderTest](../../../../../test/java/com/litroute/profile/PaperEmbedderTest.java) | A paper gets unit-length views for what its text has. Missing views leave the abstract view alone. |
| [ProfileBuilderTest](../../../../../test/java/com/litroute/profile/ProfileBuilderTest.java) | Every folder with a readable PDF becomes a profile of dense vectors. `email.txt` parsing skips comments, blanks, duplicates and non-addresses. Held out, a paper is closer to its own profile as a whole than to the others (a clear majority first, nearly all in the top two). A second sync reuses every embedding and leaves unchanged profiles alone. Only a changed PDF is embedded again. Unreadable PDFs are reported and the rest of the profile is still built. A deleted folder removes its profile. A folder with no readable PDF is not saved. A new folder of known papers needs no embedding. Each profile keeps keywords its papers list, at most 10, distinct, the most-listed first. Text missing from an older store is added without re-embedding, and so are keywords (the same ones a fresh build gives). Vectors from another model are recomputed. |
