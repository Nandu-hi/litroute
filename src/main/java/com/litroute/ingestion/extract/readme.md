# The `extract` module (M2)

**In one sentence:** it takes one alert email (SpringerOpen, Taylor & Francis, Google Scholar, ScienceDirect, ASCE…), finds every paper listed in its HTML, and turns each one into a `PaperCandidate`.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | [RawEmail](../../model/RawEmail.java) (from M1) | `messageId`, subject, sender, received time, `htmlBody`, `plainTextBody`. Only `htmlBody` is used, in both modes. With no HTML body, the result is an empty list. |
| **Output** | `List<PaperCandidate>` ([PaperCandidate](../../model/PaperCandidate.java)) | Zero or more papers in the order they appear in the email. Each has `title` (required), `authors`, `venue`, `year`, `articleType`, `doi`, `url`, `abstractSnippet`, `pdfPath` and `sourceMessageId`. Any field the email doesn't state stays `Optional.empty()`. |

You call it through [PaperExtractor.extract(RawEmail)](PaperExtractor.java). For ordinary failures it never throws: if enrichment fails, the paper is kept exactly as parsed, and an email with no papers gives `[]`. The exception is a local model that can't be reached, in Local AI mode: the batch fails with nothing saved, so its emails are read again next run.

## How it works

The papers are found in one of two ways, [ExtractionMode](ExtractionMode.java), chosen per run: the **Read emails with** toggle in the UI's Pipeline tab, or `PipelineRunnerCli --extraction=pattern|llm`. Enrichment is the same for both.

```
RawEmail.htmlBody
   │
   ▼  AlertLines        HTML → ordered visual "lines" (jsoup); links keep hrefs, images,
   │                    hidden elements and the Gmail forward header/signature are dropped
   ▼  AlertHtmlParser   find paper blocks → title, authors, venue, year, type, abstract
   │     ├─ AuthorNames     strict author-line parser
   │     └─ LinkUnwrapper   classify/unwrap each link → READABLE / OPAQUE / NONE
   ▼  ParsedPaper(candidate, linkKind)                  every block, notes included
   │
   ├─ PATTERN: the research papers among the blocks
   │
   └─ LLM:     AiTitleExtractor: prompts.txt + the email ─▶ Ollama ─▶ {"titles": [...]},
   │           checked against the email; each title takes its block's details, if it has one
   │
   ▼  PaperEnricher     fills DOI/metadata/PDF; NONE = no-op
   │                    (LLM: a title with no block is dropped unless it now has a DOI)
   ▼
List<PaperCandidate>
```

### Pattern matching ([AlertHtmlParser](AlertHtmlParser.java))

The parser doesn't have a separate rule set for each publisher. It relies on one visual pattern that every alert shares:

1. **Flatten to lines.** [AlertLines](AlertLines.java) starts a new line at every block element and `<br>`. Each line remembers whether it is a heading or an `<hr>` rule.
2. **Title line.** A line counts as a title when it is nothing but one http(s) link, has at least 3 words, and isn't "furniture" text like "Unsubscribe", "Read article" or "Full Text". A tag such as `[PDF]` at the start of the title is stripped, whether it is inside the link or beside it (Google Scholar puts it in a span of its own). Not titles:
   - a Google Maps link, which Gmail makes of a street address when a message is forwarded ("London, N1 9XW,");
   - a link that is the journal's own name from the issue header (ASCE's "Natural Hazards Review");
   - a linked author name right under a title (ASCE links authors to their profiles: "Jane A. Doe").
3. **Block.** The lines after a title, up to the next title, heading or rule (12 lines at most), form that paper's block. **The title only becomes a paper if the block has an author list or a venue line.** This check keeps stray links out of the results.
4. **Reading the block** ([readBlock](AlertHtmlParser.java)):
   - **Authors:** [AuthorNames](AuthorNames.java) splits on `, ; & and`, drops placeholders like "others" and "…", and rejects the whole line if any piece doesn't look like a name. A link line counts too when it is a list of names with lowercase letters in them, so ASCE's linked sole authors are kept and a footer menu link such as "ABOUT ASCE PUBLICATIONS" is not.
   - **Venue and year:** taken from a line containing a year or a volume number. For example, "Ocean Engineering, Volume 311, … August 2024" gives venue "Ocean Engineering" and year 2024. A copyright line ("© 2025 BioMed Central …") and the issue header ("VOLUME: 27 | …") never count, so a footer link above them is not a paper.
   - **Combined lines:** Scholar-style "Authors – Venue, 2026" lines are split into both parts.
   - **Abstract:** the first line of 10 or more words.
