# `model`: records passed between stages

Immutable records with no pipeline logic. Each one validates its fields in its constructor, so bad data is rejected where it is created. `with...` methods return changed copies.

```
RawEmail ─M2/M3─▶ PaperCandidate ─M6─▶ ScoredPaper ─M8/M10─▶ ReviewStatus
student PDFs ─M5─▶ Profile / ProfilePaper
```

| Record | Made by | Key points |
|---|---|---|
| [RawEmail](RawEmail.java) | M1 | `messageId` (M7a's key) and at least one body |
| [PaperCandidate](PaperCandidate.java) | M2, M3 | Only `title` is required; nothing is guessed. `isResearch()` is false for notes. |
| [Profile](Profile.java) | M5 | Name, emails, ≥ 1 `ProfilePaper` (vectors, text, keywords), `modelId` |
| [ScoredPaper](ScoredPaper.java) | M6 | `score` = relevance × share, `rationale`, `status`, every student's `fits` (for **Move**). `queueKey()` = student + DOI or compacted title. |
| [ReviewStatus](ReviewStatus.java) | | `PENDING`, `APPROVED`, `DROPPED`, `SENT`, `UNMATCHED` |
| [AuthorName](AuthorName.java) | Authors tab | First and last name spelled out; strict `matches` |

The stores serialize their own JSON shapes, so a corrupt entry fails the constructor and surfaces as an `IOException`.

**Tests:** [PaperCandidateTest](../../../../../test/java/com/litroute/model/PaperCandidateTest.java) (`isResearch` on real titles); the rest through the stages and stores.
