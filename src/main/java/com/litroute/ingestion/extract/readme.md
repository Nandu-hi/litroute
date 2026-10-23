# The `extract` module (M2)

**In one sentence:** it takes one alert email (SpringerOpen, Taylor & Francis, Google Scholar, ScienceDirect, ASCE…), finds every paper listed in its HTML, and turns each one into a `PaperCandidate`.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | [RawEmail](../../model/RawEmail.java) (from M1) | `messageId`, subject, sender, received time, `htmlBody`, `plainTextBody`. Only `htmlBody` is used. With no HTML body, the result is an empty list. |
| **Output** | `List<PaperCandidate>` ([PaperCandidate](../../model/PaperCandidate.java)) | Zero or more papers in the order they appear in the email. Each has `title` (required), `authors`, `venue`, `year`, `articleType`, `doi`, `url`, `abstractSnippet`, `pdfPath` and `sourceMessageId`. Any field the email doesn't state stays `Optional.empty()`. |

You call it through [PaperExtractor.extract(RawEmail)](PaperExtractor.java#L41). For ordinary failures it never throws: if enrichment fails, the paper is kept exactly as parsed, and an email with no papers gives `[]`.

## How it works (two stages)

```
RawEmail.htmlBody
   │
   ▼  AlertLines        HTML → ordered visual "lines" (jsoup); links keep hrefs, images,
   │                    hidden elements and the Gmail forward header/signature are dropped
   ▼  AlertHtmlParser   find paper blocks → title, authors, venue, year, type, abstract
   │     ├─ AuthorNames     strict author-line parser
   │     └─ LinkUnwrapper   classify/unwrap each link → READABLE / OPAQUE / NONE
   ▼  ParsedPaper(candidate, linkKind)
   │
   ▼  PaperEnricher     (stage 2, pluggable) fills DOI/metadata/PDF; NONE = no-op
   ▼
List<PaperCandidate>
```

### Stage 1: offline parse ([AlertHtmlParser](AlertHtmlParser.java))

The parser doesn't have a separate rule set for each publisher. It relies on one visual pattern that every alert shares:

1. **Flatten to lines.** [AlertLines](AlertLines.java) starts a new line at every block element and `<br>`. Each line remembers whether it is a heading or an `<hr>` rule.
2. **Title line.** A line counts as a title when it is nothing but one http(s) link, has at least 3 words, and isn't "furniture" text like "Unsubscribe", "Read article" or "Full Text". A tag such as `[PDF]` at the start of the title is stripped, whether it is inside the link or beside it (Google Scholar puts it in a span of its own). Not titles:
   - a Google Maps link, which Gmail makes of a street address when a message is forwarded ("London, N1 9XW,");
   - a link that is the journal's own name from the issue header (ASCE's "Natural Hazards Review");
   - a linked author name right under a title (ASCE links authors to their profiles: "Michelle A. Ritchie").
3. **Block.** The lines after a title, up to the next title, heading or rule (12 lines at most), form that paper's block. **The title only becomes a paper if the block has an author list or a venue line.** This check keeps stray links out of the results.
4. **Reading the block** ([readBlock](AlertHtmlParser.java#L139)):
   - **Authors:** [AuthorNames](AuthorNames.java) splits on `, ; & and`, drops placeholders like "others" and "…", and rejects the whole line if any piece doesn't look like a name. A link line counts too when it is a list of names with lowercase letters in them, so ASCE's linked sole authors are kept and a footer menu link such as "ABOUT ASCE PUBLICATIONS" is not.
   - **Venue and year:** taken from a line containing a year or a volume number. For example, "Ocean Engineering, Volume 311, … August 2024" gives venue "Ocean Engineering" and year 2024. A copyright line ("© 2025 BioMed Central …") and the issue header ("VOLUME: 27 | …") never count, so a footer link above them is not a paper.
   - **Combined lines:** Scholar-style "Authors – Venue, 2026" lines are split into both parts.
   - **Abstract:** the first line of 10 or more words.
5. **Issue header fallback.** For table-of-contents mails (ASCE's "VOLUME: 27 | NUMBER: 4 | November 2026") or headlines like "New articles for &lt;journal&gt;", the venue and year come from the header when a block doesn't state them.
6. **Article type.** Taken from a label just before or after the title, otherwise from the nearest label above it. As in a table of contents, a section label ("Technical Papers", "Case Studies", whether a heading or a plain line) heads every paper under it until the next one. A label right above the next title belongs to that next paper, not to the one before it. A label is always a few words ending in a type word (article, papers, studies, notes, reviews, discussions, closures, corrections, …), so the email's banner ("New articles have been published") is never a type.
7. **Notes are dropped.** A book review, discussion, closure, erratum, correction, editorial and the like is not a research paper and never leaves M2 ([PaperCandidate.isResearch](../../model/PaperCandidate.java)), recognized by its section label or, for alerts without labels, by its title ("Erratum for …", "Discussion of …"). So it is never enriched, stored or matched: a book review can read exactly like a student's papers (one scored relevance 0.96). ASCE's 22-entry table of contents gives its 17 research papers.
7. **Links and DOI.** Every href in the block goes through [LinkUnwrapper](LinkUnwrapper.java), which never touches the network:
   - **READABLE:** a plain publisher URL, or a redirect wrapper whose real target can be percent-decoded (`scholar_url?url=…`, Elsevier's `/CL0/https:%2F%2F…`). Nested wrappers are unwrapped up to 4 levels deep.
   - **OPAQUE:** a click-tracking token with no recoverable target. Its URL is dropped, because it identifies nothing and carries the recipient's tracking ID.
   - **NONE:** no usable http link (`mailto:`, `cid:` and so on).

   The DOI is searched for in the resolved URL, then in the raw hrefs, then in the block text.
8. **Dedup within one mail.** If several links point to the same paper (title, "Full Text", "Read article"), it is emitted once. The match uses a normalized title.

### Stage 2: enrichment ([PaperEnricher](PaperEnricher.java))

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
| [PaperExtractorPipelineTest](../../../../../../test/java/com/litroute/ingestion/enrich/PaperExtractorPipelineTest.java) (in `enrich`) | End-to-end M2 + M3 | All six mails through the real `OnlinePaperEnricher`. Every one of the 31 papers ends up with its DOI. |

Network-dependent tests use **[RecordedHttp](../../../../../../test/java/com/litroute/ingestion/enrich/RecordedHttp.java)**. It replays the actual Crossref, OpenAlex and publisher responses, including the PDFs and the 403s, captured once from a live run into [src/test/resources/http/](../../../../../../test/resources/http/). The tests are offline and deterministic but still exercise real payloads. If code asks for a URL that wasn't recorded, the test fails instead of quietly skipping it.
