# `delivery` (M10): email approved papers

[ApprovedPaperSender.send()](ApprovedPaperSender.java) groups the `APPROVED` queue entries by student and emails each student one plain-text digest from the professor's Gmail (`smtps://smtp.gmail.com:465`), with the PDFs attached. It is started by **Send approved** (Review tab) or `PipelineRunnerCli --send-approved`.

- **Digest:** `LitRoute: 3 new papers for alice`. Each paper is listed with its title, authors, venue, year, link and abstract. Recommended papers come first, and replies go to the professor.
- **Size:** at most 15 MiB of PDFs per email; the rest continues in `(part 2 of N)`. A missing or oversized PDF is listed with a note to use the link.
- **Exactly once:** papers are recorded in `sent_papers.json`, then removed from M7e. `settle()` cleans up after a crash between the two steps. A crash before recording resends that one email.
- **Failures:** a refused recipient keeps their papers `APPROVED`. A student with no address is reported. A lost connection stops the send.

| Class | Role |
|---|---|
| [ApprovedPaperSender](ApprovedPaperSender.java) | Settle, compose, send, record, remove, report |
| [DigestComposer](DigestComposer.java) | One student's papers → [DigestEmail](DigestEmail.java)s within the size budget |
| [GmailSmtpSender](GmailSmtpSender.java) | SMTP over SSL; text + PDF MIME parts |

**Tests:** [DigestComposerTest](../../../../../test/java/com/litroute/delivery/DigestComposerTest.java) (body, order, split, missing PDFs); [ApprovedPaperSenderTest](../../../../../test/java/com/litroute/delivery/ApprovedPaperSenderTest.java) against GreenMail (byte-identical PDFs, no resend, no address, refused login).
