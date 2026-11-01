# The `ui` module (M8)

**In one sentence:** the professor's JavaFX window: review the matched papers, run the pipeline, list the authors to favour, rebuild the profiles, watch and clear the caches, and manage the Gmail credentials, without a terminal.

```
mvn -q compile javafx:run
```

That command runs [LitRouteApp](LitRouteApp.java), the pom's `javafx-maven-plugin` `mainClass`: `main` calls `launch`, and `start` builds the window (a menu bar, a "credentials are locked" banner, and the six tabs below) and opens on the Gmail tab. `stop` closes the [Session](Session.java). [Launcher](Launcher.java) is a plain `main` that calls `LitRouteApp.main`, for running from a plain classpath (an IDE's Run, or `exec:java`), where the JVM refuses to start a main class that extends `Application`.

## The tabs

| Tab | Class | What it does |
|---|---|---|
| **Review** | [ReviewPane](ReviewPane.java) | The review queue (M7e). Students on the left with how many papers wait for each; their papers in the middle; the chosen paper's details and M6's rationale below. **Approve**, **Drop**, **Back to pending** (or A, D, P) record the decision for every selected paper. **Send approved** emails them to their students (M10), which moves them out of the queue. **Show: Sent** lists the sent papers with when they went, read only. |
| **Pipeline** | [PipelinePane](PipelinePane.java) | Runs M9 with a live log. **Read emails with** toggles between **Pattern matching** and **Local AI model** (M2); that, how many unread emails a run examines, and **Keep emails unread** (for testing) are chosen here and remembered. **Clear and re-run…** undoes every run so far and runs again over the same emails ([pipeline readme](../pipeline/readme.md)). |
| **Authors** | [AuthorsPane](AuthorsPane.java) | The authors whose papers M6 favours: a lower cutoff, first in the Review tab, the author given as the reason ([scoring readme](../scoring/readme.md)). It takes a **first name** and a **last name**, both spelled out, and matches strictly: Alice Carter finds Alice K. Carter but not A. Carter or AK Carter. A new author's papers that were turned down get one more look on the next run. |
| **Profiles** | [ProfilesPane](ProfilesPane.java) | The stored profiles, **Rebuild profiles**, which syncs them with their folders (M5), and **Open profiles folder**. |
| **Caches** | [CachesPane](CachesPane.java) | The size and contents of each cache, sent papers included ([DataCaches](../store/DataCaches.java)). Choosing caches shows what clearing them means; **Clear selected** works only once **I understand these consequences** is ticked, and any change of choice undoes the tick. |
| **Gmail** | [CredentialsPane](CredentialsPane.java) | Unlock the stored App Password with the master passphrase, or set it up. A tab rather than a dialog, because tiling window managers hide modal dialogs. |

[Session](Session.java) holds what lives as long as the window: the master passphrase once unlocked (wiped on exit), the SPECTER2 model (loaded on first use) and one job thread, so a pipeline run and a profile rebuild never overlap. [LogPane](LogPane.java) shows a job's progress and what LitRoute's classes log meanwhile.

## The review table

- **Listed authors' papers first, then best score first.** The columns don't re-sort, so a decision (approve, drop, back to pending) never reorders the list. **Listed author** names the author, as printed on the paper.
- **Score** = relevance × share. Hovering the header explains it; hovering a cell shows that paper's breakdown, e.g. "0.29 = relevance 0.82 × share 0.36", and says when another student's work is close too.
- **Relevance**: how close the paper is to the student's own work (0.9 as close as their own papers, 0.1 as close as another student's). Hovering explains the match floors (0.70, or 0.43 from a title alone; 0.50 or 0.36 for a listed author), what this paper's value means, and whether it was measured from the full PDF, the title and abstract, or the title alone. "–" if it was not recorded when the paper was queued.
- **★** marks a recommended paper. The matcher queues only papers it recommends. **Recommended only** hides any others, such as entries from older queues.
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
 Authors    │ AuthorsPane ──runJob──▶ PriorityAuthors ──▶ priority_authors.json, scored_papers.json         │
 Profiles   │ ProfilesPane ──runJob──▶ ProfileBuilder.syncAll (M5) ──▶ M7d profiles.json ──▶ table          │
 Caches     │ CachesPane ──runIo: report / runJob: clear──▶ DataCaches ──▶ every store in M7, pdfs/         │
            └───────────────────────────────────────────────────────────────────────────────────────────────┘
