# `ui` (M8): the professor's window

`mvn -q compile javafx:run` ([LitRouteApp](LitRouteApp.java)), or [Launcher](Launcher.java) from an IDE. It opens on the Gmail tab.

| Tab | Class | Does |
|---|---|---|
| Review | [ReviewPane](ReviewPane.java) | Approve / Drop / Back to pending (A / D / P), Move (M), Send approved. **Show: Dropped** includes unmatched papers. |
| Pipeline | [PipelinePane](PipelinePane.java) | Run with a live log; reading mode, max unread, keep unread; Clear and re-run |
| Authors | [AuthorsPane](AuthorsPane.java) | Authors to favour (full first and last name) |
| Profiles | [ProfilesPane](ProfilesPane.java) | Profile table, Rebuild, Open folder |
| Caches | [CachesPane](CachesPane.java) | Size and contents of each cache; clear after ticking the consequences |
| Gmail | [CredentialsPane](CredentialsPane.java) | Unlock or set up credentials (a tab, because tiling WMs hide dialogs) |

**Columns:** Score = relevance × share; Relevance 0.9 ≈ as close as the student's own papers; ★ = recommended. Decisions never re-sort the list.

**Threads** ([Session](Session.java), which also holds the passphrase and the lazily loaded model): one **job** thread for runs, rebuilds, sends and clears (buttons disabled meanwhile), and an **IO** thread for review decisions. [LogPane](LogPane.java) shows job progress and `com.litroute` logs.
