# The `ui` module (M8)

**In one sentence:** the professor's JavaFX window: review the matched papers, run the pipeline, rebuild the profiles and manage the Gmail credentials, without a terminal.

```
mvn -q compile javafx:run
```

([Launcher](Launcher.java) starts it from a plain classpath too, e.g. an IDE's Run.)

## The tabs

| Tab | Class | What it does |
|---|---|---|
| **Review** | [ReviewPane](ReviewPane.java) | The review queue (M7e). Students on the left with how many papers wait for each; their papers in the middle; the chosen paper's details and M6's rationale below. **Approve**, **Drop**, **Back to pending** (or A, D, P) record the decision for every selected paper. **Send approved** emails them to their students (M10). |
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
