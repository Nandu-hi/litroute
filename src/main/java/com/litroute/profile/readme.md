# `profile` (M5): student folders → profiles

M5 turns each folder `~/.litroute/profiles/<student>/` into a [Profile](../model/Profile.java) in M7d: one SPECTER2 vector set and the extracted text for each reference PDF, plus the student's addresses (from `email.txt`) and up to 10 keywords. It runs at the start of every pipeline run, from **Profiles → Rebuild profiles**, or from `ProfileBuilderCli`. Design notes are in [m5plan.txt](../../../../../../m5plan.txt).

## Rules

- **The folder is the source of truth.** Adding, replacing or removing a PDF, or deleting the folder, updates the profile at the next sync. Subfolders are read too.
- **Embed once.** PDFs are indexed by SHA-256 across every profile, so a shared, renamed or moved PDF is never embedded twice. A paper saved by a different model is embedded again.
- **One bad PDF never stops a build.** A corrupt, encrypted or image-only PDF is skipped and reported, and the profile is built from the rest. A folder with no readable PDF gets no profile.
- **Save only real changes**, one profile at a time, so an interrupted sync keeps the profiles it finished. A profile whose folder is gone is removed.
- **Uniform profiles.** No profile vector is stored. M6 computes each student's centroid (an equal-weight mean of their distinct papers) at scoring time; see [scoring/calc.md](../scoring/calc.md).

## Reading a PDF ([PdfTextExtractor](PdfTextExtractor.java))

The parts of a paper are found from typography and wording, using PDFBox, in at most the first 60 pages:

- **Unreadable:** fewer than 500 letters in the file, or fewer than half the characters being letters → `UnreadablePdfException`.
- **Title:** the metadata title if it really appears on the first pages; otherwise the largest-font lines on page 1; otherwise the file name.
- **Abstract:** the text after an "Abstract" heading, searched for in the first 10 pages; otherwise the opening body text.
- **Headings:** short bold or large lines that are numbered or lead into a paragraph.
- **Keywords:** the list after "Keywords" / "Index Terms" on pages 1–2, in the ASCE, Elsevier and run-in layouts. At most 12 keywords, each at most 6 words. About half the PDFs list some.
- **Body:** the text between the abstract and the references, with captions, footnotes and tables dropped and end-of-line hyphenation undone. It is cut into ~1,500-character passages, and 6 of them, spread evenly, are kept.

## Embedding ([PaperEmbedder](PaperEmbedder.java))

Each view is `embed(title [SEP] text)`:

| View | Text | Weight |
|---|---|---|
| `abstractView` | abstract | 0.6 |
| `structureView` | headings joined by `; ` (optional) | 0.2 |
| `bodyView` | normalized mean over the ≤ 6 passages (optional) | 0.2 |

`combined = normalize(Σ weight·view)`. A missing view drops out of the sum, and the remaining weights rescale. A new paper takes at most 8 model passes. `-Dlitroute.semantic=abstract` (experimental) keeps `abstractView` only. A profile store built one way must be rebuilt from scratch before matching the other way.

## Classes

| Class | Role |
|---|---|
| [ProfileBuilder](ProfileBuilder.java) | `syncAll()`: hash, reuse, embed, save changes, remove orphans; returns a `BuildReport` |
| [PdfTextExtractor](PdfTextExtractor.java) / [PaperText](PaperText.java) | PDF → title, abstract, headings, body passages, keywords (M6 uses it for candidate PDFs too) |
| [PaperEmbedder](PaperEmbedder.java) | `PaperText` → the three views and the combined vector (M6 uses it too) |
| [ProfileKeywords](ProfileKeywords.java) | The student's top 10 author keywords: spellings are folded together, ranked by how many papers list each one |
| [UnreadablePdfException](UnreadablePdfException.java) | A PDF that can't contribute; its message is the skip reason |
| [ProfileBuilderCli](ProfileBuilderCli.java) | Sync, then print per student: counts, keywords, skipped PDFs, and how well each paper fits its profile |

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.profile.ProfileBuilderCli [-Dexec.args=PROFILES_DIR]
```

## Tests

The tests use the real SPECTER2 model and the real folders in `~/.litroute/profiles`:

- [PdfTextExtractorTest](../../../../../test/java/com/litroute/profile/PdfTextExtractorTest.java): unreadable PDFs are rejected; each layout's keywords come out exactly; extraction is deterministic.
- [PaperEmbedderTest](../../../../../test/java/com/litroute/profile/PaperEmbedderTest.java): the views are unit-length, and missing views are handled.
- [ProfileBuilderTest](../../../../../test/java/com/litroute/profile/ProfileBuilderTest.java): a held-out paper is closest to its own profile; a second sync embeds nothing; only a changed PDF is embedded again; deleted folders are removed; an older store gains text and keywords without being re-embedded; vectors from another model are recomputed.
