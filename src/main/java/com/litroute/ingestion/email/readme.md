# `email` (M1): fetch alerts from Gmail

M1 logs in to `imaps://imap.gmail.com:993`. It looks at the newest unread inbox messages not yet in M7a (at most `maxUnread`, default 50), keeps the ones that look like publication alerts, and returns them oldest first as [RawEmail](../../model/RawEmail.java)s. It does no parsing; parsing is M2's job.

## Rules

- **Never marks mail read while fetching.** The inbox is opened read-only, and bodies are fetched with `BODY.PEEK`.
- **Read only after recording.** `markRead` is called by M9 only after M7a holds the batch, because a message marked read but never recorded would never be fetched again. **Keep emails unread** skips this step.
- **Already-processed mail is free.** Messages are skipped by their `Message-ID` header without being downloaded, and they don't count against `maxUnread`, so a backlog is worked through over several runs.
- **Graceful failure.** A rejected login or a dead connection is logged and gives an empty list, or 0 messages marked. `markUnread` (used by Clear and re-run) is the one exception: it throws, so M9 never forgets mail that is still marked read.
- The app password copy is wiped in a `finally` block.

## [AlertClassifier](AlertClassifier.java): is this a paper alert?

Nothing filters the inbox before LitRoute sees it, so this class is the only gate. A message passes if either:

1. **The sender is trusted:** its domain is in `KNOWN_ALERT_SENDER_DOMAINS` (Elsevier, Springer, T&F, ASCE, IEEE, ACM, Wiley, Nature, arXiv, …), or its exact address is in `KNOWN_ALERT_SENDER_ADDRESSES`. Address-only trust is for shared domains: Google Scholar's `scholaralerts-noreply@google.com` is trusted, but `google.com` as a whole is not.
2. **The subject has an alert phrase** (`SUBJECT_KEYWORDS`): "new citations", "table of contents", "new issue alert", "just accepted", and so on.

**Forwarded alerts:** if the direct check fails, every quoted `From:`/`Subject:` block in the plain-text body is checked by the same rules. Quote markers are stripped, the Gmail and Outlook header orders are both handled, and chains of any depth work.

To support a new mailing list, add its domain or subject phrase to these sets; no new logic is needed.

## Classes

| Class | Role |
|---|---|
| [EmailSource](EmailSource.java) | Interface: `fetchNewAlerts`, `markRead`, `markUnread` |
| [GmailEmailSource](GmailEmailSource.java) | The IMAP implementation. It walks the MIME tree for the first HTML part and the first plain-text part, and reads bodies before classifying, so forwards can be recognized. `markUnread` searches by header, 50 IDs per command. |
| [AlertClassifier](AlertClassifier.java) | The gate above |

**Tests:** the tests use the six real alerts the client forwarded. [AlertClassifierTest](../../../../../../test/java/com/litroute/ingestion/email/AlertClassifierTest.java) covers direct, forwarded and re-forwarded alerts, other mail clients' quoting, and ordinary mail being rejected. [GmailEmailSourceTest](../../../../../../test/java/com/litroute/ingestion/email/GmailEmailSourceTest.java) runs against an embedded GreenMail server: fetching, skipping already-processed mail, leaving mail unread, `markRead` touching only alerts, and bad credentials or host giving an empty list.