```

Long work (a pipeline run, a rebuild, a send) goes through `Session.runJob` on one job thread, so two of them never overlap and the buttons are disabled meanwhile (`busyProperty`). Clearing a cache and changing the authors list are jobs too, though short, so they never write a store under a running pipeline. A review decision is a short file write and goes through `Session.runIo`, a separate thread that keeps decisions in order without waiting for a running job; measuring the caches runs there as well.

## Interaction with M7 (the stores)

| Store | Read by | Written by |
|---|---|---|
| **M7a** `email_cache.json` | PipelinePane (how many emails **Clear and re-run…** will mark unread), CachesPane | the pipeline run, through PipelineRunner; **Clear selected** |
| **M7c** `enrichment_cache.json` | AuthorsPane (a new author's held papers), CachesPane | the pipeline run; **Clear selected** |
| **M7d** `profiles.json` | ProfilesPane (the table), CachesPane | ProfilesPane's **Rebuild** (ProfileBuilder), and each pipeline run's profile sync; **Clear selected** |
| **M7e** `scored_queue.json` + `matched/` | ReviewPane (the table), PipelinePane (counts for the confirmation), AuthorsPane, CachesPane | ReviewPane: Approve/Drop/Back to pending (`setStatus`); the pipeline adds papers; **Send approved** removes sent ones; **Clear and re-run…** and **Clear selected** empty it |
| `sent_papers.json` | ReviewPane (**Show: Sent**, and to show a paper caught between queue and record as sent), AuthorsPane, CachesPane | **Send approved**, through ApprovedPaperSender; **Clear selected** |
| `scored_papers.json` | CachesPane | the pipeline run; **Add** author forgets some; **Clear selected** |
| `priority_authors.json` | AuthorsPane | **Add**, **Remove** |

Every one of these reads and writes goes through the store classes in [store](../store/readme.md), whose per-file locks let the review table and a running pipeline change the queue at the same time without losing either's update.

## The classes

| Class | Role |
|---|---|
| [LitRouteApp](LitRouteApp.java) | The JavaFX `Application`: builds the window and the tabs, the File menu and the locked-credentials banner; closes the session on exit. |
| [Launcher](Launcher.java) | Plain `main` that starts LitRouteApp from a classpath (IDE, `exec:java`). |
| [Session](Session.java) | What lives as long as the window: the unlocked passphrase, the SPECTER2 model, the job and io threads, and the busy/unlocked properties the tabs bind to. |
| [CredentialsPane](CredentialsPane.java) | The Gmail tab: unlock with the master passphrase, or set up / change the credentials. |
| [PipelinePane](PipelinePane.java) | The Pipeline tab: how to read emails, max unread, keep unread, **Run pipeline**, **Clear and re-run…**, live log and summary. |
| [ReviewPane](ReviewPane.java) | The Review tab over M7e: students, papers best first, details and rationale, decisions, **Send approved**. |
| [AuthorsPane](AuthorsPane.java) | The Authors tab: the list of authors to favour, first and last name fields, **Add**, **Remove**. |
| [ProfilesPane](ProfilesPane.java) | The Profiles tab over M7d: the profile table, **Rebuild profiles**, **Open profiles folder**. |
| [CachesPane](CachesPane.java) | The Caches tab: each cache's size and contents, what clearing it means, the agreement and **Clear selected**. |
| [LogPane](LogPane.java) | A timestamped log area that also shows `com.litroute` log records while a job runs. |
