# `ingestion` (M1–M4): inbox → deduplicated papers

| Stage | Package | Does |
|---|---|---|
| M1 | [email](email/readme.md) | Fetch the unread alerts not yet in M7a |
| M2 | [extract](extract/readme.md) | Read the papers out of each alert; drop notes; run the batch |
| M3 | [enrich](enrich/readme.md) | Fill in DOI, venue, year and abstract; download the PDF |
| M4 | [dedup](dedup/readme.md) | Merge copies of one paper |

```
M1 fetchNewAlerts(M7a IDs) ─▶ AlertBatchProcessor.process(emails)
      load M7c + M7a ─▶ per email: M2 extract ─▶ M3 enrich (M7c lookup cache first)
      ─▶ M4 deduplicate(held papers + batch) ─▶ save M7c ─▶ save M7a ─▶ M1 markRead
```

- **Fails softly:** if Gmail is unreachable there are no emails; a bad email is skipped; a failed lookup leaves the paper as parsed. Only a store error stops the batch, and then nothing is recorded as processed.
- **Crash-safe order:** if the run dies anywhere before M7a is saved, the whole batch runs again next time, and its papers replace the earlier copies.
- In **Local AI** mode, an unreachable Ollama fails the batch before anything is saved.
