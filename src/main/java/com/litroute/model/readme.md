# The `model` module

**In one sentence:** the shared, immutable data records that pass between pipeline stages. Each record is the output of one stage and the input of the next, so this package contains no logic, only data and the rules that keep it valid.

## Where each record sits in the pipeline

```
Gmail INBOX
   │  M1 GmailEmailSource
   ▼
RawEmail ─────────────────┐   one alert email, unparsed
   │  M2 PaperExtractor   │
   │  M3 enrichment       │
   ▼                      │
PaperCandidate            │   one paper from an email (filled in from Crossref/OpenAlex)
   │  M4 Deduplicator     │
   ▼                      │
PaperCandidate (deduplicated, in M7c)
   │
   │            student folders of PDFs
   │               │  M5 ProfileBuilder
   │               ▼
   │            Profile (ProfilePaper)                    in M7d
   │               │
   ▼  M6 RelevanceScorer
ScoredPaper  (status = ReviewStatus.PENDING)              in M7e review queue
   │  M8 professor approves / drops
   ▼
ScoredPaper  (status = APPROVED ─▶ M10 sends to the student, or DROPPED)
```

## The records

Every record checks its inputs in a compact constructor and copies its lists with `List.copyOf`. This rejects invalid data when the object is created and makes the lists unmodifiable, so nothing downstream can change them.

### [RawEmail](RawEmail.java): output of M1

One Gmail message that looked like a publication alert, **before** any HTML parsing.

| Field | Meaning |
|---|---|
| `messageId` | The RFC 822 `Message-ID` header. M7a records it so the same email is never processed twice. Must not be blank. |
| `subject`, `fromAddress` | As received. |
| `receivedAt` | UTC timestamp. |
| `htmlBody`, `plainTextBody` | Either may be empty, but **not both**. |

### [PaperCandidate](PaperCandidate.java): output of M2/M3, input of M4 and M6

One paper mentioned in an alert email.

- **`title` is the only required field.** Everything else is `Optional` and stays empty when it can't be found. Nothing is guessed.
- **The email's text takes priority.** Values taken from the email are never overwritten. Data looked up from Crossref, OpenAlex or the publisher's page only fills fields the email left empty.
- Fields: `sourceMessageId`, `title`, `authors` (placeholders like "others" or "…" removed), `venue`, `year` (the paper's year, never the email's date), `articleType` (the alert's own section label, e.g. "Research article"), `doi` (bare, no URL prefix), `url` (redirect wrappers removed), `abstractSnippet`, and `pdfPath` (where the PDF was saved locally, if one could be downloaded).

### [Profile](Profile.java): output of M5, input of M6 and M10

What one student works on, built from the PDFs in their profile folder. A profile is **uniform**: one body of work. M6 compares a paper with all of the student's papers together and with the closest few.

| Field | Meaning |
|---|---|
| `name` | The folder's name. Must not be blank. |
| `emails` | Addresses from the folder's `email.txt`, where M10 will send approved papers. |
| `papers` | One [ProfilePaper](Profile.java#L57) per readable PDF. **Never empty.** |
| `modelId` | The embedding model that produced every vector. Vectors from different models can't be compared. |
| `updatedAt` | When the profile last actually changed. |

- **`ProfilePaper`** holds one reference paper: its relative path, SHA-256 (so an unchanged file is never embedded twice), title, the **combined** `embedding` used for scoring, the three view vectors (`abstractView`, optional `structureView` and `bodyView`), and the extracted `abstractText` and `bodyText`. M6 matches words in that text without opening the PDF again. `hasText()` is false for papers saved before text was stored. The vector arrays are shared, not copied, so treat them as read-only.

### [ScoredPaper](ScoredPaper.java): output of M6, held in M7e, used by M8 and M10

How relevant one candidate is to one student, and why.

| Field | Meaning |
|---|---|
| `candidate` | The deduplicated paper. |
| `profileName` | The student it was matched to. |
| `score` | 0.0–1.0: *relevance* times *share*. The constructor rejects values outside this range. |
| `rationale` | Readable evidence: the share, the relevance, shared words, the closest of the student's papers, and what the paper was scored from. |
| `status` | The professor's decision so far ([ReviewStatus](ReviewStatus.java)). |
| `recommended` | Whether M6 picked it as one of the student's top 2–3 papers in its batch. The professor still decides. |

- `withStatus(...)` and `withRecommended(...)` return changed copies; the record itself never changes.
- **[queueKey()](ScoredPaper.java#L57)** identifies an entry in the review queue: the student plus the paper, identified by `doi:<lowercased DOI>` or, without a DOI, `title:<compacted title>` (the same way M4 identifies papers). M7e uses it to update a re-scored paper instead of adding it twice.

### [ReviewStatus](ReviewStatus.java)

An enum for where a scored paper stands in the professor's queue: `PENDING` (scored, waiting for review), `APPROVED` (to be sent to the student by M10) or `DROPPED` (not to be sent).

## How it's tested

This package has no test class of its own. Its validation rules are tested through the stages that create and store the records: [store](../../../../../test/java/com/litroute/store/) (records survive saving and loading, and corrupt entries are rejected), [scoring](../../../../../test/java/com/litroute/scoring/) (scores stay within 0–1 and come with a rationale), and the ingestion tests.
