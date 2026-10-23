# The `pipeline` module (M9)

**In one sentence:** one run of the whole pipeline, from the students' profile folders and Gmail to the professor's review queue, started from the UI's Pipeline tab or from the command line.

## Input and output

| | What | Notes |
|---|---|---|
| **Input** | Gmail credentials, the profile folders (`~/.litroute/profiles/<student>/`), the stores in `~/.litroute/data/` | The matcher is chosen per run: words + SPECTER2 (offline, the default) or the Groq language model. |
| **Output** | New entries in the review queue (M7e), and a [Report](PipelineRunner.java) of what the run did | Progress lines go to the caller as they happen (the UI's log, or standard output). |

## How a run works

```
PipelineRunner.run(credentials, progress)
   │
   ├─ 1. M5 ProfileBuilder.syncAll         only new or changed PDFs are embedded
   ├─ 2. M1 GmailEmailSource.fetchNewAlerts unread alerts not yet in M7a, at most maxUnread
   ├─ 3. M2–M4 AlertBatchProcessor.process  notes dropped, papers enriched, deduplicated → M7c, then M7a
   ├─ 4. M1 markRead                        only now, and only unless "keep emails unread" is on
   └─ 5. M6 match the papers not scored yet → M7e queue, then ScoredPaperLog
                  (papers close to no student are not queued, and neither is a paper already
                   sent to that student; the log says how many of each)
```

- **Crash safety.** Each step's stores are written in the order that makes a crash repeat work rather than skip it. Alerts are marked read only after M7a holds them, since M1 looks at unread mail only. A batch is recorded as scored only after its matches are queued.
- **What M6 sees.** Every deduplicated paper not yet scored, not just this run's. So papers left unscored by a run that failed after saving M7c (no profiles yet, or the AI service down) are scored by the next one.
- **Keep emails unread** (for testing; [PipelineRunner](PipelineRunner.java)'s `markRead` flag, `--keep-unread` on the command line) skips step 4. M7a still keeps the alerts from being processed twice.

## Clear and re-run

[reset(credentials, progress)](PipelineRunner.java) undoes every run so far, so the next run processes the same alert emails from scratch:

1. The alerts recorded in M7a are marked **unread** in Gmail ([markUnread](../ingestion/email/GmailEmailSource.java)), whether they were read or left unread. If Gmail can't be reached, this throws and **nothing is cleared**: clearing would lose mail that is still marked read.
2. The review queue is emptied, with the professor's decisions and the students' PDF copies.
3. The record of scored papers is cleared.
4. M7c (papers and lookup cache), then M7a, are cleared ([forgetAll](../ingestion/extract/AlertBatchProcessor.java)).

A crash part-way leaves M7a for last: its emails are skipped, never lost. PDFs downloaded by M2 stay in `~/.litroute/data/pdfs/`, and **the sent papers are kept**, so the re-run never queues a paper for a student who already received it. The UI's **Clear and re-run…** asks first, stating how many emails will be marked unread and how many queued papers and decisions will be lost, then resets and runs.

## Running it without the UI

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli [-Dexec.args="--matcher=ai --max-unread=100 --keep-unread --send-approved"]
```

[PipelineRunnerCli](PipelineRunnerCli.java) reads the master passphrase from the terminal, or from `LITROUTE_PASSPHRASE` where there is none (cron). `--send-approved` then emails the approved papers to their students (M10). Clear and re-run is not available here.

## How it's tested

| Test | What it checks |
|---|---|
| [PipelineRunnerTest](../../../../../test/java/com/litroute/pipeline/PipelineRunnerTest.java) | On the client's six real alerts, enriched offline from recorded replies and matched by the real model against the real profiles: a first run scores every paper and queues the close ones, each for one student; alerts are marked read only once M7a holds them; told to keep mail unread, it marks none and still skips processed alerts; a second run finds nothing new and keeps the professor's decisions; papers fetched before any profile existed are scored once there is one; papers are scored again when the matcher failed. **Reset:** the next run processes the same six emails afresh, with decisions discarded and the alerts read again; a reset that can't reach Gmail clears nothing; a paper already sent is not queued for its student again after a reset. |
