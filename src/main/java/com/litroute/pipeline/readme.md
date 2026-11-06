# `pipeline` (M9): one full run

[PipelineRunner](PipelineRunner.java), started from the Pipeline tab or `PipelineRunnerCli`:

```
1. M5     sync profiles                  only new or changed PDFs are embedded
2. M1     fetch new alerts               unread, not in M7a, at most maxUnread
3. M2–M4  AlertBatchProcessor.process    → M7c, then M7a
4. M1     mark read                      unless "Keep emails unread"
5. M6     match every paper neither queued nor sent → M7e (PENDING or UNMATCHED)
```

- **Crash-safe:** mail is marked read only after M7a holds it. Step 5 picks up papers a failed run left unscored.
- **Never twice:** a sent paper is never queued again for that student, even after a reset.
- **Authors list** ([PriorityAuthors](PriorityAuthors.java)): adding an author releases their `UNMATCHED` papers so the next run scores them again.

**Clear and re-run** (`reset`): marks M7a's alerts unread in Gmail (if that fails, nothing is cleared), then empties the queue, M7c and M7a. Downloaded PDFs and sent papers are kept.

**CLI:** `--extraction=pattern|llm --max-unread=N --keep-unread --send-approved`. The passphrase comes from the terminal, or from `LITROUTE_PASSPHRASE` under cron.

## Tests

| Test | Checks |
|---|---|
| [PipelineRunnerTest](../../../../../test/java/com/litroute/pipeline/PipelineRunnerTest.java) | Six real alerts: queued once, read only after M7a, second run finds nothing, reset reprocesses and never re-queues sent papers |
| [PriorityAuthorsTest](../../../../../test/java/com/litroute/pipeline/PriorityAuthorsTest.java) | Only unmatched papers are released; strict names |
| [EmlAlertsPipelineTest](../../../../../test/java/com/litroute/pipeline/eml/EmlAlertsPipelineTest.java) | Only when asked: `-Dtest=EmlAlertsPipelineTest -Dlitroute.emlAlerts=<folder>` (live network) |
