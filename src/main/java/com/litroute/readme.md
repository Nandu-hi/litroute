# `com.litroute`: architecture overview

LitRoute reads the professor's publication-alert emails, matches each new paper to the one student it suits (if any), lets the professor approve, drop or move each match, and emails the approved papers, with their PDFs, to the students. User documentation is in the [top-level README](../../../../../README.md).

```
student folders ─M5─▶ M7d profiles.json ──────────────────────────────┐
                                                                      ▼
Gmail ─M1─▶ RawEmail ─M2─▶ PaperCandidate ─M3─▶ +DOI, abstract, PDF ─M4─▶ M7c ─M6─▶ ScoredPaper
  ▲                                                                                  │
  │ mark read (after M7a is saved)                    M9 queues ▼
  │                                     M7e scored_queue.json + matched/<student>/
  │                                                   │ M8 Review: approve / drop / move
  └──── Gmail SMTP ◀── M10 ApprovedPaperSender ◀──────┘ APPROVED ──▶ sent_papers.json
```

| | Package | Role |
|---|---|---|
| M1 | [ingestion/email](ingestion/email/readme.md) | Fetch unread publication alerts over IMAP |
| M2 | [ingestion/extract](ingestion/extract/readme.md) | Read the papers out of each alert (pattern matching or the local LLM); run a batch through M2–M4 |
| M3 | [ingestion/enrich](ingestion/enrich/readme.md) | DOI, venue, year and abstract from the publisher page, Crossref and OpenAlex; download the PDF |
| M4 | [ingestion/dedup](ingestion/dedup/readme.md) | Merge copies of the same paper |
| M5 | [profile](profile/readme.md) | Student folder of PDFs → profile |
| M6 | [scoring](scoring/readme.md) | Match each paper to at most one student (SPECTER2 + TF-IDF) |
| M7 | [store](store/readme.md) | Atomic JSON stores under `~/.litroute/data/` |
| M8 | [ui](ui/readme.md) | JavaFX app: Review, Pipeline, Authors, Profiles, Caches, Gmail tabs |
| M9 | [pipeline](pipeline/readme.md) | One full run; Clear and re-run; the authors list |
| M10 | [delivery](delivery/readme.md) | Email approved papers over SMTP, never twice |
| — | [model](model/readme.md), [embedding](embedding/readme.md), [security](security/readme.md), [util](util/readme.md) | Records; SPECTER2 via ONNX; encrypted credentials; helpers |

## Entry points

| Class | Starts |
|---|---|
| [ui/LitRouteApp](ui/LitRouteApp.java) | The window: `mvn -q compile javafx:run` |
| [ui/Launcher](ui/Launcher.java) | The same window, from a plain classpath (IDE, `exec:java`) |
| [pipeline/PipelineRunnerCli](pipeline/PipelineRunnerCli.java) | One pipeline run, optionally followed by sending (cron) |
| [profile/ProfileBuilderCli](profile/ProfileBuilderCli.java) | Profile sync and fit report |
| [scoring/RelevanceScorerCli](scoring/RelevanceScorerCli.java) | M6 on M7c, or on given PDFs |
| [security/CredentialSetupCli](security/CredentialSetupCli.java) | Set or rotate the App Password (needs a real terminal) |

[App.java](App.java) is the Maven archetype's unused "Hello World"; nothing calls it.

## Who touches which store

| | M7a emails | M7c papers | M7d profiles | M7e queue | sent | authors |
|---|---|---|---|---|---|---|
| M1 | skips its IDs | | | | | |
| M2–M4 | writes last | reads, writes | | | | |
| M5 | | | reads, writes | | | |
| M6 | | input | reads | output | | reads |
| M8 | counts | | shows | sets status, moves | shows | edits |
| M9 | reads, clears | reads, clears | reads | adds, clears | reads | |
| M10 | | | addresses | reads, removes | adds | |

Every write is atomic, and each module orders its writes so that a crash repeats work rather than losing it ([store readme](store/readme.md)).
