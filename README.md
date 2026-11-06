# LitRoute

LitRoute reads a professor's publication-alert emails (Google Scholar, publisher and journal alerts), works out which student each new paper suits, and, once the professor approves, emails the paper and its PDF to that student.

Everything runs on the professor's machine. Papers are read out of the emails by pattern matching, or optionally by a local LLM (**llama3.2:3b** on [Ollama](https://ollama.com)). They are matched with **SPECTER2** (a scientific-paper embedding model, run locally via ONNX Runtime) plus word overlap. No email text leaves the machine. The Gmail App Password is stored encrypted under a master passphrase.

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
| M2 | `ingestion/extract` | Read papers out of each alert (pattern matching or local LLM) |
| M3 | `ingestion/enrich` | DOI, venue, year and abstract from the publisher page, Crossref and OpenAlex; download the PDF |
| M4 | `ingestion/dedup` | Merge copies of the same paper |
| M5 | `profile` | Each student's folder of PDFs → a profile |
| M6 | `scoring` | Match a paper only when it is close to one student's work (relevance ≥ 0.85, or lower for a listed author) |
| M7 | `store` | Atomic JSON stores; a crash repeats work instead of losing it |
| M8 | `ui` | JavaFX app: Review, Pipeline, Authors, Profiles, Caches, Gmail |
| M9 | `pipeline` | One full run; **Clear and re-run** |
| M10 | `delivery` | Gmail SMTP; never sends a paper twice |

Each package has a short `readme.md`; [src/main/java/com/litroute/readme.md](src/main/java/com/litroute/readme.md) is the architecture overview.

## Requirements

| | Version | For |
|---|---|---|
| JDK | 17+ (21 recommended) | building and running |
| Maven | 3.6+ | building and running |
| Python 3 + `venv` | 3.9+ | **one-time** SPECTER2 → ONNX export only |
| Gmail | 2-Step Verification on | IMAP + SMTP with an App Password |
| Ollama + `llama3.2:3b` | optional | the local-LLM reading mode |

Supported on Linux, macOS and Windows 10/11; minimal Linux installs also need GTK 3 for JavaFX. Disk: ~3 GB for the one-time export, ~500 MB for the model, ~2 GB for `llama3.2:3b`. No GPU is needed.

## Install

```bash
git clone <repository-url> litroute && cd litroute
./install.sh            # Linux / macOS / WSL (run with bash)
install.cmd             # Windows (wraps install.ps1, no policy change)
```

The installer is safe to re-run. It:

1. offers to install a missing JDK, Maven or Python (apt / pacman / dnf / zypper / Homebrew / winget);
2. compiles the project;
3. exports SPECTER2 to `~/.litroute/models`;
4. offers `ollama pull llama3.2:3b` if Ollama is installed;
5. creates `~/.litroute/profiles` and `~/.litroute/data`.

| `install.sh` | `install.ps1` | Effect |
|---|---|---|
| `--yes` | `-Yes` | Install prerequisites without asking |
| `--skip-model` | `-SkipModel` | Skip the export (e.g. you copied `~/.litroute/models` over) |
| `--skip-build` | `-SkipBuild` | Skip the Maven build |
| `--credentials` | `-Credentials` | Set up the Gmail App Password at the end |

<details><summary>Manual install</summary>

```bash
sudo apt install openjdk-21-jdk maven python3 python3-venv   # or pacman / dnf / brew equivalents
mvn -DskipTests compile
scripts/model-export/setup_model.sh      # venv → merge specter2_base + proximity adapter → ONNX → verify
ollama pull llama3.2:3b                  # optional
mkdir -p ~/.litroute/profiles ~/.litroute/data
```

`setup_model.sh` writes `specter2.onnx`, `specter2.onnx.data` and the tokenizer files to `~/.litroute/models` (or to `SPECTER2_OUT_DIR`; LitRoute itself always loads from `~/.litroute/models`). You can delete its venv afterwards. On Windows, run `install.cmd -SkipBuild` instead.
</details>

