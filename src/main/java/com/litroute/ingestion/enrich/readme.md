# `enrich` (M3): fill in what the email didn't say

Fills a parsed paper's empty fields (`doi`, `authors`, `venue`, `year`, `url`, `abstractSnippet`) and saves its PDF to `~/.litroute/data/pdfs/` when a public copy exists. `title`, `articleType` and `sourceMessageId` never change.

- **The email wins:** email → publisher page → Crossref → OpenAlex. The one exception: a looked-up abstract that continues one the alert cut short replaces it.
- **Never throws** on ordinary failures (offline, 403, 404, no match); the paper comes back unchanged.

```
READABLE link? ─▶ publisher page meta tags ─▶ Crossref (DOI, else title ≥ 0.88) ─▶ OpenAlex
─▶ merge ─▶ first URL that really serves a PDF
```

| Class | Role |
|---|---|
| [OnlinePaperEnricher](OnlinePaperEnricher.java) | The steps above; `create(pdfDir)` wires the real network |
| [CachingPaperEnricher](CachingPaperEnricher.java) | M7c lookup cache in front; caches only results with a DOI, so an outage isn't remembered |
| [CrossrefSource](CrossrefSource.java), [OpenAlexSource](OpenAlexSource.java) | The APIs; Crossref prefers the alert's journal and published versions over preprints |
| [CompositeMetadataLookup](CompositeMetadataLookup.java) | Earlier sources win per field; a source is skipped after 3 failures in a row |
| [ArticlePageReader](ArticlePageReader.java) | Public GET (≤ 3 MiB) of the publisher page; never gets around a paywall |
| [PdfDownloader](PdfDownloader.java) | Accepts only a 2xx, untruncated (≤ 80 MiB) reply starting `%PDF-`; atomic write; safe file names |
| [PacedFetcher](PacedFetcher.java) | Crossref ≥ 250 ms apart, OpenAlex ≥ 125 ms; one retry on a short 429 |
| [HttpFetcher](HttpFetcher.java), [JdkHttpFetcher](JdkHttpFetcher.java), [HttpReply](HttpReply.java) | The only way to the network: http(s), no https→http, 8 s / 30 s timeouts |
| [MetadataSource](MetadataSource.java), [PaperMetadata](PaperMetadata.java), [PaperQuery](PaperQuery.java), [ApiJson](ApiJson.java) | Source contract and shared JSON handling |

**Tests:** no network. [RecordedHttp](../../../../../../test/java/com/litroute/ingestion/enrich/RecordedHttp.java) replays 97 real replies by exact URL ([recordings](../../../../../../test/resources/http/README.md)). One test class per source, plus [PaperExtractorPipelineTest](../../../../../../test/java/com/litroute/ingestion/enrich/PaperExtractorPipelineTest.java) (M2 + M3: all 31 papers get a DOI; offline equals parse-only).
