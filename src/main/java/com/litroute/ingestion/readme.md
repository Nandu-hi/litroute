# The `ingestion` modules (M1–M4)

**In one sentence:** everything between the professor's Gmail inbox and the deduplicated list of papers M6 scores: fetch the alert emails (M1), read the papers out of them (M2), fill in what the emails don't say and download the PDFs (M3), and merge copies of the same paper (M4).

This folder holds no classes of its own; each stage is a subpackage with its own readme:

| Subpackage | Stage | Readme | Main classes |
|---|---|---|---|
| [email](email/) | **M1** fetch | [email/readme.md](email/readme.md) | [EmailSource](email/EmailSource.java), [GmailEmailSource](email/GmailEmailSource.java), [AlertClassifier](email/AlertClassifier.java) |
| [extract](extract/) | **M2** parse (+ the batch wrapper) | [extract/readme.md](extract/readme.md) | [PaperExtractor](extract/PaperExtractor.java), [AlertHtmlParser](extract/AlertHtmlParser.java), [AlertBatchProcessor](extract/AlertBatchProcessor.java) |
| [enrich](enrich/) | **M3** enrich | [enrich/readme.md](enrich/readme.md) | [OnlinePaperEnricher](enrich/OnlinePaperEnricher.java), [CachingPaperEnricher](enrich/CachingPaperEnricher.java), [CrossrefSource](enrich/CrossrefSource.java), [OpenAlexSource](enrich/OpenAlexSource.java), [PdfDownloader](enrich/PdfDownloader.java) |
| [dedup](dedup/) | **M4** deduplicate | [dedup/readme.md](dedup/readme.md) | [Deduplicator](dedup/Deduplicator.java) |

## How data flows through ingestion

```
Gmail INBOX (unread)
   │  M1 GmailEmailSource.fetchNewAlerts(credentials, M7a IDs)   newest maxUnread, alerts only
   ▼
List<RawEmail>
   │  AlertBatchProcessor.process(emails)
   │     load M7c (papers + lookup cache) and M7a
   │     for each email:
   │        M2 PaperExtractor.extract
   │             AlertLines ─▶ AlertHtmlParser (+ AuthorNames, LinkUnwrapper) ─▶ ParsedPaper(candidate, LinkKind)
   │             notes (book reviews, errata, ...) dropped
   │        M3 CachingPaperEnricher (M7c lookups) ─▶ OnlinePaperEnricher
   │             ArticlePageReader (READABLE links only) + CompositeMetadataLookup(Crossref, OpenAlex)
   │             PdfDownloader ─▶ ~/.litroute/data/pdfs/<doi>.pdf
   │     M4 Deduplicator.deduplicate(held papers + this batch)
   │     save M7c, then M7a
   ▼
List<PaperCandidate> (deduplicated, everything M7c holds) ──▶ M9 ──▶ M6
   │
   └─ M1 GmailEmailSource.markRead(this batch's IDs)   only after M7a holds them
```

Every stage fails softly: an unreachable Gmail gives no emails, an unreadable email is skipped, an enrichment that throws keeps the paper as parsed, an offline API leaves fields empty. Only a store that can't be read or written stops a batch, and then nothing is marked processed.

## Interaction with M7 (the stores)

| Store | Ingestion's use |
|---|---|
| **M7a** `email_cache.json` | M1 skips the IDs in it; AlertBatchProcessor adds the batch's IDs **last**; M1 marks read (and, on reset, unread) only IDs it holds. |
| **M7c** `enrichment_cache.json` | AlertBatchProcessor reads the held papers and the lookup cache, CachingPaperEnricher reads and fills the cache in memory, and the deduplicated papers and the cache are saved together in one write. |
| **M7d**, **M7e** | Not used by ingestion. M7e later copies the PDFs M3 downloaded into the students' folders. |

The order (load both → work in memory → save M7c → save M7a → mark read) means a crash anywhere repeats the batch on the next run instead of losing it, and a re-run replaces the batch's earlier papers instead of duplicating them.