On Windows, `~` means `%USERPROFILE%`.

## Configure

**1. Gmail App Password.** Turn on 2-Step Verification, create an App Password (Google Account → Security → App passwords), and make sure IMAP is enabled. Then use the app's **Gmail** tab → *Set up*, or:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.litroute.security.CredentialSetupCli
```

The credentials are encrypted with AES-256-GCM (PBKDF2, 210,000 rounds) into `~/.litroute/credentials.enc`. The passphrase is never saved and can't be recovered; if you lose it, run the setup again.

**2. Student profiles.** Create one folder per student; the folder name is the student's name in LitRoute:

```
~/.litroute/profiles/alice/
├── email.txt        alice@university.edu   (one or more per line; # comments)
├── thesis-ch2.pdf   reference papers: their own work and the closest papers to it
└── refs/*.pdf       subfolders are read too
```

Unreadable PDFs (corrupt, encrypted, scanned) are skipped and reported. A student without `email.txt` is still matched but can't be sent papers. Profiles sync at the start of every run, and only new or changed PDFs are embedded.

**3. Reading mode** (Pipeline tab → *Read emails with*, or `--extraction=`):

| | Pattern matching (default) | Local AI model |
|---|---|---|
| Needs | nothing | `ollama serve` + `llama3.2:3b` |
| Speed per email | instant | 10–20 s (≈ 2 min for a long table of contents) |
| On the six real alerts | 26 / 26 papers | 25 / 26 papers |
| Strength | exact on known layouts | not tied to a layout |

If Ollama is down, the run fails and the alerts stay unprocessed. The model can only name titles that appear verbatim in the email. Environment variables: `LITROUTE_OLLAMA_MODEL` (default `llama3.2:3b`) and `LITROUTE_OLLAMA_URL` (default `http://localhost:11434`, local addresses only). The prompt is [src/main/resources/prompts.txt](src/main/resources/prompts.txt), with exactly one `{body}`; if you edit it, [re-record the test answers](src/main/java/com/litroute/ingestion/extract/readme.md#recording-the-test-answers).

## Use

```bash
mvn -q compile javafx:run         # or run com.litroute.ui.Launcher from an IDE
```

1. **Gmail:** unlock with the master passphrase.
2. **Profiles:** check every student is listed; use **Rebuild profiles** after changing a folder.
3. **Pipeline:** choose the reading mode and the max number of unread emails, then **Run pipeline**. The steps run in order: sync profiles → fetch → extract, enrich, dedup → mark read → match and queue. **Clear and re-run…** marks the processed alerts unread again and empties the queue; the sent-paper record is kept.
4. **Review:**
   - **Approve** (A), **Drop** (D), **Back to pending** (P).
   - **Move** (M) gives a paper to another student.
   - **Show: Dropped** also lists the papers no one matched, under their closest student, with the reason.
   - **Send approved** emails each student one digest, split if the PDFs exceed Gmail's size limit. **Show: Sent** lists what has gone.
- **Authors:** papers by listed authors need relevance 0.50 instead of 0.85 (0.36 instead of 0.60 from a title alone) and come first. Names are matched strictly: Alice Carter matches Alice K. Carter, but not A. Carter.
- **Caches:** see what each cache holds, and clear the ones you choose (you must tick that you accept the consequences first). Gmail is never touched.

### Command line

```bash
mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli \
    -Dexec.args="--extraction=llm --max-unread=100 --keep-unread --send-approved"
```

| Option | Meaning |
|---|---|
| `--extraction=pattern\|llm` | Reading mode (default `pattern`) |
| `--max-unread=N` | Examine at most N unread emails (default 50) |
| `--keep-unread` | Don't mark processed alerts read (testing) |
| `--send-approved` | Afterwards, send the papers already approved |

Other entry points: `com.litroute.profile.ProfileBuilderCli [DIR]` (sync and fit report) and `com.litroute.scoring.RelevanceScorerCli [PDF_OR_FOLDER]` (match M7c, or score the given PDFs).

**PowerShell:** quote every `-D` argument (`"-Dexec.mainClass=..."`).

**Scheduling:** with no terminal attached, `PipelineRunnerCli` reads `LITROUTE_PASSPHRASE`. Keep the passphrase in a `chmod 600` file rather than in the crontab:

```cron
0 7 * * 1-5  cd /path/to/litroute && LITROUTE_PASSPHRASE="$(cat ~/.litroute/.pass)" mvn -q exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli >> ~/.litroute/cron.log 2>&1
```

On Windows, use Task Scheduler with `cmd.exe /c cd /d C:\path\to\litroute && mvn -q exec:java ...` and a user environment variable.

## Files (`~/.litroute/`)

| Path | Contents |
|---|---|
| `models/` | SPECTER2 ONNX model and tokenizer |
| `profiles/<student>/` | reference PDFs + `email.txt` (**you edit this**) |
| `credentials.enc` | encrypted Gmail credentials |
| `data/email_cache.json` | processed alert Message-IDs (M7a) |
| `data/enrichment_cache.json` | extracted papers and the lookup cache (M7c) |
| `data/profiles.json` | built profiles (M7d) |
| `data/scored_queue.json`, `data/matched/<student>/` | review queue and decisions, the students' PDF copies (M7e) |
| `data/sent_papers.json` | papers already sent (never sent twice) |
| `data/priority_authors.json` | the Authors list |
| `data/pdfs/` | downloaded PDFs |

To start over, use **Clear and re-run…** rather than deleting `data/`, which would lose the sent-paper record.

## Testing

```bash
mvn test                    # whole suite, several minutes
make RelevanceScorerTest    # one class
```

The tests use no network and no running LLM: they replay real alert emails and recorded Crossref, OpenAlex, publisher and Ollama replies, and Gmail is simulated with GreenMail. The profile and matching tests load the **real SPECTER2 model** and `~/.litroute/profiles`, so install those first.

Live end-to-end run on saved `.eml` alerts:

```bash
mvn test -Dtest=EmlAlertsPipelineTest -Dlitroute.emlAlerts=<folder>     # → target/eml-alerts-match-report.txt
python3 benchmark/score.py benchmark/alert-papers-2026-11-05.json run=<its scored_queue.json>
```

The run's `scored_queue.json` is written to a `litroute-eml-pipeline…` folder in the system temp directory. Add `-Dlitroute.semantic=abstract -Dlitroute.testProfileCache=<empty dir>` to try SPECTER2 on title + abstract only. See the [benchmark](benchmark/README.md).

## Troubleshooting

| Problem | Fix |
|---|---|
| `SPECTER2 model not found` | Run `scripts/model-export/setup_model.sh` or `./install.sh` |
| `pip` can't find `torch` | Python is too new for PyTorch: `python3.12 -m venv scripts/model-export/.venv`, then re-run |
| `JavaFX runtime components are missing` | Use `mvn javafx:run` or `Launcher`, not `LitRouteApp` |
| Window doesn't open on Linux | Install GTK 3 |
| Windows: scripts disabled / `Unknown lifecycle phase` | Use `install.cmd`; quote `-D` arguments in PowerShell |
| Windows: tools not found right after install | Open a new terminal |
| Windows: `onnxruntime.dll` fails to load | `winget install Microsoft.VCRedist.2015+.x64` |
| Gmail login rejected | Use an App Password; check that 2-Step Verification and IMAP are on |
| Can't decrypt / forgot the passphrase | Set up the credentials again |
| `CredentialSetupCli` refuses to run | It needs a real terminal; use the Gmail tab instead |
| A run finds no alerts | Only unread, unprocessed alert emails are fetched; use **Clear and re-run…** |
| `Ollama is not running` | `ollama serve` and `ollama pull llama3.2:3b`, or switch to pattern matching |
| A student gets no emails | Check `email.txt`, then rebuild the profiles |
