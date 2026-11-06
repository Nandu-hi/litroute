# `delivery` (M10): email approved papers to students

[ApprovedPaperSender.send()](ApprovedPaperSender.java) takes every `APPROVED` entry in M7e and groups them by student. It emails each student a plain-text digest from the professor's Gmail (`smtps://smtp.gmail.com:465`, one login per send), with the PDFs attached, then moves each sent paper from the queue to `sent_papers.json`.

- **The digest:** the subject reads `LitRoute: 3 new papers for alice`. Each paper is listed with its title, authors, venue · year · type, link, abstract, and whether its PDF is attached. Recommended papers come first, then by score. Replies go to the professor.
- **Size limit:** at most 15 MiB of PDFs per email (about 21 MB once base64-encoded, under Gmail's 25 MB). Overflow continues in `(part 2 of N)`. A paper whose PDF is missing or too large is still listed, with a note to use its link.
- **Exactly once:** after each email is accepted, its papers are **recorded as sent, then removed** from M7e. Every send starts with `settle()`, which removes papers already recorded (left behind by a crash between the two steps). A crash between sending and recording resends that one email rather than losing it.
- **Partial failure:** a refused recipient is reported, and their papers stay `APPROVED`. A student with no address is reported as unaddressed. A lost connection stops the send; papers already sent stay recorded.
- **No empty emails**, and a sent paper is never re-queued for that student (M9 checks `sentKeys()`).

It is started by **Send approved** on the Review tab, or by `PipelineRunnerCli --send-approved`.

| Class | Role |
|---|---|
| [ApprovedPaperSender](ApprovedPaperSender.java) | Settle, read M7e + M7d (addresses), compose, send, record, remove, report |
| [DigestComposer](DigestComposer.java) | One student's approved papers → [DigestEmail](DigestEmail.java)s within the attachment budget |
| [GmailSmtpSender](GmailSmtpSender.java) | SMTP over SSL; builds the MIME message (text + `application/pdf` parts) |

**Tests:** [DigestComposerTest](../../../../../test/java/com/litroute/delivery/DigestComposerTest.java) checks the body contents, the ordering, the split at the budget, and papers with a missing or oversized PDF. [ApprovedPaperSenderTest](../../../../../test/java/com/litroute/delivery/ApprovedPaperSenderTest.java) runs against GreenMail SMTPS: PDFs arrive byte-identical, a second send sends nothing, a student without an address keeps their papers, and a refused login changes nothing.
