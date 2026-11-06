# `ingestion` (M1–M4): inbox → deduplicated papers

| Stage | Package | Does |
|---|---|---|
| M1 | [email](email/readme.md) | Fetch unread alerts not yet in M7a |
| M2 | [extract](extract/readme.md) | Read papers out of each alert; run the batch |
| M3 | [enrich](enrich/readme.md) | Fill in DOI, venue, year, abstract; download the PDF |
| M4 | [dedup](dedup/readme.md) | Merge copies of one paper |

```
M1 fetch ─▶ AlertBatchProcessor: load M7c + M7a ─▶ per email: M2 extract ─▶ M3 enrich
         ─▶ M4 dedup (held + batch) ─▶ save M7c ─▶ save M7a ─▶ M1 markRead
```

- **Fails softly:** Gmail unreachable → no emails; a bad email is skipped; a failed lookup keeps the paper as parsed. Only a store error (or, in Local AI mode, an unreachable Ollama) stops the batch, and then nothing is recorded.
- **Crash-safe:** M7a is saved last, so a crash re-runs the whole batch, and its papers replace the earlier copies.
