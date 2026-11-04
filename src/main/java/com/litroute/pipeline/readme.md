# The `pipeline` module (M9)

**In one sentence:** one run of the whole pipeline, from the students' profile folders and Gmail to the professor's review queue, started from the UI's Pipeline tab or from the command line.

## Input and output

| | What | Notes |
|---|---|---|
| **Input** | Gmail credentials, the profile folders (`~/.litroute/profiles/<student>/`), the stores in `~/.litroute/data/` | How M2 reads the emails is chosen per run ([ExtractionMode](../ingestion/extract/ExtractionMode.java)): pattern matching (the default) or the local language model on Ollama. |
| **Output** | New entries in the review queue (M7e), and a [Report](PipelineRunner.java) of what the run did | Progress lines go to the caller as they happen (the UI's log, or standard output). |

## How a run works

```
PipelineRunner.run(credentials, progress)
   │
   ├─ 1. M5 ProfileBuilder.syncAll         only new or changed PDFs are embedded
   ├─ 2. M1 GmailEmailSource.fetchNewAlerts unread alerts not yet in M7a, at most maxUnread
   ├─ 3. M2–M4 AlertBatchProcessor.process  notes dropped, papers enriched, deduplicated → M7c, then M7a
   ├─ 4. M1 markRead                        only now, and only unless "keep emails unread" is on
   └─ 5. M6 match the papers not scored yet → M7e queue: matches as pending, the rest as
                  not matched, under Dropped (a paper already in the queue or sent is not scored
                   sent to that student; the log says how many of each)
```

- **Crash safety.** Each step's stores are written in the order that makes a crash repeat work rather than skip it. Alerts are marked read only after M7a holds them, since M1 looks at unread mail only. A batch is recorded as scored only after its matches are queued.
- **What M6 sees.** Every deduplicated paper not yet scored, not just this run's. So papers left unscored by a run that failed after saving M7c (no profiles yet, or the matcher failed) are scored by the next one. A run in Local AI mode whose model can't be reached fails before anything is saved for its emails, so the next run reads them.
- **Listed authors.** The matcher reads the authors list ([PriorityAuthorStore](../store/PriorityAuthorStore.java)) afresh for each run and holds those authors' papers to a lower floor ([scoring readme](../scoring/readme.md)). [PriorityAuthors](PriorityAuthors.java) changes the list. M6 scores a paper once, so adding an author also takes their not-matched papers out of the queue, and the next run scores those once more; a paper the professor has acted on, or that was sent, keeps its place, since it has had its decision. Removing an author leaves queued papers queued.
- **Keep emails unread** (for testing; [PipelineRunner](PipelineRunner.java)'s `markRead` flag, `--keep-unread` on the command line) skips step 4. M7a still keeps the alerts from being processed twice.

## Clear and re-run

[reset(credentials, progress)](PipelineRunner.java) undoes every run so far, so the next run processes the same alert emails from scratch:

1. The alerts recorded in M7a are marked **unread** in Gmail ([markUnread](../ingestion/email/GmailEmailSource.java)), whether they were read or left unread. If Gmail can't be reached, this throws and **nothing is cleared**: clearing would lose mail that is still marked read.
2. The review queue is emptied, with the professor's decisions and the students' PDF copies.
3. The record of scored papers is cleared.
4. M7c (papers and lookup cache), then M7a, are cleared ([forgetAll](../ingestion/extract/AlertBatchProcessor.java)).

A crash part-way leaves M7a for last: its emails are skipped, never lost. PDFs downloaded by M2 stay in `~/.litroute/data/pdfs/`, and **the sent papers are kept**, so the re-run never queues a paper for a student who already received it. The UI's **Clear and re-run…** asks first, stating how many emails will be marked unread and how many queued papers and decisions will be lost, then resets and runs.

## Interaction with M7 (the stores)

M9 is the only module that drives every store; the order of its reads and writes is what makes a crash repeat work instead of skipping it.

| Step | Reads | Writes |
|---|---|---|
| 1. profile sync (M5) | **M7d** | **M7d** (changed profiles only) |
| 2. fetch (M1) | **M7a** (IDs to skip) | — |
| 3. extract/enrich/dedup (M2–M4, AlertBatchProcessor) | **M7c** (papers + lookup cache), **M7a** | **M7c**, then **M7a** last |
| 4. mark read (M1) | — | Gmail only, and only for IDs now in M7a |
| 5. match (M6) | **M7d** (profiles), **M7c** (via step 3's result), **M7e** + `sent_papers.json` (what is already scored) | **M7e** (`addAll`, matched and not matched) |
| reset | **M7a** (which emails to mark unread) | **M7e** `clear()`, **M7c** then **M7a** emptied (`forgetAll`) — never `sent_papers.json` |

The classes:

| Class | Role |
|---|---|
| [PipelineRunner](PipelineRunner.java) | One run (`run`) and **Clear and re-run** (`reset`) over injected stages and stores; `atDefaultLocations` wires the real ones under `~/.litroute`. Also the `Report`, `ResetReport` and `MatcherFactory` types. |
| [PriorityAuthors](PriorityAuthors.java) | Adds an author to the list and removes one, taking a new author's not-matched papers out of the queue to be scored again. |
| [PipelineRunnerCli](PipelineRunnerCli.java) | The terminal / cron entry point: parses the flags, unlocks the credentials (terminal or `LITROUTE_PASSPHRASE`), runs once, optionally sends the approved papers (M10). |

## Running it without the UI

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli [-Dexec.args="--extraction=llm --max-unread=100 --keep-unread --send-approved"]
```

[PipelineRunnerCli](PipelineRunnerCli.java) reads the master passphrase from the terminal, or from `LITROUTE_PASSPHRASE` where there is none (cron). `--send-approved` then emails the approved papers to their students (M10). Clear and re-run is not available here.

## How it's tested

| Test | What it checks |
|---|---|
| [PriorityAuthorsTest](../../../../../test/java/com/litroute/pipeline/PriorityAuthorsTest.java) | Adding an author forgets their held papers, past a middle name, and only theirs; a different first name is someone else; a queued or sent paper keeps its place in the log; the same spelling is listed once; removing an author leaves the rest. |
| [PipelineRunnerTest](../../../../../test/java/com/litroute/pipeline/PipelineRunnerTest.java) | On the client's six real alerts, enriched offline from recorded replies and matched by the real model against the real profiles: a first run scores every paper and queues each once, for one student: the close ones pending, the rest not matched, with every student's score kept; alerts are marked read only once M7a holds them; told to keep mail unread, it marks none and still skips processed alerts; a second run finds nothing new and keeps the professor's decisions; papers fetched before any profile existed are scored once there is one; papers are scored again when the matcher failed. **Reset:** the next run processes the same six emails afresh, with decisions discarded and the alerts read again; a reset that can't reach Gmail clears nothing; a paper already sent is neither scored nor queued again after a reset. |
