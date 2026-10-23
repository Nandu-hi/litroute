# The `delivery` module (M10)

**In one sentence:** it emails every paper the professor approved in the review queue to its student, from the professor's own Gmail account, with the paper's PDF attached, and marks the paper sent so it never goes twice.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | M7e [ScoredQueueStore](../store/ScoredQueueStore.java) | The review queue. Only entries whose status is `APPROVED` are sent. Their `pdfPath` points to the student's copy in `matched/<student>/`. |
| **Input** | M7d [ProfileStore](../store/ProfileStore.java) | Each student's `emails`, read from the profile folder's `email.txt` when the profiles are synced. |
| **Input** | [GmailCredentials](../security/GmailCredentials.java) | The professor's Gmail address and App Password: the same ones M1 reads the inbox with. |
| **Output** | Emails, plus statuses in M7e | One email per student (or several, see below). Each paper sent is set to `SENT`. |
| **Output** | [ApprovedPaperSender.Report](ApprovedPaperSender.java) | Emails, papers and PDFs sent; students left out because they have no address; emails that failed. |

## How it works

```
M7e queue ── APPROVED entries, grouped by student
   │
   ▼  ApprovedPaperSender.send()
   │     student's profile has no address?  ─▶ left APPROVED, reported as "unaddressed"
   │     DigestComposer.compose()           ─▶ plain-text email(s): recommended first, then by score
   │
   ▼  GmailSmtpSender.connect()  smtps://smtp.gmail.com:465, one login for the whole send
   │     for each email:
   │        send ─▶ every paper in it set to SENT in M7e
   │        a recipient refused   ─▶ reported, its papers stay APPROVED, the next email still goes
   │        connection lost       ─▶ stop (IOException); the emails already sent stay SENT
```

- **What a student receives.** The subject is `LitRoute: 3 new papers for RP1-mh-risk`. The body lists each paper's title, authors, venue · year · type, its link (URL, else `https://doi.org/<doi>`), whether its PDF is attached, and its abstract when there is one. Replies go to the professor. Gmail keeps a copy in the professor's Sent folder.
- **Attachments and Gmail's 25 MB limit.** One email carries at most 15 MiB of PDFs ([DigestComposer.GMAIL_ATTACHMENT_BUDGET](DigestComposer.java)). Base64 makes that about 21 MB on the wire. When the next PDF would not fit, the papers continue in another email (`(part 2 of 3)`). A paper whose PDF is missing, gone from disk or larger than the whole budget is still sent, with a note to use its link.
- **Exactly once, as far as a crash allows.** A paper is marked `SENT` straight after the email carrying it is accepted. A failure part-way leaves the rest `APPROVED` for the next send. A crash between sending and marking sends that one email again rather than losing it.
- **Sending again.** Approving a `SENT` paper again (in M8) sends it again.

## Where it is started

- **UI (M8):** the **Send approved (N)** button on the Review tab. It needs the Gmail credentials unlocked, and runs on the same job thread as a pipeline run. The Review tab's **Sent** filter shows what has gone.
- **CLI:** `PipelineRunnerCli --send-approved` sends after the pipeline run, e.g. from cron.

## The classes

| Class | Role |
|---|---|
| [ApprovedPaperSender](ApprovedPaperSender.java) | The send: reads M7e and M7d, composes, sends, marks `SENT`, reports. |
| [DigestComposer](DigestComposer.java) | Package-private. Turns one student's approved papers into [DigestEmail](DigestEmail.java)s within the attachment budget. |
| [DigestEmail](DigestEmail.java) | One email: recipients, subject, body, attached PDFs, and the queue entries it delivers. |
| [GmailSmtpSender](GmailSmtpSender.java) | SMTP over SSL to Gmail. `connect()` logs in once (a refused login is an `IOException`), and `Connection.send()` builds the MIME message (text + `application/pdf` parts). |

## How it's tested

| Test class | What it covers |
|---|---|
| [DigestComposerTest](../../../../../test/java/com/litroute/delivery/DigestComposerTest.java) | Two real papers from the client's alert, with their recorded PDFs: they share one email with both PDFs attached, and the body carries the authors, venue, DOI link and abstract. Recommended papers come first. PDFs over the budget continue in a second email. A paper with no PDF, a deleted PDF or one too large is listed with its link. A paper with only a title is still listed. |
| [ApprovedPaperSenderTest](../../../../../test/java/com/litroute/delivery/ApprovedPaperSenderTest.java) | Against an embedded SMTPS server (GreenMail), to two real student profiles: approved papers reach the student's address from the professor, with byte-identical PDFs, and become `SENT`, while pending ones stay. A second send sends nothing. A student without an address keeps their papers approved. Too many PDFs for one email go in two. A refused login sends nothing and marks nothing. |
