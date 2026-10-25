# The `email` module (M1)

**In one sentence:** it logs in to Gmail over IMAP, looks at the newest unread messages in the inbox (at most 50 per run by default), keeps only messages that look like publication or citation alerts and haven't been processed yet, and returns each one as a [RawEmail](../../model/RawEmail.java) for M2 to parse.

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
   │     open INBOX read-only; bodies fetched with BODY.PEEK, so mail stays unread
   │     pick the newest maxUnread unread messages not yet in M7a (Message-ID header only)
   │
   │  for each chosen message, oldest first:
   │     ├─ no Message-ID, or already in M7a?  ─▶ skip
   │     ├─ read From, Subject; walk the MIME tree for HTML + plain-text bodies
   │     ├─ AlertClassifier.isPublicationAlert(from, subject, plainText)
   │     │        └─ false ─▶ skip
   │     ├─ no readable body?                   ─▶ skip (logged)
   │     └─ RawEmail(messageId, subject, from, receivedAt, html, plainText)
   │
   ▼  close INBOX and connection; wipe the app password from memory
List<RawEmail> ──▶ M2 AlertBatchProcessor / PaperExtractor ──▶ M7c, then M7a saved
                                                                   │
   ┌───────────────────────────────────────────────────────────────┘
   ▼  GmailEmailSource.markRead(this run's Message-IDs)
         open INBOX read-write; set \Seen on those unread messages only
```

**Alerts are marked read only after M7a holds them.** The fetch looks at unread mail only, so an alert marked read earlier and then lost to a crash before M7a was saved would never be fetched again. Mail that is not an alert is never marked. For testing, M9 can skip marking altogether (`PipelineRunnerCli --keep-unread`, or **Keep emails unread** in the UI's pipeline tab); M7a still keeps those alerts from being processed twice.

## Interaction with M7 (the stores)

M1 never opens a store itself; M9 hands it what it needs from **M7a** and calls it in the order that keeps M7a and Gmail consistent.

| Call | M7a's part |
|---|---|
| `fetchNewAlerts(credentials, ids)` | `ids` is M7a's set; those messages are skipped by their `Message-ID` header without being downloaded, and don't count against `maxUnread`. |
| `markRead(credentials, ids)` | called only after [AlertBatchProcessor](../extract/AlertBatchProcessor.java) has saved this batch's IDs to M7a. |
| `markUnread(credentials, ids)` | `ids` is everything in M7a; called by **Clear and re-run** before M7a is emptied. If it throws, M9 clears nothing. |

Each [RawEmail](../../model/RawEmail.java)'s `messageId` is what M7a stores, and what each of its papers carries as `sourceMessageId` into M7c.

### [EmailSource](EmailSource.java): the interface

It has three methods. `fetchNewAlerts(credentials, alreadyProcessedMessageIds)` returns new alert emails oldest first. `markRead(credentials, messageIds)` marks the given messages read and returns how many it marked; M9 calls it once the batch is recorded in M7a. `markUnread(credentials, messageIds)` makes them unread again, read or not, and returns how many it found in the inbox; M9's **Clear and re-run** calls it before forgetting what was processed. The contract requires **failing gracefully**: problems are logged and give an empty list (or 0 marked), never an exception. The exception is `markUnread`, which throws `IOException`, because its caller must not forget mail that is still marked read.

### [GmailEmailSource](GmailEmailSource.java): the IMAP implementation

- **[fetchNewAlerts](GmailEmailSource.java#L130)** opens a Jakarta Mail `imaps` session, connects with the address and app password, reads the inbox, and always closes the connection. In a `finally` block it overwrites the copy of the app password with `'\0'`, so the secret doesn't stay in memory. A `MessagingException` is logged and gives `List.of()`.
- **[readInbox](GmailEmailSource.java#L280)** opens `INBOX` **read-only** and asks only for **unread** messages. Bodies are fetched with `BODY.PEEK` (`mail.imaps.peek`), so nothing is marked as read even on a server that ignores read-only mode. It reads just the `Message-ID` header of each unread message, then walks them newest first and keeps up to `maxUnread` (default [DEFAULT_MAX_UNREAD](GmailEmailSource.java) = 50; the UI and `PipelineRunnerCli --max-unread` set it) that are not yet in M7a. Messages already processed don't count against the limit, so a larger backlog is worked through over several runs. Only the chosen messages are downloaded in full, and they are converted oldest first.
- **[toRawEmailIfNewAlert](GmailEmailSource.java#L313)** is the filter for one message. It skips messages with no `Message-ID` or that are already in M7a, extracts the bodies, asks the classifier, and skips messages with no readable body. `receivedAt` falls back to "now" if the server gives no date. An unreadable message is logged and skipped, so it can't stop the rest of the batch.
- **[markUnread](GmailEmailSource.java)** opens `INBOX` **read-write**, lets the server find the messages by their `Message-ID` header (an IMAP `HEADER` search, 50 IDs per command) and clears `\Seen` on the exact matches. A message no longer in the inbox is skipped and not counted. A `MessagingException` is thrown as an `IOException`, and M9 then clears nothing.
- **[markRead](GmailEmailSource.java)** opens `INBOX` **read-write**, reads the `Message-ID` header of each unread message, and sets `\Seen` on those in the list in one command. Anything else, including unread mail that isn't an alert, is left as it was. A `MessagingException` is logged and gives 0; the alerts then stay unread but are still skipped through M7a.
- **[extractBodies](GmailEmailSource.java#L391)** walks the MIME structure recursively. A `text/html` part fills `html`, a `text/plain` part fills `plainText`, and `multipart/*` sections are combined, keeping the **first** HTML part and the **first** plain-text part found.
- **The bodies are read before classification.** A forwarded alert can only be recognized from the original headers quoted in its plain-text body, so classification can't rely on the envelope alone.
- A package-private constructor takes a host, port and a "trust any certificate" flag. This lets tests point at a local GreenMail server. It is never used against real Gmail.

### [AlertClassifier](AlertClassifier.java): "is this a paper alert?"

M1 scans the inbox's **unread mail with no Gmail label or filter**, so this class is the only gate between every unread email the user has received and the pipeline.

**[isPublicationAlert(from, subject)](AlertClassifier.java#L154)** accepts a message if either of these holds:

1. **The sender is trusted.** Its domain is in [KNOWN_ALERT_SENDER_DOMAINS](AlertClassifier.java) (ScienceDirect/Elsevier, Springer/SpringerOpen, Taylor & Francis, ASCE, IEEE, ACM, Wiley, Nature, arXiv, …) or in the extra domains passed to the constructor, or its whole address is in [KNOWN_ALERT_SENDER_ADDRESSES](AlertClassifier.java). The address list is for domains that also send other mail: `google.com` sends sign-in alerts and Workspace/Cloud notices too, so only Google Scholar's `scholaralerts-noreply@google.com` is trusted, not the domain. (Trusting all of `google.com` once let eight Google account and product emails through as alerts.)
2. **The subject contains an alert phrase** from [SUBJECT_KEYWORDS](AlertClassifier.java#L93), such as "new citations", "table of contents", "new issue alert", "just accepted" or "recommended for you". This catches alerts from publishers that aren't on the list.

**[isPublicationAlert(from, subject, plainTextBody)](AlertClassifier.java#L179)** adds support for **forwarded alerts**. A forwarded alert arrives from the person who forwarded it, so the sender check fails. When the direct check fails, [extractQuotedHeaders](AlertClassifier.java#L201) looks through the body:

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
| [GmailEmailSourceTest](../../../../../../test/java/com/litroute/ingestion/email/GmailEmailSourceTest.java) | Against an embedded GreenMail IMAP server carrying the six real alerts as Gmail delivers them (multipart/alternative): all six are fetched with the bodies M2 needs. An already processed alert is skipped. A direct alert from a publisher is fetched. Ordinary mail and alert-free forwards are ignored. Rejected credentials and an unreachable server give an empty list, not an exception. Fetching leaves mail unread; `markRead` marks just the fetched alerts read, leaving other unread mail alone, after which they are not fetched again; with rejected credentials it marks nothing and does not throw. |
