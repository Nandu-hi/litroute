# `extract` (M2): alert email → papers

[PaperExtractor.extract(RawEmail)](PaperExtractor.java) finds every paper an alert lists, drops notes, enriches each paper (M3), and returns `List<PaperCandidate>` in email order. Only the HTML body is used. Ordinary failures never throw: if enrichment fails, the paper is kept as parsed. The **Read emails with** toggle (`--extraction=pattern|llm`, [ExtractionMode](ExtractionMode.java)) picks how papers are found:

```
htmlBody ─AlertLines─▶ visual lines ─AlertHtmlParser─▶ paper blocks (title, authors, venue, year, type, abstract, links)
   PATTERN: the research papers among the blocks
   LLM:     AiTitleExtractor ─prompts.txt─▶ Ollama ─▶ {"titles":[…]}, each checked against the email
─▶ PaperEnricher (M3) ─▶ List<PaperCandidate>
```

## Pattern matching ([AlertHtmlParser](AlertHtmlParser.java))

There is one visual rule for every publisher rather than a rule set per publisher.

- **Title:** a line that is nothing but one http(s) link of at least 3 words, and isn't furniture ("Unsubscribe", "Full Text"), a Maps address link, the journal's own name, or a linked author. A leading `[PDF]` is stripped.
- **Block:** the lines up to the next title, heading or rule (12 lines at most). A title becomes a paper **only if its block has an author line or a venue line**.
- **Fields:**
  - Authors: [AuthorNames](AuthorNames.java) rejects the whole line unless every piece is a name.
  - Venue and year: a line with a year or volume number; copyright lines and issue headers don't count.
  - Scholar's "Authors – Venue, 2026" line is split into both.
  - Abstract: the first line of 10+ words.
  - A table of contents's issue header fills a missing venue and year.
- **Article type:** a section label ("Technical Papers", "Case Studies") heads every paper under it.
- **Notes are dropped:** book reviews, discussions, closures, errata, editorials and special-issue introductions, by label or by title ([PaperCandidate.isResearch](../../model/PaperCandidate.java)). They are never enriched or matched. ASCE's 22-entry table of contents gives 17 papers.
- **Links** ([LinkUnwrapper](LinkUnwrapper.java), offline): `READABLE` (a direct URL or a decodable wrapper, nested up to 4 deep), `OPAQUE` (a tracking token, whose URL is dropped), `NONE`. The DOI is looked for in the resolved URL, then the raw hrefs, then the text.
- Several links to one paper produce a single entry.

## Local AI model ([AiTitleExtractor](AiTitleExtractor.java))

`llama3.2:3b` on Ollama names the titles. Pattern matching still runs underneath to supply each paper's details and to recognize notes:

| The model names a title that… | Outcome |
|---|---|
| pattern matching read as a paper | kept, with the parsed details |
| pattern matching read as a note, or is the journal name | dropped |
| pattern matching missed | kept only if M3 finds a DOI for it |
| isn't in the email | refused |

The model's output is checked at every step:

- **Schema-constrained:** `{"titles":[string]}`, temperature 0, fixed seed. A malformed answer is retried once with another seed, then the email falls back to pattern matching.
- **Verbatim match:** a title must equal a whole link or text run in the email, and the email's own text is kept.
- **Must read as a title:** names, footers, labels and copyright lines are refused.
- **Sliced input:** the email is sent as simplified XML ([AlertLines.toXml](AlertLines.java); ASCE goes from 174 KB to 6 KB) in ~3,200-character slices that overlap by 2 lines. An answer cut off at the token budget is re-asked in two halves.
- **Unreachable Ollama:** the batch fails before anything is saved.

On the six alerts it finds 25 of 26 papers as received; pattern matching finds 26, which is why pattern matching is the default. Speed on a CPU is 5–10 s per one-paper alert and about 100 s for ASCE. [OllamaClient](OllamaClient.java) refuses any `LITROUTE_OLLAMA_URL` that isn't on this machine. The prompt is [prompts.txt](../../../../../resources/prompts.txt); [PromptGenerator](PromptGenerator.java) requires exactly one `{body}` in it.

## [AlertBatchProcessor](AlertBatchProcessor.java): M2–M4 for a batch

`process(emails)` loads M7c and M7a, extracts and enriches everything in memory, deduplicates the result with the held papers (dropping earlier copies of re-run emails first), saves M7c in one write, then saves M7a **last**. `forgetAll()` empties M7c, then M7a; Clear and re-run calls it.

## Classes

[PaperExtractor](PaperExtractor.java) (entry point), [AlertHtmlParser](AlertHtmlParser.java), [AlertLines](AlertLines.java), [AuthorNames](AuthorNames.java), [LinkUnwrapper](LinkUnwrapper.java) / [LinkKind](LinkKind.java), [ParsedPaper](ParsedPaper.java), [AiTitleExtractor](AiTitleExtractor.java), [OllamaClient](OllamaClient.java), [PromptGenerator](PromptGenerator.java), [ExtractionMode](ExtractionMode.java), [PaperEnricher](PaperEnricher.java) (`NONE` = offline; M3 provides the real ones), [AlertBatchProcessor](AlertBatchProcessor.java).

## Tests

Every test uses the six real alerts the client forwarded ([samples/](../../../../../../test/resources/samples/)), plus [samples/received/](../../../../../../test/resources/samples/received/), the same alerts exactly as they reached the inbox. HTTP traffic is replayed from recordings ([RecordedHttp](../../../../../../test/java/com/litroute/ingestion/enrich/RecordedHttp.java)), and so are the model's answers ([RecordedOllama](../../../../../../test/java/com/litroute/ingestion/extract/RecordedOllama.java)), so no network or running model is needed.

| Test | Checks |
|---|---|
| PaperExtractorTest, ReceivedAlertsTest | Exact papers and fields per mail (1, 1, 1, 1, 5, 17); no furniture becomes a paper; the inbox copies parse like the tidied ones |
| AuthorNamesTest, LinkUnwrapperTest | Name parsing; link classification |
| AlertBatchProcessorTest | Write order, merging across runs, crash re-runs, the lookup cache reused with zero requests |
| AiTitleExtractorTest, OllamaClientTest, PromptGeneratorTest | The model's refusals, retries and slicing; the request shape; local-only URLs |

### Recording the test answers

The recorded answers are keyed by a hash of each request, so after you change `prompts.txt`, the slicing or the model, record them again (with Ollama running):

```
rm src/test/resources/ollama/*.json
mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.litroute.ingestion.extract.TitleRecorder
```
