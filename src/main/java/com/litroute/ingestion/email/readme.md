# The `email` module (M1)

**In one sentence:** it logs in to Gmail over IMAP, scans the whole inbox, keeps only messages that look like publication or citation alerts and haven't been processed yet, and returns each one as a [RawEmail](../../model/RawEmail.java) for M2 to parse.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | [GmailCredentials](../../security/GmailCredentials.java) | The Gmail address and an IMAP **App Password**, decrypted by [CredentialStore](../../security/CredentialStore.java). |
| **Input** | `Set<String>` of Message-IDs | The emails already handled, from M7a ([EmailCacheStore](../../store/EmailCacheStore.java)). These are skipped without being parsed. |
| **Output** | `List<`[RawEmail](../../model/RawEmail.java)`>` | One per new alert: `messageId`, `subject`, `fromAddress`, `receivedAt` (UTC), and the `htmlBody` and/or `plainTextBody`. No HTML parsing happens here; that is M2's job. |

**It never throws on a connection problem.** A rejected login, an unreachable server or a dropped connection is logged, and the method returns an empty list, so one bad run doesn't stop the rest of the pipeline.

## How it works

```
GmailCredentials, already-processed Message-IDs (M7a)
   │
   ▼  GmailEmailSource.fetchNewAlerts()
   │     connect imaps://imap.gmail.com:993 (SSL, 10 s timeouts)
   │     open INBOX read-only
   │
   │  for each message:
   │     ├─ no Message-ID, or already in M7a?  ─▶ skip
   │     ├─ read From, Subject; walk the MIME tree for HTML + plain-text bodies
   │     ├─ AlertClassifier.isPublicationAlert(from, subject, plainText)
   │     │        └─ false ─▶ skip
   │     ├─ no readable body?                   ─▶ skip (logged)
   │     └─ RawEmail(messageId, subject, from, receivedAt, html, plainText)
   │
   ▼  close INBOX and connection; wipe the app password from memory
List<RawEmail> ──▶ M2 AlertBatchProcessor / PaperExtractor
```

### [EmailSource](EmailSource.java): the interface

It has one method, `fetchNewAlerts(credentials, alreadyProcessedMessageIds)`, which returns new alert emails oldest first. Its contract requires **failing gracefully**: problems are logged and give an empty list, never an exception.

### [GmailEmailSource](GmailEmailSource.java): the IMAP implementation

- **[fetchNewAlerts](GmailEmailSource.java#L89)** opens a Jakarta Mail `imaps` session, connects with the address and app password, reads the inbox, and always closes the connection. In a `finally` block it overwrites the copy of the app password with `'\0'`, so the secret doesn't stay in memory. A `MessagingException` is logged and gives `List.of()`.
- **[readInbox](GmailEmailSource.java#L116)** opens `INBOX` **read-only**, so nothing is marked as read or changed, and converts each message.
- **[toRawEmailIfNewAlert](GmailEmailSource.java#L137)** is the filter for one message. It skips messages with no `Message-ID` or that are already in M7a, extracts the bodies, asks the classifier, and skips messages with no readable body. `receivedAt` falls back to "now" if the server gives no date. An unreadable message is logged and skipped, so it can't stop the rest of the batch.
- **[extractBodies](GmailEmailSource.java#L213)** walks the MIME structure recursively. A `text/html` part fills `html`, a `text/plain` part fills `plainText`, and `multipart/*` sections are combined, keeping the **first** HTML part and the **first** plain-text part found.
- **The bodies are read before classification.** A forwarded alert can only be recognized from the original headers quoted in its plain-text body, so classification can't rely on the envelope alone.
- A package-private constructor takes a host, port and a "trust any certificate" flag. This lets tests point at a local GreenMail server. It is never used against real Gmail.

### [AlertClassifier](AlertClassifier.java): "is this a paper alert?"

M1 scans the **whole inbox with no Gmail label or filter**, so this class is the only gate between every email the user has received and the pipeline.

**[isPublicationAlert(from, subject)](AlertClassifier.java#L128)** accepts a message if either of these holds:

1. **The sender's domain is trusted.** It is in [KNOWN_ALERT_SENDER_DOMAINS](AlertClassifier.java#L60) (Google Scholar, ScienceDirect/Elsevier, Springer/SpringerOpen, Taylor & Francis, ASCE, IEEE, ACM, Wiley, Nature, arXiv, …) or in the extra domains passed to the constructor. This check rarely gives false positives.
2. **The subject contains an alert phrase** from [SUBJECT_KEYWORDS](AlertClassifier.java#L81), such as "new citations", "table of contents", "new issue alert", "just accepted" or "recommended for you". This catches alerts from publishers that aren't on the list.

**[isPublicationAlert(from, subject, plainTextBody)](AlertClassifier.java#L148)** adds support for **forwarded alerts**. A forwarded alert arrives from the person who forwarded it, so the sender check fails. When the direct check fails, [extractQuotedHeaders](AlertClassifier.java#L168) looks through the body:

- it removes `>` / `&gt;` quote markers from each line;
- it finds every quoted `From:` line and looks up to 4 lines below it, stopping at a blank line, for that block's `Subject:`. The 4-line window covers both Gmail's order (From/Date/Subject/To) and Outlook's (From/Sent/To/Subject);
- it returns **every** quoted block, outermost first. In a chain forwarded several times, the publisher's own headers are usually the innermost block.

Each quoted sender and subject pair is then checked by the same two rules. Blocks are found by the header lines themselves, not by a particular mail client's "Forwarded message" banner, so forwards from Gmail, Apple Mail and Outlook, including multi-step chains, all work.

To support a new mailing list, add its domain or subject wording to the two sets instead of adding new logic.

## How it's tested

Tests use the **six real alert emails** the client forwarded (SpringerOpen, Taylor & Francis, Google Scholar, ScienceDirect, ASCE). All six arrive from the forwarder, not the publisher, which is exactly the case the classifier exists for.

| Test class | What it covers |
|---|---|
| [AlertClassifierTest](../../../../../../test/java/com/litroute/ingestion/email/AlertClassifierTest.java) | Every sample is recognized as it arrived, after the forwarder retitles it, and after it is forwarded a second time. Real alert subjects are recognized from publishers not on the domain list. Each sample's publisher is recognized by its sending domain. Ordinary mail is rejected, including when forwarded at any depth. The two-argument check never reads a body. Quoted blocks from other mail clients and behind quote markers still count. Extra allowed domains are honoured. |
| [GmailEmailSourceTest](../../../../../../test/java/com/litroute/ingestion/email/GmailEmailSourceTest.java) | Against an embedded GreenMail IMAP server carrying the six real alerts as Gmail delivers them (multipart/alternative): all six are fetched with the bodies M2 needs. An already processed alert is skipped. A direct alert from a publisher is fetched. Ordinary mail and alert-free forwards are ignored. Rejected credentials and an unreachable server give an empty list, not an exception. |
