# `extract` (M2): alert email → papers

[PaperExtractor.extract(RawEmail)](PaperExtractor.java) finds every paper an alert lists, drops notes, enriches each (M3) and returns them in email order. Only the HTML body is used. **Read emails with** ([ExtractionMode](ExtractionMode.java)) picks how papers are found:

```
html ─AlertLines─▶ lines ─AlertHtmlParser─▶ blocks (title, authors, venue, year, type, abstract, links)
   PATTERN: the research papers among the blocks
   LLM:     Ollama names the titles; each is checked against the email
─▶ PaperEnricher (M3) ─▶ List<PaperCandidate>
```

## Pattern matching ([AlertHtmlParser](AlertHtmlParser.java))

One visual rule for every publisher:
- **Title:** a line that is one link of ≥ 3 words, and not furniture, the journal name or an author.
- **Paper:** a title whose block (up to the next title, ≤ 12 lines) has an author or venue line.
- **Fields:** authors ([AuthorNames](AuthorNames.java)), venue/year, Scholar's "Authors – Venue, 2026", abstract (first line of 10+ words), issue header.
- **Notes dropped:** book reviews, discussions, errata, editorials ([PaperCandidate.isResearch](../../model/PaperCandidate.java)).
- **Links** ([LinkUnwrapper](LinkUnwrapper.java), offline): `READABLE`, `OPAQUE` (tracking token, URL dropped), `NONE`.

## Local AI model ([AiTitleExtractor](AiTitleExtractor.java))

`llama3.2:3b` names the titles; pattern matching still supplies the details. A named title is kept if parsed as a paper, dropped if parsed as a note, kept only with a DOI from M3 if pattern matching missed it, and refused if it isn't verbatim in the email. The output is schema-constrained (temperature 0), retried once, and falls back to pattern matching. The email is sent as compact XML in overlapping slices. [OllamaClient](OllamaClient.java) accepts local addresses only. The prompt is [prompts.txt](../../../../../resources/prompts.txt) with one `{body}` ([PromptGenerator](PromptGenerator.java)).

## [AlertBatchProcessor](AlertBatchProcessor.java)

`process(emails)` runs M2–M4 in memory, then saves M7c and only then M7a. `forgetAll()` empties M7c, then M7a.

## Tests

Use the six real alerts ([samples/](../../../../../../test/resources/samples/)) with recorded HTTP and Ollama replies: no network, no running model. PaperExtractorTest / ReceivedAlertsTest check the exact papers per mail (1, 1, 1, 1, 5, 17); AlertBatchProcessorTest checks write order and crash re-runs; AiTitleExtractorTest checks refusals, retries and slicing.

### Recording the test answers

The recorded model answers are keyed by request hash. After changing `prompts.txt`, the slicing or the model, re-record them with Ollama running:

```
rm src/test/resources/ollama/*.json
mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.litroute.ingestion.extract.TitleRecorder
```
