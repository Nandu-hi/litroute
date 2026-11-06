# `profile` (M5): student folders → profiles

Turns each `~/.litroute/profiles/<student>/` into a [Profile](../model/Profile.java) in M7d: SPECTER2 vectors and text for each PDF, the addresses from `email.txt`, and up to 10 keywords. It runs at the start of every pipeline run, from **Rebuild profiles**, or from `ProfileBuilderCli`.

## Rules

- **The folder is the truth:** adding, changing or removing a PDF (or the folder) updates the profile at the next sync.
- **Embed once:** PDFs are keyed by SHA-256, so a moved or shared PDF isn't embedded again. Vectors from another model are recomputed.
- **One bad PDF never stops a build:** corrupt, encrypted or scanned PDFs are skipped and reported. A folder with no readable PDF gets no profile.
- **Save only real changes**, one profile at a time.

## Reading a PDF ([PdfTextExtractor](PdfTextExtractor.java))

The parts are found by typography and wording (first 60 pages): the **title** (metadata if it appears on the page, else the largest font on page 1), the **abstract** (after "Abstract", else the opening text), **headings** (short bold or large lines), **keywords** (after "Keywords"/"Index Terms"), and the **body** (6 passages sampled from abstract to references). Fewer than 500 letters, or mostly non-letters → `UnreadablePdfException`.

## Embedding ([PaperEmbedder](PaperEmbedder.java))

`combined = normalize(0.6·abstract + 0.2·headings + 0.2·body)`. Each view is `embed(title [SEP] text)`; a missing view drops out and the weights rescale.

| Class | Role |
|---|---|
| [ProfileBuilder](ProfileBuilder.java) | `syncAll()`: hash, reuse, embed, save, remove orphans |
| [PdfTextExtractor](PdfTextExtractor.java) / [PaperText](PaperText.java) | PDF → parts (M6 uses it for candidate PDFs too) |
| [PaperEmbedder](PaperEmbedder.java) | Parts → views + combined vector |
| [ProfileKeywords](ProfileKeywords.java) | Top 10 author keywords, by how many papers list them |
| [ProfileBuilderCli](ProfileBuilderCli.java) | Sync and print how well each paper fits its profile |

**Tests** (real model, real profiles): [PdfTextExtractorTest](../../../../../test/java/com/litroute/profile/PdfTextExtractorTest.java), [PaperEmbedderTest](../../../../../test/java/com/litroute/profile/PaperEmbedderTest.java), [ProfileBuilderTest](../../../../../test/java/com/litroute/profile/ProfileBuilderTest.java): held-out papers find their profile, a second sync embeds nothing, only changed PDFs are re-embedded.
