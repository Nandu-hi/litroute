# The `profile` module (M5)

**In one sentence:** it turns each student's folder of reference PDFs into a saved profile: one SPECTER2 vector per paper and the paper's text, which M6 then scores incoming papers against.

**Profiles are uniform.** A student's papers are treated as one body of work: M6 compares a candidate with all of the student's papers together (their mean vector) and with the closest few.

It is run by [ProfileBuilderCli](ProfileBuilderCli.java) for now (until M9/M8 trigger it), and saves through M7d's [ProfileStore](../store/ProfileStore.java). M6 ([RelevanceScorer](../scoring/RelevanceScorer.java), [LocalPdfCandidates](../scoring/LocalPdfCandidates.java)) reuses [PdfTextExtractor](PdfTextExtractor.java) and [PaperEmbedder](PaperEmbedder.java), so candidate papers that come with a PDF are embedded exactly like a profile's papers.

The module's design and implementation plan, with the reasons for each decision, is in [m5plan.txt](../../../../../../m5plan.txt).

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | The profiles folder (default `~/.litroute/profiles`, from [ProfileBuilder.defaultProfilesRoot()](ProfileBuilder.java#L95)) | One subfolder per student, named after the student. Inside: their reference PDFs (subfolders are read too) and an optional `email.txt` with the addresses M10 sends approved papers to. |
| **Input** | [EmbeddingProvider](../embedding/EmbeddingProvider.java) | The embedding model (in practice [Specter2EmbeddingProvider](../embedding/Specter2EmbeddingProvider.java)): `embed(title, text)` and a `modelId()`. |
| **Output** | [Profile](../model/Profile.java) per folder, saved in M7d (`~/.litroute/data/profiles.json`) | `name`, `emails`, `papers` (a [ProfilePaper](../model/Profile.java#L57) each: path, SHA-256, title, combined vector, the three view vectors, abstract and body text), `modelId`, `updatedAt`. |
| **Output** | [BuildReport](ProfileBuilder.java#L79) | Per folder: how many PDFs were embedded, reused, given their text, skipped (and why), the addresses, and whether a profile was stored. Plus the profiles removed because their folder is gone. |

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

[syncAll()](ProfileBuilder.java#L106) runs these steps:

1. **Load what is saved.** All profiles are read from the store. Papers embedded with the **current model** are indexed by SHA-256, across all profiles, so a PDF shared by two students, renamed or moved is never embedded twice. Papers from another model are ignored and re-embedded.
2. **List the folders.** Every non-hidden subfolder of the profiles root, sorted by name. The root is created if missing.
3. **Sync each folder** ([sync](ProfileBuilder.java#L136)):
   - read `email.txt` ([readEmails](ProfileBuilder.java#L238)): one or more addresses per line, comma/semicolon/space separated; `#` lines and blanks ignored; non-addresses logged and dropped; duplicates (ignoring case) kept once;
   - walk every non-hidden `*.pdf`, sorted, and hash it;
   - a duplicate within the folder is skipped; a known hash is reused (with its new relative path); an unknown one is extracted and embedded, with progress logged since this takes seconds per PDF;
   - a known paper saved **before text was kept** has its PDF read again for the text, but its vectors are kept: nothing is re-embedded.
4. **Save only real changes.** A profile is saved if it is new, gained text, or differs in model, addresses or files (path + hash). An unchanged profile keeps its `updatedAt`. Each changed profile is saved as soon as it is rebuilt, so an interrupted run keeps the finished ones.
5. **Remove orphans.** A saved profile whose folder no longer exists is deleted from the store.

### [PdfTextExtractor](PdfTextExtractor.java): PDF → `PaperText`

PDFs carry no structure, only positioned glyphs, so the parts of a paper are recognized by typography and wording (using PDFBox). Text is read in the PDF's content order, which keeps two-column layouts intact; at most the first **60 pages** are read.

- **Readability checks first.** Fewer than 500 letters in the whole document (a scan without OCR), or less than half the non-space characters being letters (undecodable fonts, a map or drawing), throws `UnreadablePdfException`. So does a corrupt or password-protected file.
- **Page furniture is dropped:** lines that recur on several pages (ignoring digits) and lines that are only a number.
- **Title:** the PDF's metadata title, if it is a real title (not a file name or a tool leftover) and approximately appears on the first pages. Otherwise the largest-font run of lines on page 1 that is clearly larger than the body text and isn't journal branding or an Elsevier masthead. Falls back to the file name.
- **Abstract:** the text after an "Abstract" (or "Executive Summary") heading, searched for in the first 10 pages, up to keywords, "Introduction" or the next numbered heading. Without one, the opening body text stands in for it.
- **Headings:** short lines set in bold or larger than the body text, that are numbered or lead into a paragraph (bold labels inside figures and tables don't).
- **Body:** body-size text after the abstract up to the references, acknowledgements or other end matter (only looked for after the first 25% of the document). Captions, footnotes and tables are dropped, end-of-line hyphenation is undone, and the text is cut into ~1500-character passages (fits SPECTER2's 512 tokens with the title), of which up to **6 are kept, spread evenly** so a long paper is represented beyond its introduction.
- Ligatures, zero-width characters and stray first-letter glyphs that some PDF producers draw separately ("ybrid" for "Hybrid") are repaired.

### [PaperText](PaperText.java): what was read from one PDF

A record of `title`, `abstractText` (both never blank), `headings` and `bodyChunks`. [bodyText()](PaperText.java#L31) joins headings and chunks into the one text M6's word matching uses.

### [PaperEmbedder](PaperEmbedder.java): `PaperText` → vectors

Embeds a paper as three **views**, each with the title in front (`title[SEP]text`, the shape SPECTER2 was trained on):

| View | Text embedded | Weight |
|---|---|---|
| `abstractView` | title + abstract | 0.6 |
| `structureView` | title + headings joined by `; ` (absent if no headings) | 0.2 |
| `bodyView` | mean of title + each body chunk, normalized (absent if no body) | 0.2 |

**How each view is calculated.** Every SPECTER2 call gets `title [SEP] text`, cut to the model's 512 tokens, and returns a unit-length vector ([Specter2EmbeddingProvider.embed](../embedding/Specter2EmbeddingProvider.java#L84)).
- **`abstractView`** = embed(title, abstract) ([PaperEmbedder.java:45](PaperEmbedder.java#L45)).
- **`structureView`** = embed(title, the headings joined by `; `) ([PaperEmbedder.java:46-48](PaperEmbedder.java#L46)). The headings are the ones `PdfTextExtractor` recognized, and they outline what the paper does. For *Time-to-Functionality Fragilities for Performance Assessment of Buildings* (RP2) the embedded text is:
  ```
  Time-to-Functionality Fragilities for Performance Assessment of Buildings [SEP] Introduction;
  Methodology; Overview; Building Performance Model and Ground Motion; Requirements; Loss Analysis;
  Repair Sequencing Using REDi Methodology; Time-to-Functionality Fragility Curves; Illustrative
  Example: Two-Story CLT Building with; Rocking Walls; Building Overview; Building Performance Model
  and IDA; Structural Fragility Selection; Nonstructural Fragility Selection; Repair Sequencing;
  Synthesis of Time-to-Functionality Fragility Curves; Conclusions
  ```
  Generic headings ("Introduction", "Conclusions") add little. Specific ones ("Repair Sequencing Using REDi Methodology", "Structural Fragility Selection") name methods and materials an abstract may leave out. Detection works from typography, so it isn't perfect: above, a heading that wrapped onto two lines became two. There is no structure view if no heading was recognized.
- **`bodyView`** = the mean of embed(title, passage) over the up to 6 body passages, normalized to unit length ([PaperEmbedder.java:50-55](PaperEmbedder.java#L50)). There is no body view if the paper has no body text.

**The combined vector**, which represents the paper ([combine](PaperEmbedder.java#L69); the formula is on [line 81](PaperEmbedder.java#L81)):
```
combined = normalize(0.6 · abstractView + 0.2 · structureView + 0.2 · bodyView)
```
A missing view is left out of the sum. Because the result is normalized, the remaining weights behave as if rescaled: without headings, abstract 0.75 and body 0.25; without body text, abstract 0.75 and structure 0.25; with neither, the abstract view alone. The abstract dominates because that is what SPECTER2 was trained on; the other views add methods and materials the abstract leaves out. A new paper costs at most 8 model passes (1 abstract + 1 structure + up to 6 passages), which is why embedding takes seconds per PDF.

[embed(text)](PaperEmbedder.java#L44) returns the views (used by M6 for candidates); [embed(text, relativePath, sha256)](PaperEmbedder.java#L61) returns a full `ProfilePaper` with its text.

### How a profile stands for a student

**No single profile vector is stored.** A profile is just its papers, each with its own vectors and text; nothing is summarized or grouped when it is built. M6 computes the student's profile vector, the **centroid**, from those papers each time it builds its scorer ([RelevanceScorer.Space](../scoring/RelevanceScorer.java#L650)):

1. **Take the student's distinct papers.** Copies of one paper (same SHA-256, or vectors at cosine ≥ 0.99) count once, so a duplicate doesn't get double weight ([Index.of](../scoring/RelevanceScorer.java#L629), [sameAs](../scoring/RelevanceScorer.java#L612)).
2. **Pick the vectors that match the candidate's evidence.** Use the papers' combined vectors for a candidate with a full PDF, and their abstract views for a candidate with only a title and abstract, or a title. Each student therefore has two centroids ([RelevanceScorer.java:661](../scoring/RelevanceScorer.java#L661)).
3. **Average with equal weight and scale to unit length** ([RelevanceScorer.java:662](../scoring/RelevanceScorer.java#L662)):
   ```
   centroid = normalize((v₁ + v₂ + … + vₙ) / n)
   ```

Because every paper vector has length 1, a candidate's cosine with the centroid equals its **average cosine with all n papers**, divided by the length of their mean (close to 1 when the papers resemble each other). So the centroid stands for the student's work as a whole. M6 blends it with the student's closest papers ([Space.closeness](../scoring/RelevanceScorer.java#L670); the formula is on [line 681](../scoring/RelevanceScorer.java#L681)):
```
similarity = ½ · cosine(candidate, centroid) + ½ · mean cosine to the student's 3 closest papers
```
The second half lets a candidate that matches only part of a student's work still score well. The [scoring readme](../scoring/readme.md#how-a-paper-is-scored) shows where this fits in scoring. `ProfileBuilderCli`'s "similarity of each paper to the profile as a whole" uses the same centroid of combined vectors ([ProfileBuilderCli.java:84](ProfileBuilderCli.java#L84)).

### [UnreadablePdfException](UnreadablePdfException.java)

The checked exception `PdfTextExtractor` throws for a PDF that can't contribute: corrupt or truncated, password-protected, or with no usable text. Its message becomes the `reason` in the report's `SkippedPdf`.

### [ProfileBuilderCli](ProfileBuilderCli.java): the command-line entry point

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.profile.ProfileBuilderCli [-Dexec.args=PROFILES_DIR]
```

Opens SPECTER2 and the default store, runs `syncAll()`, and prints per profile: counts embedded / unchanged / given text / skipped, the addresses, each skipped PDF with its reason, the min / mean / max paper-to-paper similarity, and every paper's similarity to the profile as a whole (the normalized mean of its vectors), most similar first, so a paper that doesn't fit the student stands out.

## How it's tested

Following the project's rule, tests use the **real SPECTER2 model** and the real profile folders in `~/.litroute/profiles`; no fake embedding providers.

| Test class | What it covers |
|---|---|
| [PdfTextExtractorTest](../../../../../test/java/com/litroute/profile/PdfTextExtractorTest.java) | Truncated, password-protected and text-less PDFs are unreadable. Every readable test PDF yields a title, abstract and body. A metadata title matches the page text despite small differences. Extraction is deterministic. Body sampling keeps chunks spread across the text. |
| [PaperEmbedderTest](../../../../../test/java/com/litroute/profile/PaperEmbedderTest.java) | A paper gets unit-length views for what its text has. Missing views leave the abstract view alone. |
| [ProfileBuilderTest](../../../../../test/java/com/litroute/profile/ProfileBuilderTest.java) | Every folder with a readable PDF becomes a profile of dense vectors. `email.txt` parsing skips comments, blanks, duplicates and non-addresses. Held out, a paper is closer to its own profile as a whole than to the others (a clear majority first, nearly all in the top two). A second sync reuses every embedding and leaves unchanged profiles alone. Only a changed PDF is embedded again. Unreadable PDFs are reported and the rest of the profile is still built. A deleted folder removes its profile. A folder with no readable PDF is not saved. A new folder of known papers needs no embedding. Text missing from an older store is added without re-embedding. Vectors from another model are recomputed. |
