# The `enrich` module (M3)

**In one sentence:** it takes a paper as M2 parsed it out of an alert email and fills in what the email didn't say (DOI, venue, year, authors, abstract, landing URL), using the publisher's page, Crossref and OpenAlex, and saves the paper's PDF locally when one is publicly available.

It plugs into M2 through the [PaperEnricher](../extract/PaperEnricher.java) interface (see the [extract readme](../extract/readme.md)): `new PaperExtractor(enricher)`.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | [PaperCandidate](../../model/PaperCandidate.java) + [LinkKind](../extract/LinkKind.java) | The candidate exactly as M2 parsed it from the email, and whether its link was `READABLE` (direct or decoded), `OPAQUE` (tracking token) or `NONE`. |
| **Output** | `PaperCandidate` | The same paper with empty fields filled: `doi`, `authors` (only if the email had none), `venue`, `year`, `url`, `abstractSnippet`, `pdfPath`. `title`, `articleType` and `sourceMessageId` are never changed. |
| **Side effects** | Files and a map | A PDF written to the PDF folder (default `~/.litroute/data/pdfs`, from [PdfDownloader.defaultDirectory()](PdfDownloader.java#L74)). With `CachingPaperEnricher`, entries added to the M7c lookup-cache map. |

**The email always wins.** A lookup only fills fields the email left empty. When sources disagree, the order of precedence is: email → publisher page → Crossref → OpenAlex.

It never throws for ordinary failures (offline, timeouts, 403s, 404s, no match). If everything is unreachable, the candidate comes back exactly as it went in.

## How it works

```
PaperCandidate (from M2) + LinkKind
   │
   ├─ READABLE + url? ──▶ ArticlePageReader     publisher page → citation_* meta tags
   │                      (OPAQUE/NONE: the publisher is never contacted)
   │
   ▼  CompositeMetadataLookup  PaperQuery(title, authors, DOI if known)
   │     ├─ CrossrefSource       by DOI, else title search (≥ 0.88 similarity)
   │     └─ OpenAlexSource       by DOI (often found by Crossref), else title search
   │
   ▼  merge: page wins over APIs; PDF URLs from all sources, in order, de-duplicated
   │
   ▼  PdfDownloader            first URL that really serves a PDF → <doi>.pdf
   ▼
PaperCandidate (gaps filled, pdfPath set)
```

### [OnlinePaperEnricher](OnlinePaperEnricher.java): the orchestrator

[enrich()](OnlinePaperEnricher.java#L78) runs these steps:

1. **Publisher page.** Read only when the link is `READABLE`. An `OPAQUE` link is useless, so that paper is identified by title through the APIs alone, and the email's tracking links are never followed.
2. **Known DOI.** The email's DOI, otherwise the one on the publisher page.
3. **API lookup.** `CompositeMetadataLookup.find(...)` with the title, authors and the known DOI (if any).
4. **Merge.** `page.mergedWith(apis)`: the page wins field by field, and the APIs fill gaps and add PDF locations.
5. **PDF.** If the candidate has no PDF yet, the downloader tries the PDF URLs in order: the publisher page's own link first, then Crossref's, then OpenAlex's open-access copies.
6. **Build the result.** Email fields are kept and empty ones are filled. `url` falls back to the landing page URL, then to `https://doi.org/<doi>`.

[OnlinePaperEnricher.create(pdfDirectory)](OnlinePaperEnricher.java#L63) wires up the real network. It uses one `JdkHttpFetcher` shared by the page reader, Crossref, OpenAlex and the downloader.

### [CachingPaperEnricher](CachingPaperEnricher.java): the M7c lookup cache in front

This class wraps another enricher with M7c's live lookup-cache map, keyed by [Titles.compact(title)](../../util/Titles.java#L42) (lowercase letters and digits only):

- **Cache hit**, when the cached PDF (if any) is still on disk: the paper is answered with **no network access**. The new email still wins, and the cached data only fills its gaps.
- **Stale entry**: if the cached PDF has been deleted, the paper is looked up again.
- **Cache miss**: the delegate is called. **Only results that have a DOI are cached.** A lookup that found nothing (usually an outage) is not remembered, otherwise that outage would stick for good.
- **Empty key**: a title with no Latin letters or digits bypasses the cache, so it can't collide with every other such title.

`AlertBatchProcessor` hands it the map and saves it with the batch:

```java
lookups -> new PaperExtractor(new CachingPaperEnricher(OnlinePaperEnricher.create(pdfDir), lookups))
```

### Metadata sources ([MetadataSource](MetadataSource.java))

Every source follows the same contract:

- **Match:** the paper's [PaperMetadata](PaperMetadata.java).
- **Empty:** the source was reachable but has no confident match.
- **`IOException`:** the source is unreachable or failing (offline, timeout, HTTP 429/5xx), so the caller can fall back to another source.

[ApiJson](ApiJson.java) is the shared JSON plumbing:

- A 404 or other 4xx means "no such record" (empty).
- A 429 or 5xx throws.
- **Preprints:** a journal article and its preprint (SSRN, Research Square) share a title, and Crossref may list the preprint first. Among title-search hits whose titles match, [CrossrefSource](CrossrefSource.java) prefers one in the journal the alert names, then any published version over a preprint (`type: posted-content`). So "Drought-flood coexistence…" gets its International Journal of Disaster Risk Reduction DOI, not `10.2139/ssrn.4681645`.
- **Truncated abstracts:** some alerts cut the abstract off after a dozen words (Wiley's "Recommended articles": "…ground motion recordings are"). When a looked-up abstract begins with the alert's text and goes on, it replaces it ([OnlinePaperEnricher.completed](OnlinePaperEnricher.java)); otherwise the alert's abstract is kept.
- **Rate limits:** the live fetcher is a [PacedFetcher](PacedFetcher.java). It starts Crossref requests at least 250 ms apart (Crossref's public pool allows 5 a second, one at a time) and OpenAlex requests 125 ms apart (10 a second). A 429 asking for a wait of up to 5 s (or stating none, taken as 1 s) is waited out and retried once. A longer wait, such as OpenAlex's spent daily budget (hours), is returned at once as an outage.
- Bodies are capped at 4 MiB.
- HTML in API text (`&amp;`, `<i>`, `<sub>`) is turned into plain text, and a leading "Abstract" heading is stripped.

| Source | By DOI | Without a DOI | What it adds |
|---|---|---|---|
| [CrossrefSource](CrossrefSource.java) | `/works/<doi>`. If Crossref doesn't know the DOI, it falls through to a title search. | `query.bibliographic=<title>&rows=5`. The best hit is accepted only if its title similarity is at least **0.88** ([ACCEPT_THRESHOLD](MetadataSource.java#L10)). | DOI, title, authors (given + family), journal, year, the deposited abstract, and PDF links whose content type says PDF. |
| [OpenAlexSource](OpenAlexSource.java) | `/works/https://doi.org/<doi>`. The DOI comes back bare. | `search=<title>&per-page=5`, with the same 0.88 threshold. | Open-access PDF locations (`best_oa_location`, `primary_location`, `locations`), which Crossref usually lacks. The abstract is rebuilt from OpenAlex's inverted index. |

Titles are compared with [Titles.similarity](../../util/Titles.java#L26): edit distance on the compacted titles. Publisher quirks like missing spaces, dash styles, quote styles and accents don't count against a match, but an unrelated paper scores low.

### [CompositeMetadataLookup](CompositeMetadataLookup.java): asking both APIs

- Sources are asked in order (Crossref, then OpenAlex). **Earlier sources win field by field.** Later ones fill gaps and add PDF URLs, de-duplicated.
- If Crossref finds the DOI from the title, OpenAlex is then asked **by that DOI** (an exact lookup) instead of searching by title again.
- A failing source never fails the lookup, because the other source still answers.
- After **3 failures in a row** a source is skipped for the rest of the run, so an outage costs a few timeouts rather than one per paper. A success resets the count, so occasional blips never disable a source.

### [ArticlePageReader](ArticlePageReader.java): the publisher's own page

This is a plain public GET, like a browser without a login, and it reads at most 3 MiB. It extracts the Highwire/Google Scholar `citation_*` meta tags:

| Field | Meta tags read |
|---|---|
| DOI | `citation_doi`, `dc.identifier`, `prism.doi` |
| Authors | `citation_author`, kept exactly as the page writes them (often "Family, Given") |
| Venue | `citation_journal_title`, `citation_conference_title` |
| Year | `citation_publication_date` and similar |
| PDF | `citation_pdf_url`, plus `<link rel=alternate type=application/pdf>` |

If the URL itself serves a PDF, that URL is reported as the PDF location. A 403 (robot block), a login redirect, an unreachable host or a malformed URL all give empty: "nothing learned here", not an error. Nothing tries to get around a paywall.

### [PdfDownloader](PdfDownloader.java): saving the PDF

- **Reuse first.** If `<stem>.pdf` already exists and really is a PDF, it is reused with no download, so re-runs don't fetch twice.
- **Try URLs in order.** Only http(s) URLs are tried. A response is accepted only if it is a 2xx, wasn't truncated (cap is 80 MiB), and **contains `%PDF-` in its first 1 KiB**. A paywall or login page that answers 200 with HTML is rejected, and the next URL is tried.
- **Atomic write.** The file is written atomically ([AtomicFiles](../../util/AtomicFiles.java)), so a failed download never leaves a partial or fake file behind.
- **File name** ([fileStem](PdfDownloader.java#L143)): the DOI with every character outside `[A-Za-z0-9._-]` replaced by `_`, so a DOI from an email can't steer the write out of the folder. Without a DOI, the name is `title-<16 hex chars of SHA-256 of the compacted title>`.

### The HTTP layer

- [HttpFetcher](HttpFetcher.java) is **the only door to the network** in M2/M3. That is what lets tests swap in recorded responses.
- An HTTP error status is returned as an [HttpReply](HttpReply.java) (status, content type, body, final URI after redirects, truncated flag), not thrown. Only an unreachable host or a timeout throws.
- [JdkHttpFetcher](JdkHttpFetcher.java) is the real implementation:
  - http/https only;
  - follows redirects, but never from https down to http;
  - 8 s connect timeout and 30 s request timeout;
  - body-size cap;
  - User-Agent `LitRoute/1.0 (personal research-paper alert reader)`.

## Interaction with M7 (the stores)

M3 writes no store file itself, but two of its outputs end up in M7:

- **M7c lookup cache.** [CachingPaperEnricher](CachingPaperEnricher.java) is handed M7c's live `lookups` map (keyed by `Titles.compact(title)`). A hit is answered from it with no network access, unless its PDF has since been deleted; a miss goes to OnlinePaperEnricher, and the result is added to the map **only if it found a DOI**, so an API outage is never cached. [AlertBatchProcessor](../extract/AlertBatchProcessor.java) saves the map with the batch's papers.
- **PDFs for M7c and M7e.** [PdfDownloader](PdfDownloader.java) saves into `~/.litroute/data/pdfs/` (next to the stores) and the candidate's `pdfPath` points there; M7c stores that path. When M6 matches the paper, M7e copies the file into `matched/<student>/`. A re-run reuses a PDF already in `pdfs/`, and Clear and re-run leaves that folder alone.

```
M7c lookups ──▶ CachingPaperEnricher ──hit──▶ cached candidate (email fields win)
                     │ miss
                     ▼
               OnlinePaperEnricher ──▶ page / Crossref / OpenAlex ──▶ PdfDownloader ──▶ data/pdfs/<doi>.pdf
                     │ DOI found?
                     └──yes──▶ lookups.put(key, enriched)  ──(saved with the batch)──▶ M7c
```

## How it's tested

**No test touches the network, and none uses hand-made API responses.** Every test goes through [RecordedHttp](../../../../../../test/java/com/litroute/ingestion/enrich/RecordedHttp.java), an `HttpFetcher` that replays the real replies Crossref, OpenAlex and the publishers gave when the live pipeline was run once over the six sample mails.

The recording lives in [src/test/resources/http/](../../../../../../test/resources/http/). It holds 97 responses: 70 × 200 and 27 × 403, the 403s being real robot blocks from ascelibrary.org and sciencedirect.com.

- **Replay is by exact URL.** If the code asks for a URL that was never recorded, the test fails with an `AssertionError` instead of quietly getting a miss.
- **Bodies are shortened only where it doesn't matter:**
  - article pages are cut after `</head>` (only meta tags are read);
  - error bodies are stored empty;
  - PDFs keep their first 8 KiB.

  JSON is stored exactly as received. Details are in the [recording README](../../../../../../test/resources/http/README.md).
- **Failures are simulated at the network layer**, so the real source code meets a real `IOException`:
  - `offlineFor(fragment)` makes matching URLs unreachable (`offlineFor("")` takes the whole network down);
  - `flakyFor(fragment, true, true, false, …)` makes the n-th matching request time out.
- **Requests are tracked.** `requests`, `count(fragment)` and `requested(fragment)` let a test assert what was contacted, and what never was.

| Test class | What it checks |
|---|---|
| [CrossrefSourceTest](../../../../../../test/java/com/litroute/ingestion/enrich/CrossrefSourceTest.java) | A DOI lookup returns the exact work: given-first author names, venue, year, `https://doi.org/` landing URL, and the JATS abstract as plain text. A title-only search finds the work. A search for a title that exists nowhere (Crossref still returns 5 unrelated works) yields **nothing**. An unreachable API throws `IOException`. |
| [OpenAlexSourceTest](../../../../../../test/java/com/litroute/ingestion/enrich/OpenAlexSourceTest.java) | A DOI lookup returns a bare DOI, OpenAlex's own author spelling ("Luís Silva") and the abstract rebuilt from the inverted index. The open-access PDF URL is reported, and a location with no `pdf_url` doesn't become an empty entry. An unreachable API throws. |
| [CompositeMetadataLookupTest](../../../../../../test/java/com/litroute/ingestion/enrich/CompositeMetadataLookupTest.java) | Crossref wins field by field (its "Luis Silva" over OpenAlex's "Luís Silva"). A PDF URL listed twice comes out once. A DOI found by Crossref is used to query OpenAlex exactly, with no OpenAlex title search. Either API down: the other still answers. Both down: empty, not an exception. 3 failures in a row: the source is skipped for the rest of the run while the healthy one is still asked. Fail, fail, succeed blips never disable a source. |
| [ArticlePageReaderTest](../../../../../../test/java/com/litroute/ingestion/enrich/ArticlePageReaderTest.java) | SpringerOpen's real page yields DOI, title, venue, year (from "2025/12") and PDF URL. Authors are kept as the page states them ("Najar, Imtiyaz Akbar"). ASCE's 403, an unreachable host and a malformed URL all give empty without throwing. |
| [PdfDownloaderTest](../../../../../../test/java/com/litroute/ingestion/enrich/PdfDownloaderTest.java) | A real PDF is saved under its DOI name and starts with `%PDF-`. An HTML page that answers 200 is rejected and the next URL is tried. When nothing serves a PDF, no file is left behind. An unreachable host is just another failed URL. A PDF already on disk is reused with zero requests. `fileStem`: `10.1061/../../etc/passwd` can't escape the folder, and title hashes are stable across case and punctuation. |
| [OnlinePaperEnricherTest](../../../../../../test/java/com/litroute/ingestion/enrich/OnlinePaperEnricherTest.java) | A readable link fills DOI, venue, year and the PDF. An opaque link is identified by title alone, and the publisher site is **never** requested. A missing abstract is filled from the APIs. **Email fields are never overwritten**: authors, venue, year, URL and abstract stay, and only the DOI is added. Everything offline: the candidate is returned **unchanged**. ASCE blocking robots: the APIs still give DOI and venue, with no PDF. The same paper twice: no second PDF download. |
| [CachingPaperEnricherTest](../../../../../../test/java/com/litroute/ingestion/enrich/CachingPaperEnricherTest.java) | A repeat paper is answered with **zero requests**, even when its title differs in case or punctuation. On a hit the new email still wins and only the DOI comes from the cache. A lookup that found nothing is **not** cached. An entry whose PDF was deleted is looked up again and the PDF re-saved. |
| [PaperExtractorPipelineTest](../../../../../../test/java/com/litroute/ingestion/enrich/PaperExtractorPipelineTest.java) | **M2 + M3 end to end** over all six real mails. Each mail yields exactly the papers it lists, and **all 31 papers end up with a DOI**. Opaque-only SpringerOpen is resolved by title, without contacting `email.springeropen.com`. The Scholar redirect is unwrapped. T&F gains the year it never stated. ScienceDirect resolves both its decodable and its encrypted links, without contacting the tracker. ASCE: 22 distinct DOIs. Only the two SpringerOpen papers get a PDF, since the rest are paywalled or bot-blocked. With the network gone, the result equals the parse-only output exactly. |

Persisting the lookup cache across runs (through `AlertBatchProcessor` and M7c) is tested in the `extract` module's `AlertBatchProcessorTest`.

**Deliberately untested** (each is explained in a comment in its test class): OpenAlex's title-search branch and `CachingPaperEnricher`'s empty-key guard. Neither is reachable with the six real sample mails, so testing them would mean inventing data.
