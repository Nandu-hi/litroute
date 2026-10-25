# LitRoute: the `com.litroute` packages

**In one sentence:** LitRoute reads the professor's publication-alert emails, works out which student each new paper suits, lets the professor approve or drop each match, and emails the approved papers (with their PDFs) to the students.

This root package holds one file, [App.java](App.java): the "Hello World!" placeholder left by the Maven archetype. Nothing calls it, and it is not an entry point of LitRoute. The real entry points are:

| Entry point | Starts | How |
|---|---|---|
| [ui/LitRouteApp](ui/LitRouteApp.java) | the professor's window (M8) | `mvn -q compile javafx:run` (the pom's `javafx-maven-plugin` `mainClass`) |
| [ui/Launcher](ui/Launcher.java) | the same window, from a plain classpath | an IDE's Run, or `exec:java -Dexec.mainClass=com.litroute.ui.Launcher` |
| [pipeline/PipelineRunnerCli](pipeline/PipelineRunnerCli.java) | one pipeline run (M9), optionally sending approved papers (M10) | `exec:java`, e.g. from cron |
| [profile/ProfileBuilderCli](profile/ProfileBuilderCli.java) | a profile sync (M5) with a fit report | `exec:java` |
| [scoring/RelevanceScorerCli](scoring/RelevanceScorerCli.java) | M6 on M7c's papers, or on PDFs given by hand | `exec:java` |
| [security/CredentialSetupCli](security/CredentialSetupCli.java) | setting or rotating the Gmail App Password | `exec:java`, in a real terminal |

## The modules

| Module | Package | Readme | Role |
|---|---|---|---|
| M1 | [ingestion/email](ingestion/email/) | [readme](ingestion/email/readme.md) | Fetch unread publication alerts from Gmail over IMAP. |
| M2 | [ingestion/extract](ingestion/extract/) | [readme](ingestion/extract/readme.md) | Read the papers out of each alert's HTML; run a batch through M2–M4 and save it. |
| M3 | [ingestion/enrich](ingestion/enrich/) | [readme](ingestion/enrich/readme.md) | Fill in DOI, venue, year, abstract from the publisher's page, Crossref and OpenAlex; download the PDF. |
| M4 | [ingestion/dedup](ingestion/dedup/) | [readme](ingestion/dedup/readme.md) | Merge copies of the same paper. |
| M5 | [profile](profile/) | [readme](profile/readme.md) | Turn each student's folder of PDFs into a profile of SPECTER2 vectors and text. |
| M6 | [scoring](scoring/), [scoring/ai](scoring/ai/) | [readme](scoring/readme.md), [ai readme](scoring/ai/readme.md) | Match each paper to the student it suits: words + SPECTER2, or a language model on Groq. |
| M7 | [store](store/) | [readme](store/readme.md) | The JSON stores that hold the pipeline's progress between runs (M7a, c, d, e, plus the scored-paper log and the sent papers). |
| M8 | [ui](ui/) | [readme](ui/readme.md) | The JavaFX window: review, run, profiles, credentials. |
| M9 | [pipeline](pipeline/) | [readme](pipeline/readme.md) | One run of the whole pipeline, and Clear and re-run. |
| M10 | [delivery](delivery/) | [readme](delivery/readme.md) | Email the approved papers to their students over Gmail SMTP. |
| — | [model](model/) | [readme](model/readme.md) | The immutable records passed between stages. |
| — | [embedding](embedding/) | [readme](embedding/readme.md) | SPECTER2 run locally through ONNX Runtime. |
| — | [security](security/) | [readme](security/readme.md) | The Gmail App Password, encrypted at rest under a master passphrase. |
| — | [util](util/) | [readme](util/readme.md) | Atomic file writes, DOIs, title comparison, vector arithmetic. |

## How data flows end to end

```
                 student folders (~/.litroute/profiles/<student>/*.pdf, email.txt)
                                  │ M5 ProfileBuilder
                                  ▼
                           M7d profiles.json ─────────────────────────────┐
                                                                          │
Gmail ──M1──▶ RawEmail ──M2──▶ PaperCandidate ──M3──▶ (+DOI, PDF) ──M4──▶ M7c enrichment_cache.json
  ▲               │                                                       │
  │               └──── Message-IDs ──▶ M7a email_cache.json (saved last) │
  │ mark read                                                             ▼
  │                                                       M6 RelevanceScorer / AiPaperMatcher
  │                                                                       │ ScoredPaper
  │                                                                       ▼
  │                         scored_papers.json ◀── M9 ──▶ M7e scored_queue.json + matched/<student>/
  │                                                                       │
  │                                                     M8 ReviewPane: approve / drop
  │                                                                       │ APPROVED
  │                                                                       ▼
  └──────────────── Gmail SMTP ◀── M10 ApprovedPaperSender ──▶ sent_papers.json (never sent twice)
```

M9 ([PipelineRunner](pipeline/PipelineRunner.java)) runs M5 → M1 → M2–M4 → mark read → M6 → M7e in that order; M8 starts M9, M5 and M10 from its tabs.

## How the modules use M7

| | M7a emails | M7c papers | M7d profiles | M7e queue | scored log | sent papers |
|---|---|---|---|---|---|---|
| M1 | skips its IDs | | | | | |
| M2–M4 | writes last | reads, writes | | | | |
| M5 | | | reads, writes | | | |
| M6 | | input (via M9) | reads | output (via M9) | | |
| M8 | counts | | shows | shows, sets status | | shows |
| M9 | reads, clears | reads, clears | reads | adds, clears | reads, adds, clears | reads |
| M10 | | | reads addresses | reads, removes | | adds |

Every store write is atomic ([AtomicFiles](util/AtomicFiles.java)), and the write order in each module is chosen so that a crash repeats work instead of losing it. See the [store readme](store/readme.md) for the details.
