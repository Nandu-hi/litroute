# `ui` (M8): the professor's window

Start it with `mvn -q compile javafx:run` ([LitRouteApp](LitRouteApp.java)), or from an IDE with [Launcher](Launcher.java); the JVM won't start an `Application` subclass directly from a plain classpath. The app opens on the Gmail tab.

| Tab | Class | Does |
|---|---|---|
| **Review** | [ReviewPane](ReviewPane.java) | Students on the left, their papers in the middle (listed authors first, then best first), and the details and rationale below. **Approve / Drop / Back to pending** (A / D / P) apply to the selection. **Move** (M) gives the paper to another student, listed closest first; it takes that student's score. **Show: Dropped** includes the unmatched papers; **Show: Sent** is read-only. **Send approved** runs M10. |
| **Pipeline** | [PipelinePane](PipelinePane.java) | **Run pipeline** with a live log. Settings, all remembered: *Read emails with* (pattern matching or local AI), max unread, *Keep emails unread*. **Clear and re-run…** asks for confirmation first. |
| **Authors** | [AuthorsPane](AuthorsPane.java) | The authors to favour: first and last name, both spelled out, matched strictly. |
| **Profiles** | [ProfilesPane](ProfilesPane.java) | The profile table, **Rebuild profiles**, **Open profiles folder**. |
| **Caches** | [CachesPane](CachesPane.java) | Size and contents of each cache ([DataCaches](../store/DataCaches.java)). **Clear selected** works only after *I understand these consequences* is ticked; changing the selection unticks it. |
| **Gmail** | [CredentialsPane](CredentialsPane.java) | Unlock with the master passphrase, or set up the credentials. It is a tab, not a dialog, because tiling window managers hide modal dialogs. |

**Review columns:**

- **Score** = relevance × share. Hover a cell for its breakdown.
- **Relevance:** 0.9 means as close as the student's own papers, 0.1 as close as another student's. The hover text gives the floors and the evidence used.
- **★** = recommended. *Recommended only* hides the rest (older queue entries).
- Columns don't re-sort, so a decision never reorders the list.

**Threads** ([Session](Session.java)): Session also holds the unlocked passphrase (wiped on exit) and the lazily loaded SPECTER2 model.

- **Job thread:** long or store-changing work (a run, a rebuild, a send, a cache clear, an authors change) runs one job at a time, and the buttons are disabled meanwhile.
- **IO thread:** review decisions and cache measuring run separately, so they don't wait for a running job.

The stores' per-file locks keep the two threads from losing each other's writes. [LogPane](LogPane.java) shows each job's progress plus the `com.litroute` log records written meanwhile.
