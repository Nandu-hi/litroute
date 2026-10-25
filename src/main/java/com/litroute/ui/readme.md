# The `ui` module (M8)

**In one sentence:** the professor's JavaFX window: review the matched papers, run the pipeline, rebuild the profiles and manage the Gmail credentials, without a terminal.

```
mvn -q compile javafx:run
```

That command runs [LitRouteApp](LitRouteApp.java), the pom's `javafx-maven-plugin` `mainClass`: `main` calls `launch`, and `start` builds the window (a menu bar, a "credentials are locked" banner, and the four tabs below) and opens on the Gmail tab. `stop` closes the [Session](Session.java). [Launcher](Launcher.java) is a plain `main` that calls `LitRouteApp.main`, for running from a plain classpath (an IDE's Run, or `exec:java`), where the JVM refuses to start a main class that extends `Application`.

## The tabs

| Tab | Class | What it does |
|---|---|---|
| **Review** | [ReviewPane](ReviewPane.java) | The review queue (M7e). Students on the left with how many papers wait for each; their papers in the middle; the chosen paper's details and M6's rationale below. **Approve**, **Drop**, **Back to pending** (or A, D, P) record the decision for every selected paper. **Send approved** emails them to their students (M10), which moves them out of the queue. **Show: Sent** lists the sent papers with when they went, read only. |
| **Pipeline** | [PipelinePane](PipelinePane.java) | Runs M9 with a live log: the matcher, how many unread emails a run examines, and **Keep emails unread** (for testing) are chosen here and remembered. **Clear and re-run…** undoes every run so far and runs again over the same emails ([pipeline readme](../pipeline/readme.md)). |
| **Profiles** | [ProfilesPane](ProfilesPane.java) | The stored profiles, **Rebuild profiles**, which syncs them with their folders (M5), and **Open profiles folder**. |
| **Gmail** | [CredentialsPane](CredentialsPane.java) | Unlock the stored App Password with the master passphrase, or set it up. A tab rather than a dialog, because tiling window managers hide modal dialogs. |

[Session](Session.java) holds what lives as long as the window: the master passphrase once unlocked (wiped on exit), the SPECTER2 model (loaded on first use) and one job thread, so a pipeline run and a profile rebuild never overlap. [LogPane](LogPane.java) shows a job's progress and what LitRoute's classes log meanwhile.

## The review table

- **Always best score first.** The columns don't re-sort, so a decision (approve, drop, back to pending) never reorders the list.
- **Score** = relevance × share. Hovering the header explains it; hovering a cell shows that paper's breakdown, e.g. "0.29 = relevance 0.82 × share 0.36", and says when another student's work is close too.
- **Relevance**: how close the paper is to the student's own work (0.9 as close as their own papers, 0.1 as close as another student's). Hovering explains the match floors (0.70, or 0.43 from a title alone), what this paper's value means, and whether it was measured from the full PDF, the title and abstract, or the title alone. "–" for the AI matcher, which doesn't measure it.
- **★** marks a recommended paper. The words-and-meaning matcher queues only papers it recommends; the AI matcher recommends one focus paper per student. **Recommended only** hides the rest.
- After **Clear and re-run…**, the table empties as soon as the reset is done, and fills when the run finishes.

## How data flows through the UI

```
            ┌───────────────────────────── LitRouteApp (window, tabs, banner) ─────────────────────────────┐
            │                                                                                               │
 Gmail tab  │ CredentialsPane ──unlock/save──▶ Session (passphrase, SPECTER2 model, job + io threads)       │
            │                                     │ gmail(): fresh GmailCredentials per job                 │
 Pipeline   │ PipelinePane ──runJob──▶ PipelineRunner.run / reset (M9) ──▶ M1..M6 ──▶ M7a/c/d/e            │
            │                 │ log::line, LogPane.capture()                    │ queueChanged              │
 Review     │ ReviewPane ◀── reload() ── M7e scored_queue.json + sent_papers.json ◀┘                       │
            │      │ A / D / P ──runIo──▶ ScoredQueueStore.setStatus (M7e)                                   │
            │      └ Send approved ──runJob──▶ ApprovedPaperSender (M10) ──▶ Gmail SMTP                      │
 Profiles   │ ProfilesPane ──runJob──▶ ProfileBuilder.syncAll (M5) ──▶ M7d profiles.json ──▶ table          │
            └───────────────────────────────────────────────────────────────────────────────────────────────┘
```

Long work (a pipeline run, a rebuild, a send) goes through `Session.runJob` on one job thread, so two of them never overlap and the buttons are disabled meanwhile (`busyProperty`). A review decision is a short file write and goes through `Session.runIo`, a separate thread that keeps decisions in order without waiting for a running job.

## Interaction with M7 (the stores)

| Store | Read by | Written by |
|---|---|---|
| **M7a** `email_cache.json` | PipelinePane (how many emails **Clear and re-run…** will mark unread) | the pipeline run, through PipelineRunner (never the UI directly) |
| **M7c** `enrichment_cache.json` | — | the pipeline run |
| **M7d** `profiles.json` | ProfilesPane (the table) | ProfilesPane's **Rebuild** (ProfileBuilder), and each pipeline run's profile sync |
| **M7e** `scored_queue.json` + `matched/` | ReviewPane (the table), PipelinePane (counts for the confirmation) | ReviewPane: Approve/Drop/Back to pending (`setStatus`); the pipeline adds papers; **Send approved** removes sent ones; **Clear and re-run…** empties it |
| `sent_papers.json` | ReviewPane (**Show: Sent**, and to show a paper caught between queue and record as sent) | **Send approved**, through ApprovedPaperSender |

Every one of these reads and writes goes through the store classes in [store](../store/readme.md), whose per-file locks let the review table and a running pipeline change the queue at the same time without losing either's update.

## The classes

| Class | Role |
|---|---|
| [LitRouteApp](LitRouteApp.java) | The JavaFX `Application`: builds the window and the tabs, the File menu and the locked-credentials banner; closes the session on exit. |
| [Launcher](Launcher.java) | Plain `main` that starts LitRouteApp from a classpath (IDE, `exec:java`). |
| [Session](Session.java) | What lives as long as the window: the unlocked passphrase, the SPECTER2 model, the job and io threads, and the busy/unlocked properties the tabs bind to. |
| [CredentialsPane](CredentialsPane.java) | The Gmail tab: unlock with the master passphrase, or set up / change the credentials. |
| [PipelinePane](PipelinePane.java) | The Pipeline tab: matcher choice, max unread, keep unread, **Run pipeline**, **Clear and re-run…**, live log and summary. |
| [ReviewPane](ReviewPane.java) | The Review tab over M7e: students, papers best first, details and rationale, decisions, **Send approved**. |
| [ProfilesPane](ProfilesPane.java) | The Profiles tab over M7d: the profile table, **Rebuild profiles**, **Open profiles folder**. |
| [LogPane](LogPane.java) | A timestamped log area that also shows `com.litroute` log records while a job runs. |
