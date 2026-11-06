# `email` (M1): fetch alerts from Gmail

Logs in to `imaps://imap.gmail.com:993`, examines the newest unread inbox messages not in M7a (at most `maxUnread`, default 50), keeps the publication alerts and returns them oldest first as [RawEmail](../../model/RawEmail.java)s. It does no parsing.

- **Read-only fetch:** the inbox is opened read-only and bodies are fetched with `BODY.PEEK`.
- **Marked read only after recording:** M9 calls `markRead` once M7a holds the batch.
- **Processed mail is skipped by Message-ID** without downloading, and doesn't count toward `maxUnread`.
- **Failures** give an empty list (or 0 marked). `markUnread` (Clear and re-run) throws instead, so nothing is forgotten.

## [AlertClassifier](AlertClassifier.java)

The only gate on the inbox. A message passes if:
1. the sender's domain is in `KNOWN_ALERT_SENDER_DOMAINS` (Elsevier, Springer, ASCE, IEEE, Wiley, arXiv, …) or its address is in `KNOWN_ALERT_SENDER_ADDRESSES` (e.g. Scholar's, without trusting all of `google.com`); or
2. the subject has an alert phrase (`SUBJECT_KEYWORDS`: "new citations", "table of contents", …).

Forwarded alerts pass too: every quoted `From:`/`Subject:` block in the body is checked, to any depth. To support a new list, add its domain or phrase.

| Class | Role |
|---|---|
| [EmailSource](EmailSource.java) | `fetchNewAlerts`, `markRead`, `markUnread` |
| [GmailEmailSource](GmailEmailSource.java) | IMAP implementation; first HTML and first plain-text part |
| [AlertClassifier](AlertClassifier.java) | The gate |

**Tests:** [AlertClassifierTest](../../../../../../test/java/com/litroute/ingestion/email/AlertClassifierTest.java) (direct, forwarded, other clients, ordinary mail); [GmailEmailSourceTest](../../../../../../test/java/com/litroute/ingestion/email/GmailEmailSourceTest.java) against GreenMail.
