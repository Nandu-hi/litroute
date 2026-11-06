# `enrich` (M3): fill in what the email didn't say

M3 takes a paper as M2 parsed it, plus its [LinkKind](../extract/LinkKind.java). It fills empty fields (`doi`, `authors` if the email had none, `venue`, `year`, `url`, `abstractSnippet`) and saves the PDF to `~/.litroute/data/pdfs/` when a public copy exists. `title`, `articleType` and `sourceMessageId` never change.

- **The email wins.** Precedence: email → publisher page → Crossref → OpenAlex. The one exception: a looked-up abstract that *continues* an abstract the alert cut short replaces it.
- **Never throws on ordinary failures** (offline, 403, 404, no match). With everything down, the paper comes back unchanged.

```
READABLE link? ─▶ ArticlePageReader (citation_* meta tags; OPAQUE links are never followed)
─▶ CompositeMetadataLookup: Crossref (by DOI, else title ≥ 0.88), then OpenAlex (by Crossref's DOI, else title)
─▶ merge (page wins) ─▶ PdfDownloader (first URL that really serves a PDF)
```

## Classes

| Class | Role |
|---|---|
| [OnlinePaperEnricher](OnlinePaperEnricher.java) | The steps above. `create(pdfDir)` wires the real network. |
| [CachingPaperEnricher](CachingPaperEnricher.java) | The M7c lookup cache in front, keyed by `Titles.compact(title)`. A hit makes no network call, unless its PDF has since been deleted. **Only results with a DOI are cached**, so an outage is never remembered. |
| [CrossrefSource](CrossrefSource.java) | `/works/<doi>` or `query.bibliographic`. Among matching hits, it prefers the alert's journal, then a published version over a preprint (SSRN, Research Square). |
| [OpenAlexSource](OpenAlexSource.java) | Mainly for open-access PDF locations. Rebuilds the abstract from the inverted index. |
| [CompositeMetadataLookup](CompositeMetadataLookup.java) | Earlier sources win field by field, and PDF URLs are pooled. A source is skipped for the rest of the run after **3 failures in a row**; any success resets the count. |
| [ArticlePageReader](ArticlePageReader.java) | A public GET (≤ 3 MiB) of the publisher's page, reading its DOI, authors, venue, year and PDF link. A 403 or login redirect means "nothing learned". It never tries to get around a paywall. |
| [PdfDownloader](PdfDownloader.java) | Reuses an existing `<doi>.pdf`. A response is accepted only if it is a 2xx, not truncated (≤ 80 MiB), and has `%PDF-` in its first 1 KiB. Atomic write. The file name is the DOI sanitised to `[A-Za-z0-9._-]`, else `title-<hash>`. |
| [PacedFetcher](PacedFetcher.java) | Spaces requests: Crossref ≥ 250 ms apart, OpenAlex ≥ 125 ms. A 429 with a wait of ≤ 5 s is retried once; a longer wait is treated as an outage. |
| [HttpFetcher](HttpFetcher.java) / [JdkHttpFetcher](JdkHttpFetcher.java) / [HttpReply](HttpReply.java) | The only way to the network: http(s) only, no https→http redirect, 8 s connect / 30 s request timeouts. An error status is returned, not thrown. |
| [MetadataSource](MetadataSource.java), [PaperMetadata](PaperMetadata.java), [PaperQuery](PaperQuery.java), [ApiJson](ApiJson.java) | The source contract (a match, empty, or `IOException` when the source is down), with shared JSON handling: 4xx = no record, 429/5xx = down, bodies capped at 4 MiB, HTML converted to plain text |

## Tests

No test touches the network, and no API response is hand-made. [RecordedHttp](../../../../../../test/java/com/litroute/ingestion/enrich/RecordedHttp.java) replays the 97 real replies captured from a live run over the six sample mails ([recordings](../../../../../../test/resources/http/README.md)), by exact URL: a URL that was never recorded fails the test. `offlineFor` and `flakyFor` simulate outages.

There is one test class per source. Beyond those, the tests check that the email always wins, that opaque links never reach the publisher, that outages aren't cached, that a PDF is never downloaded twice, and that sanitised file names can't escape the folder. [PaperExtractorPipelineTest](../../../../../../test/java/com/litroute/ingestion/enrich/PaperExtractorPipelineTest.java) runs M2 + M3 end to end: all 31 papers get a DOI, and with the network down the result equals the parse-only output.

Two paths are deliberately untested: OpenAlex's title search and the cache's empty-key guard. The real samples never reach either, so testing them would mean inventing data.
