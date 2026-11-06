# `model`: the records passed between stages

These are immutable records and contain no pipeline logic. Each one validates its fields in a compact constructor and copies its lists with `List.copyOf`, so invalid data is rejected where it is created. `with...` methods return changed copies.

```
RawEmail ─M2/M3─▶ PaperCandidate ─M4─▶ (M7c) ─M6─▶ ScoredPaper (M7e) ─M8/M10─▶ status
student PDFs ─M5─▶ Profile / ProfilePaper (M7d)
```

| Record | Made by | Key points |
|---|---|---|
| [RawEmail](RawEmail.java) | M1 | One alert as received: `messageId` (required; M7a's key), subject, sender, UTC time, HTML and/or plain-text body (at least one). |
| [PaperCandidate](PaperCandidate.java) | M2, M3 | One paper. Only `title` is required; every other field is `Optional` and never guessed. The email's values win: a lookup only fills gaps, except that it can complete an abstract the alert cut short. `isResearch()` is false for notes (book reviews, discussions, errata, editorials, special-issue introductions), recognized by section label or title. |
| [Profile](Profile.java) | M5 | A student: `name` (the folder), `emails`, `papers` (never empty), up to 10 `keywords`, `modelId`, `updatedAt`. Each `ProfilePaper` holds its path, SHA-256, title, combined vector, the three view vectors, its abstract and body text, and its keywords. The vector arrays are shared, so treat them as read-only. |
| [ScoredPaper](ScoredPaper.java) | M6 | One paper for one student: `score` (relevance × share, 0–1), `rationale`, `status`, `recommended`, optional `relevance` and `listedAuthor`, and `fits` (every student's score, used by `movedTo`). `queueKey()` = student + `doi:<doi>` or `title:<compacted>`. |
| [ReviewStatus](ReviewStatus.java) | — | `PENDING`, `APPROVED`, `DROPPED`, `SENT` (moved to SentPaperStore, never queued again), `UNMATCHED` (matched to no one; shown under Dropped). |
| [AuthorName](AuthorName.java) | Authors tab | First and last name, both spelled out (an initial is refused). `matches(printed)` is strict ([PersonNames](../util/PersonNames.java)). |

**Storage:** the stores never serialize these records directly. Each store has its own JSON shape with nullable fields ([StoredCandidate](../store/StoredCandidate.java), and the private records in ProfileStore and ScoredQueueStore), so a corrupt or hand-edited entry fails the constructor and is reported as an `IOException`.

**Tests:** [PaperCandidateTest](../../../../../test/java/com/litroute/model/PaperCandidateTest.java) checks `isResearch()` on real titles from the alerts. The other rules are tested through the stages and stores that create and store these records.
