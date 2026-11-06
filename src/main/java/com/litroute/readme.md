# `com.litroute`: architecture

```
student folders ─M5─▶ M7d profiles ─────────────────────────────────────────┐
                                                                            ▼
Gmail ─M1─▶ RawEmail ─M2─▶ PaperCandidate ─M3─▶ +DOI, abstract, PDF ─M4─▶ M7c ─M6─▶ ScoredPaper
  ▲  mark read after M7a is saved                                                      │ M9
  │                                         M7e review queue ◀─────────────────────────┘
  └── Gmail SMTP ◀── M10 ◀── APPROVED ◀── M8 Review: approve / drop / move
```

| | Package | Role |
|---|---|---|
| M1 | [ingestion/email](ingestion/email/readme.md) | Fetch unread alerts over IMAP |
| M2 | [ingestion/extract](ingestion/extract/readme.md) | Read papers out of each alert; run a batch through M2–M4 |
| M3 | [ingestion/enrich](ingestion/enrich/readme.md) | Fill in DOI, venue, year, abstract; download the PDF |
| M4 | [ingestion/dedup](ingestion/dedup/readme.md) | Merge copies of one paper |
| M5 | [profile](profile/readme.md) | Student folder of PDFs → profile |
| M6 | [scoring](scoring/readme.md) | Match each paper to at most one student |
| M7 | [store](store/readme.md) | Atomic JSON stores under `~/.litroute/data/` |
| M8 | [ui](ui/readme.md) | JavaFX window |
| M9 | [pipeline](pipeline/readme.md) | One full run; Clear and re-run; authors list |
| M10 | [delivery](delivery/readme.md) | Email approved papers, never twice |
| | [model](model/readme.md), [embedding](embedding/readme.md), [security](security/readme.md), [util](util/readme.md) | Records, SPECTER2, encrypted credentials, helpers |

## Entry points

| Class | Starts |
|---|---|
| [ui/LitRouteApp](ui/LitRouteApp.java) / [ui/Launcher](ui/Launcher.java) | The window (`mvn javafx:run` / plain classpath) |
| [pipeline/PipelineRunnerCli](pipeline/PipelineRunnerCli.java) | One run, optionally followed by sending (cron) |
| [profile/ProfileBuilderCli](profile/ProfileBuilderCli.java) | Profile sync and fit report |
| [scoring/RelevanceScorerCli](scoring/RelevanceScorerCli.java) | M6 on M7c, or on given PDFs |
| [security/CredentialSetupCli](security/CredentialSetupCli.java) | Set or rotate the App Password |

## Who touches which store

| | M7a emails | M7c papers | M7d profiles | M7e queue | sent | authors |
|---|---|---|---|---|---|---|
| M1 | skips its IDs | | | | | |
| M2–M4 | writes last | reads, writes | | | | |
| M5 | | | reads, writes | | | |
| M6 | | input | reads | output | | reads |
| M8 | counts | | shows | status, move | shows | edits |
| M9 | reads, clears | reads, clears | reads | adds, clears | reads | |
| M10 | | | addresses | reads, removes | adds | |

Every write is atomic, and writes are ordered so a crash repeats work instead of losing it ([store](store/readme.md)).