5. **Issue header fallback.** For table-of-contents mails (ASCE's "VOLUME: 27 | NUMBER: 4 | November 2026") or headlines like "New articles for &lt;journal&gt;", the venue and year come from the header when a block doesn't state them.
6. **Article type.** Taken from a label just before or after the title, otherwise from the nearest label above it. As in a table of contents, a section label ("Technical Papers", "Case Studies", whether a heading or a plain line) heads every paper under it until the next one. A label right above the next title belongs to that next paper, not to the one before it. A label is always a few words ending in a type word (article, papers, studies, notes, reviews, discussions, closures, corrections, …), so the email's banner ("New articles have been published") is never a type.
7. **Notes are dropped.** A book review, discussion, closure, erratum, correction, editorial and the like is not a research paper and never leaves M2 ([PaperCandidate.isResearch](../../model/PaperCandidate.java)), recognized by its section label or, for alerts without labels, by its title ("Erratum for …", "Discussion of …"). So it is never enriched, stored or matched: a book review can read exactly like a student's papers (one scored relevance 0.96). ASCE's 22-entry table of contents gives its 17 research papers.
8. **Links and DOI.** Every href in the block goes through [LinkUnwrapper](LinkUnwrapper.java), which never touches the network:
   - **READABLE:** a plain publisher URL, or a redirect wrapper whose real target can be percent-decoded (`scholar_url?url=…`, Elsevier's `/CL0/https:%2F%2F…`). Nested wrappers are unwrapped up to 4 levels deep.
   - **OPAQUE:** a click-tracking token with no recoverable target. Its URL is dropped, because it identifies nothing and carries the recipient's tracking ID.
   - **NONE:** no usable http link (`mailto:`, `cid:` and so on).

   The DOI is searched for in the resolved URL, then in the raw hrefs, then in the block text.
9. **Dedup within one mail.** If several links point to the same paper (title, "Full Text", "Read article"), it is emitted once. The match uses a normalized title.

### Local AI model ([AiTitleExtractor](AiTitleExtractor.java))

A small model running on this machine (`llama3.2:3b` on [Ollama](https://ollama.com), no GPU needed) names the titles in each email, and the Crossref/OpenAlex lookup then finds each one's DOI. It isn't tied to a layout, so it can read an alert pattern matching can't follow.

**What it decides.** Which papers an email lists is the model's call. Pattern matching still runs underneath, without a say in which papers count, for the details it can read from the email and the notes it recognizes:

| The model names a title that | Outcome |
|---|---|
| pattern matching read as a paper | kept, with the authors, venue, abstract and links read from the email |
| pattern matching read as a note (book review, erratum…), or is the journal's own name | dropped; the model lists such notes although told not to, and "Review of Fire and Flood…" can't be told from a paper by its title |
| pattern matching didn't read | kept only if enrichment gives it a DOI, from its own link or a lookup; otherwise logged and dropped |
| isn't in the email | refused: it matches nothing (the model has invented a plausible title in trials) |

**A paper the model doesn't name is left out**, even if pattern matching read it, and the log says "Pattern matching would also have found: …". Measured with the recordings: 25 of the 26 papers on the six alerts as received (it missed a ScienceDirect paper), and all 26 on their tidied copies. Pattern matching finds all of them, which is why it is the default.

**When the model fails:**

- **It can't be reached** (Ollama stopped, model not downloaded, timed out): the batch fails with a message saying what to do, before anything is saved, so the emails stay unprocessed. The toggle said to use the model, so the run doesn't quietly do something else.
- **It gives no usable answer** about part of an email: that one email falls to pattern matching, with a warning, rather than being recorded as done with papers missing.

**The prompt** is [src/main/resources/prompts.txt](../../../../../resources/prompts.txt). [PromptGenerator](PromptGenerator.java) fills its `{body}` placeholder with the email and refuses a file without exactly one. The body is not the raw HTML: [AlertLines.toXml](AlertLines.java) renders the lines as simplified XML with `<h>`, `<p>`, `<a>` and `<hr/>`, which takes the ASCE alert from 174 KB to 6 KB and leaves out the links' tracking tokens. Angle brackets in the email's text are escaped, so an email cannot close the prompt's `<email>` element, and lines past 600 characters are cut since no title is that long.

**Slices.** Given all 22 ASCE entries at once, the model repeated itself until it ran out of tokens. So an email goes in slices of about 3,200 characters, each repeating the last two lines of the one before (a title then keeps its author line), and the 4,096-token context always has room for the slice and its answer.

**Keeping the output's integrity:**

1. **Shape enforced while generating.** The request carries a JSON Schema in Ollama's `format` field (`{"titles": [string]}`, nothing else), with temperature 0 and a fixed seed.
2. **Shape checked on arrival.** The text must parse as exactly one object holding only a `titles` list of strings; anything else ([titlesIn](AiTitleExtractor.java)) is asked once more with another seed, then the email falls to pattern matching.
3. **Complete or not at all.** The answer budget is tied to the slice's length, as a title is copied from it. An answer stopped at the budget (`done_reason: length`) is never read: the slice is asked again in two halves.
4. **Verbatim or not at all.** A title must equal a whole link or run of text in the email, ignoring case, spacing and punctuation. Nothing is matched in part, so "Advances in Landslide Detection…" does not pass on the strength of the line "Erratum for “Advances in Landslide Detection…”". **The title kept is the email's own text**, never the model's copy.
5. **Must read as a title.** Footer text, a list of names, a section label, a copyright line or an issue header ([readsAsTitle](AlertHtmlParser.java)) is refused even though the email states it.

These checks also limit what an email's own text can do by addressing the model: at worst it gets a line of that same email looked up by title.

**Settings:** `LITROUTE_OLLAMA_MODEL` (default `llama3.2:3b`), `LITROUTE_OLLAMA_URL` (default `http://localhost:11434`; [OllamaClient](OllamaClient.java) refuses any address not on this machine, so an email never leaves it). Speed on a CPU: 5–10 seconds for a one-paper alert, about 20 for ScienceDirect's five, about 100 for ASCE's table of contents.

### Enrichment ([PaperEnricher](PaperEnricher.java))

This is an interface that receives the parsed candidate plus its `LinkKind`:
- **`PaperEnricher.NONE` / `PaperExtractor.offline()`:** parse only, with no network.
- **`OnlinePaperEnricher`** (in the `enrich` package, M3): a READABLE link can be checked against the publisher's page. An OPAQUE link can only be resolved by title through Crossref/OpenAlex. Either way it fills in empty fields and downloads the PDF. Text from the email is never overwritten.
- **`CachingPaperEnricher`:** wraps the online enricher with the M7c lookup cache.

### The batch wrapper: [AlertBatchProcessor](AlertBatchProcessor.java)

This class lives in `extract`, but it ties M2, M3, M4 and the stores together:

- **Input:** `List<RawEmail>`.
- **Output:** every paper now held in M7c, enriched and deduplicated.
- **Ordering, for crash safety:**
  1. Extract and enrich the whole batch, writing nothing to disk.
  2. Merge with the papers already held, deduplicate, and save to M7c in one write.
  3. Write the processed email IDs to M7a last.
- **[forgetAll()](AlertBatchProcessor.java):** empties M7c (papers and lookup cache), then M7a, in that order, so the same emails are extracted and enriched afresh on the next run. A crash in between leaves the emails recorded, so they are skipped, never lost. M9's **Clear and re-run** calls it after marking the emails unread.

  A crash at any point means the batch is re-run rather than silently skipped. A re-run replaces that batch's earlier entries instead of duplicating them.

## The classes

| Class | Role |
|---|---|
| [PaperExtractor](PaperExtractor.java) | M2's entry point: find one email's papers in the chosen mode, then enrich each (keeping it as parsed if enrichment throws). |
| [PromptGenerator](PromptGenerator.java) | Fills `prompts.txt`'s `{body}` with the email. |
| [ExtractionMode](ExtractionMode.java) | PATTERN / LLM: how a run finds the papers. |
| [AiTitleExtractor](AiTitleExtractor.java) | LLM mode: slices the email, asks the model, checks every answer, and returns the papers it found. |
| [OllamaClient](OllamaClient.java) | The local model over Ollama's chat API: a JSON Schema in `format`, temperature 0, this machine only. |
| [AlertHtmlParser](AlertHtmlParser.java) | Package-private. Pattern matching: finds the paper blocks in the email's lines and reads title, authors, venue, year, type, abstract, links and DOI. |
| [AlertLines](AlertLines.java) | Package-private. Flattens the HTML into visual lines of text and link segments, and renders those lines as the model's simplified XML. |
| [AuthorNames](AuthorNames.java) | Package-private. Strict author-line parser: a line is authors only if every piece is a name. |
| [LinkUnwrapper](LinkUnwrapper.java) | Package-private. Unwraps redirect links and classifies them READABLE / OPAQUE / NONE, offline. |
| [LinkKind](LinkKind.java) | READABLE / OPAQUE / NONE: decides how M3 may enrich a paper. |
| [ParsedPaper](ParsedPaper.java) | Package-private record: a parsed candidate plus its LinkKind. |
| [PaperEnricher](PaperEnricher.java) | The enrichment stage's interface; `NONE` is the offline no-op, M3 provides the real ones. |
| [AlertBatchProcessor](AlertBatchProcessor.java) | Runs a batch through M2-M4 and persists it: M7c, then M7a. |

## Interaction with M7 (the stores)

Only [AlertBatchProcessor](AlertBatchProcessor.java) touches the stores; the parser and extractor are pure.

```
                ┌── M7c enrichment_cache.json ──┐            ┌── M7a email_cache.json ──┐
   (1) load()   │ enriched: [...]  lookups: {…} │            │ ["<id1>", "<id2>", ...]  │   (1) load()
                └──────────┬─────────────▲──────┘            └───────────▲──────────────┘
                lookups map│ (live)      │ (3) save(deduplicated, lookups)│ (4) save(ids ∪ batch) LAST
                           ▼             │                                │
  RawEmails ──▶ ExtractorFactory.create(lookups) ──▶ PaperExtractor.extract (per email)
                       └ CachingPaperEnricher reads/adds lookups ──▶ (2) held papers − this batch's old ones
                                                                         + extracted ──▶ Deduplicator (M4)
```

- **Load both first**, so a corrupt store fails the batch before any network call or download is spent.
- **Nothing is written while extracting.** The lookup cache is filled in memory and saved with the papers in one M7c write.
- **M7a last.** A crash before it leaves the emails unrecorded, so the next run processes the batch again; its papers replace the earlier copies (same `sourceMessageId`), and the PDFs already on disk are reused.
- **forgetAll()** writes empty M7c, then empty M7a: the reverse dependency, so a crash in between leaves emails recorded (skipped) rather than papers whose emails are forgotten.

## How it's tested

Everything is driven by **the six real alert emails the client forwarded**, stored as Gmail-style forwards in [src/test/resources/samples/](../../../../../../test/resources/samples/). That includes the forwarder's note, signature and "Forwarded message" header. There are no made-up HTML snippets.

The visible text was copied from PDF prints of the mails. Because a PDF print loses hrefs, the links were rebuilt from each paper's real DOI in that publisher's real link style (direct, decodable wrapper, or opaque token).

| Test class | Level | What it checks |
|---|---|---|
| [PaperExtractorTest](../../../../../../test/java/com/litroute/ingestion/extract/PaperExtractorTest.java) | Offline parse (`PaperExtractor.offline()`) | Exact paper counts per mail (1, 1, 1, 1, 5, 17: ASCE's discussion, closure, book reviews and erratum are dropped). Exact titles, authors, venue, year, type and DOI for each paper. Opaque-link papers are kept, with no DOI. A ScienceDirect PII never becomes a bogus DOI. The year comes from the paper, never from the forward's date. Across all six mails: no furniture or forwarder text ever becomes a paper or author, no paper appears twice per mail, and a note-only mail gives `[]`. |
| [LinkUnwrapperTest](../../../../../../test/java/com/litroute/ingestion/extract/LinkUnwrapperTest.java) | Unit | Plain URL, query-value wrapper, path-segment wrapper, nested wrappers, opaque trackers, publisher hosts that look like trackers (`link.springer.com`), and `mailto:`/`javascript:`/null links. |
| [ReceivedAlertsTest](../../../../../../test/java/com/litroute/ingestion/extract/ReceivedAlertsTest.java) | Offline parse of the six alerts **exactly as they reached the inbox** (`src/test/resources/samples/received/`: real HTML with tracking links, Gmail's address links and plain-line section labels) | Each gives the same papers (title, authors, venue, year, type) as its tidied copy in `samples/`. No footer address, copyright line or journal-name link becomes a paper. ASCE section labels head every paper under them and the banner is never a type. ASCE's linked sole authors keep their papers. Scholar's title with "[PDF]" beside the link is found. |
| [AuthorNamesTest](../../../../../../test/java/com/litroute/ingestion/extract/AuthorNamesTest.java) | Unit | Separators and placeholders, name particles (`van de`), hyphens and initials, and rejection of venue, abstract and furniture lines. |
| [AlertBatchProcessorTest](../../../../../../test/java/com/litroute/ingestion/extract/AlertBatchProcessorTest.java) | Integration with M7a/M7c in a `TemporaryFolder` | Nothing is written during enrichment. M7c holds the enriched paper with the real DOI and a real PDF on disk. Papers sharing a DOI are merged before saving, both within a batch and across runs. If M7c fails to save, M7a stays unwritten. A paper-less email is still marked processed. A crash re-run replaces instead of duplicating. The lookup cache is persisted and reused, with no HTTP calls on the second run. |
| [PaperExtractorPipelineTest](../../../../../../test/java/com/litroute/ingestion/enrich/PaperExtractorPipelineTest.java) (in `enrich`) | End-to-end M2 + M3 | All six mails through the real `OnlinePaperEnricher`. Every one of the 31 papers ends up with its DOI. With the model's recorded answers: the same papers as pattern matching; a paper only the model could read is completed by enrichment; a title no lookup confirms is left out; a model that isn't running fails the batch with nothing saved. |
| [AiTitleExtractorTest](../../../../../../test/java/com/litroute/ingestion/extract/AiTitleExtractorTest.java) | The model's real answers on the six alerts as received, and the checks | Every verified title is one pattern matching read too, each paper comes with its details, at least 24 of 26 are found, and a mail pattern matching can't read still gives its paper. Refused: an invented title, part of a line, names and footer text, a recognized note. Kept text is the email's. An answer cut short is asked again in halves; a malformed one with another seed, then the email falls to pattern matching. Only `{"titles": [strings]}` counts as an answer. Slices stay short, cover every line and overlap. |
| [PromptGeneratorTest](../../../../../../test/java/com/litroute/ingestion/extract/PromptGeneratorTest.java), [OllamaClientTest](../../../../../../test/java/com/litroute/ingestion/extract/OllamaClientTest.java) | Unit | `{body}` exactly once, filled in literally. The request's model, schema, seed and budget; an answer stopped at the budget; a missing model or stopped server says what to do; only this machine is accepted. |

Network-dependent tests use **[RecordedHttp](../../../../../../test/java/com/litroute/ingestion/enrich/RecordedHttp.java)**. It replays the actual Crossref, OpenAlex and publisher responses, including the PDFs and the 403s, captured once from a live run into [src/test/resources/http/](../../../../../../test/resources/http/). The tests are offline and deterministic but still exercise real payloads. If code asks for a URL that wasn't recorded, the test fails instead of quietly skipping it.

### Recording the test answers

The model's tests need no running model: [RecordedOllama](../../../../../../test/java/com/litroute/ingestion/extract/RecordedOllama.java) replays what `llama3.2:3b` really answered, saved in [src/test/resources/ollama/](../../../../../../test/resources/ollama/) under a hash of the request. A changed `prompts.txt`, slicing or default model therefore finds no recording and fails, rather than testing against a stale answer. To record again, with Ollama running (a few minutes on a CPU):

```
rm src/test/resources/ollama/*.json
mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.litroute.ingestion.extract.TitleRecorder
```
