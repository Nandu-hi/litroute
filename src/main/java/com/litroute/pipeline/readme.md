# `pipeline` (M9): one full run

[PipelineRunner](PipelineRunner.java) runs everything from the profile folders and Gmail through to the review queue. It is started from the Pipeline tab or from `PipelineRunnerCli`.

```
run(credentials, progress)
  1. M5  ProfileBuilder.syncAll          only new or changed PDFs are embedded
  2. M1  fetchNewAlerts                  unread alerts not in M7a, at most maxUnread
  3. M2–M4 AlertBatchProcessor.process   → M7c, then M7a
  4. M1  markRead                        unless "Keep emails unread"
  5. M6  match every paper neither queued nor sent → M7e (matches PENDING, the rest UNMATCHED)
```

- **Crash safety:** alerts are marked read only after M7a holds them. Step 5 scores every paper not yet judged, not just this run's, so papers a failed run left unscored (no profiles yet, or the matcher threw) are picked up by the next run.
- **Never twice:** a paper in `sent_papers.json` is never queued again for that student, even after a reset.
- **Authors list:** read fresh on each run. [PriorityAuthors](PriorityAuthors.java) adds and removes authors. Adding one takes that author's `UNMATCHED` papers out of the queue so the next run scores them once more; papers the professor has acted on keep their place.

## Clear and re-run ([reset](PipelineRunner.java))

1. Mark every alert recorded in M7a **unread** in Gmail. If that fails, the reset throws and **nothing is cleared**.
2. Empty the review queue: the decisions and the students' PDF copies.
3. Empty M7c, then M7a ([forgetAll](../ingestion/extract/AlertBatchProcessor.java)).

Downloaded PDFs and the sent papers are kept. The UI asks for confirmation first, saying how many emails and queued papers are affected.

## Command line

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli \
    -Dexec.args="--extraction=pattern|llm --max-unread=N --keep-unread --send-approved"
```

`PipelineRunnerCli` reads the passphrase from the terminal, or from `LITROUTE_PASSPHRASE` when there is no terminal (cron). Reset is available only in the UI.

## Tests

| Test | Checks |
|---|---|
| [PipelineRunnerTest](../../../../../test/java/com/litroute/pipeline/PipelineRunnerTest.java) | Runs the six real alerts with recorded HTTP, the real model and real profiles: each paper is queued once; mail is marked read only after M7a; a second run finds nothing new and keeps decisions; papers left unscored are scored later; reset reprocesses the emails, clears nothing if Gmail is down, and never re-queues sent papers |
| [PriorityAuthorsTest](../../../../../test/java/com/litroute/pipeline/PriorityAuthorsTest.java) | Adding an author releases only their unmatched papers; strict names; no duplicates |
| [EmlAlertsPipelineTest](../../../../../test/java/com/litroute/pipeline/eml/EmlAlertsPipelineTest.java) | **Runs only when asked** (`-Dtest=EmlAlertsPipelineTest -Dlitroute.emlAlerts=<folder>`; live network, about 25 min for 17 alerts). The whole pipeline on saved `.eml` files; writes `target/eml-alerts-match-report.txt` |
