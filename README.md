# LitRoute

LitRoute reads a professor's publication-alert emails (Google Scholar, publisher and journal alerts), picks the one student each new paper suits, and, once the professor approves, emails the paper and its PDF to that student.

Everything runs locally. Papers are read from the emails by pattern matching, or optionally by a local LLM (**llama3.2:3b** on [Ollama](https://ollama.com)). They are matched with **SPECTER2** (via ONNX Runtime) plus word overlap. The Gmail App Password is stored encrypted under a master passphrase.

## How it works

```
student folders (~/.litroute/profiles/<student>/*.pdf + email.txt) ─M5─▶ profiles
Gmail ─M1─▶ alerts ─M2─▶ papers ─M3─▶ + DOI, abstract, PDF ─M4─▶ deduplicated
      ─M6─▶ at most one student per paper ─M7─▶ review queue ─M8─▶ approve / drop / move
      ─M10─▶ email approved papers + PDFs to the students
```

| | Package | Does |
|---|---|---|
| M1 | `ingestion/email` | Fetch unread alerts over IMAP |
| M2 | `ingestion/extract` | Read papers out of each alert |
| M3 | `ingestion/enrich` | DOI, venue, year, abstract and PDF from the publisher, Crossref and OpenAlex |
| M4 | `ingestion/dedup` | Merge copies of the same paper |
| M5 | `profile` | Student's folder of PDFs → profile |
| M6 | `scoring` | Match a paper only if it is close to one student's work (relevance ≥ 0.85) |
| M7 | `store` | Atomic JSON stores; a crash repeats work, never loses it |
| M8 | `ui` | JavaFX app |
| M9 | `pipeline` | One full run; Clear and re-run |
| M10 | `delivery` | Gmail SMTP; never sends a paper twice |

Each package has a `readme.md`; [the architecture overview](src/main/java/com/litroute/readme.md) links them.

## Requirements

- JDK 17+ (21 recommended) and Maven 3.6+
- Python 3.9+ with `venv`, once, to export SPECTER2 to ONNX
- Gmail with 2-Step Verification (for the App Password)
- Optional: Ollama with `llama3.2:3b`

Linux, macOS or Windows 10/11 (minimal Linux installs need GTK 3). No GPU needed.

## Install

```bash
git clone <repository-url> litroute && cd litroute
./install.sh       # Linux / macOS / WSL
install.cmd        # Windows
```

The installer can be re-run safely. It offers to install a missing JDK, Maven or Python, compiles, exports SPECTER2 to `~/.litroute/models`, offers `ollama pull llama3.2:3b`, and creates `~/.litroute/profiles` and `~/.litroute/data`. Flags: `--yes`, `--skip-model`, `--skip-build`, `--credentials` (`-Yes`, `-SkipModel`, … on Windows).

<details><summary>Manual install</summary>

```bash
mvn -DskipTests compile
scripts/model-export/setup_model.sh      # → ~/.litroute/models
ollama pull llama3.2:3b                  # optional
mkdir -p ~/.litroute/profiles ~/.litroute/data
```
</details>

## Configure

1. **Gmail:** create an App Password (Google Account → Security → App passwords) and enable IMAP. Enter it in the app's **Gmail** tab, or run `mvn -q compile exec:java -Dexec.mainClass=com.litroute.security.CredentialSetupCli`. It is saved with AES-256-GCM in `~/.litroute/credentials.enc`. A lost passphrase can't be recovered; run the setup again.
2. **Students:** one folder per student, named after them:
   ```
   ~/.litroute/profiles/alice/
   ├── email.txt     alice@university.edu   (one or more; # comments)
   └── *.pdf         their own papers and the closest papers to them (subfolders too)
   ```
   Unreadable PDFs are skipped and reported. Without `email.txt` a student is matched but can't be sent papers.
3. **Reading mode** (Pipeline tab or `--extraction=`): *pattern matching* (default, instant, 26/26 papers on the sample alerts) or *local AI model* (Ollama, 10–20 s per email, 25/26). Settings: `LITROUTE_OLLAMA_MODEL`, `LITROUTE_OLLAMA_URL` (local addresses only). Prompt: [prompts.txt](src/main/resources/prompts.txt).

## Use

```bash
mvn -q compile javafx:run
```

1. **Gmail:** unlock with the passphrase.
2. **Pipeline:** **Run pipeline** (sync profiles → fetch → extract, enrich, dedup → mark read → match). **Clear and re-run…** marks the alerts unread and empties the queue. Sent papers are kept.
3. **Review:** **Approve** (A), **Drop** (D), **Back to pending** (P), **Move** (M) to another student. **Send approved** emails each student one digest.
4. **Authors:** listed authors' papers need relevance 0.50 instead of 0.85. Names match strictly: Alice Carter matches Alice K. Carter, not A. Carter.
5. **Profiles** and **Caches:** rebuild profiles; inspect and clear caches.

Command line (cron reads the passphrase from `LITROUTE_PASSPHRASE`):

```bash
mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli \
    -Dexec.args="--extraction=pattern|llm --max-unread=50 --keep-unread --send-approved"
```

Also: `profile.ProfileBuilderCli [DIR]` (profile report) and `scoring.RelevanceScorerCli [PDF_OR_FOLDER]` (score PDFs). In PowerShell, quote each `-D` argument.

## Files (`~/.litroute/`)

| Path | Contents |
|---|---|
| `models/` | SPECTER2 model and tokenizer |
| `profiles/<student>/` | reference PDFs + `email.txt` (**you edit this**) |
| `credentials.enc` | encrypted Gmail credentials |
| `data/` | the M7 stores (processed emails, papers, profiles, queue, sent papers, authors), `matched/` PDF copies, downloaded `pdfs/` |

Use **Clear and re-run…** or the Caches tab, not `rm -r data/`: deleting the sent-paper record allows resends.

## Development

```bash
mvn test                  # all tests, several minutes (real SPECTER2 model + ~/.litroute/profiles)
make RelevanceScorerTest  # one class
mvn verify                # tests + coverage report: target/site/jacoco/index.html
mvn javadoc:javadoc       # fails on any undocumented public API
```

The tests use no network and no running LLM: they replay real alerts and recorded HTTP and Ollama replies, with GreenMail standing in for Gmail. Coverage is a baseline for finding untested branches, not a target. Matching quality is measured by the [benchmark](benchmark/README.md).

## Troubleshooting

| Problem | Fix |
|---|---|
| `SPECTER2 model not found` | `scripts/model-export/setup_model.sh` |
| `JavaFX runtime components are missing` | Use `mvn javafx:run` or `Launcher` |
| Window doesn't open on Linux | Install GTK 3 |
| Windows: `onnxruntime.dll` fails to load | `winget install Microsoft.VCRedist.2015+.x64` |
| Gmail login rejected | Use an App Password; check 2-Step Verification and IMAP |
| A run finds no alerts | Only unread, unprocessed alerts are fetched; use **Clear and re-run…** |
| `Ollama is not running` | `ollama serve`, or switch to pattern matching |
| A student gets no emails | Check `email.txt`, then rebuild the profiles |
