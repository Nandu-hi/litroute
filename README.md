# LitRoute

LitRoute reads a professor's publication-alert emails (Google Scholar, publisher and journal alerts) and works out which student each new paper suits. The professor approves or drops each match, and LitRoute emails the approved papers, with their PDFs, to the students.

It runs on the professor's own machine. Papers are matched with **SPECTER2**, a scientific-paper embedding model run locally through ONNX Runtime, combined with word overlap. An optional second matcher asks a language model on **Groq** instead. The Gmail App Password is stored encrypted under a master passphrase.

---

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
  - [Quick install](#quick-install)
  - [Manual installation](#manual-installation)
- [Configuration](#configuration)
  - [Gmail App Password](#1-gmail-app-password)
  - [Student profiles](#2-student-profiles)
  - [AI matcher (optional)](#3-ai-matcher-optional)
- [Usage](#usage)
  - [Desktop app](#desktop-app)
  - [Command line](#command-line)
  - [Running on a schedule](#running-on-a-schedule)
- [Where LitRoute keeps its files](#where-litroute-keeps-its-files)
- [Project layout](#project-layout)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)

---

## How it works

```
 student folders (~/.litroute/profiles/<student>/*.pdf + email.txt)
                │  M5  build profiles (SPECTER2 vectors + text)
                ▼
 Gmail ─M1─▶ alert emails ─M2─▶ papers ─M3─▶ + DOI, abstract, PDF ─M4─▶ deduplicated papers
                                                                          │
                                         M6  match each paper to the one student it suits
                                                                          ▼
                                         M7e review queue  ──M8──▶  professor approves / drops
                                                                          │
                                         M10 email approved papers + PDFs to the students
```

| Module | Package | What it does |
|---|---|---|
| M1 | `ingestion/email` | Fetches unread publication alerts from Gmail over IMAP. |
| M2 | `ingestion/extract` | Reads the papers out of each alert's HTML. |
| M3 | `ingestion/enrich` | Fills in DOI, venue, year and abstract from the publisher's page, Crossref and OpenAlex, and downloads the PDF. |
| M4 | `ingestion/dedup` | Merges copies of the same paper. |
| M5 | `profile` | Turns each student's folder of PDFs into a profile. |
| M6 | `scoring`, `scoring/ai` | Matches each paper to at most one student, and only when it is close to that student's work (relevance ≥ 0.7). Alternatively, matches with a Groq language model. |
| M7 | `store` | JSON stores that keep the pipeline's progress between runs. Every write is atomic, so a crash makes the next run repeat work instead of losing it. |
| M8 | `ui` | JavaFX desktop app with Review, Pipeline, Profiles and Gmail tabs. |
| M9 | `pipeline` | Runs the whole pipeline once, and provides **Clear and re-run**. |
| M10 | `delivery` | Emails the approved papers to their students over Gmail SMTP and never sends a paper twice. |

Each package has its own `readme.md` with the details. [src/main/java/com/litroute/readme.md](src/main/java/com/litroute/readme.md) is the overview.

---

## Requirements

| | Version | Needed for |
|---|---|---|
| **JDK** | 17 or newer (21 recommended) | Building and running LitRoute |
| **Apache Maven** | 3.6 or newer | Building, dependencies, running |
| **Python 3** with `venv` | 3.9 or newer | **Once only**, to export the SPECTER2 model to ONNX. It is not used at runtime. |
| **Gmail account** | 2-Step Verification on | Reading alerts (IMAP) and sending papers (SMTP) with an App Password |
| **Groq API key** | optional | Only for the AI matcher |

Other requirements:

- **OS:** Linux, macOS or Windows 10/11. Maven downloads JavaFX and ONNX Runtime for your platform automatically. On a minimal Linux install, JavaFX also needs GTK 3 (`libgtk-3-0` / `gtk3`).
- **Disk:** about 3 GB for the one-time model export (PyTorch CPU + model weights) and about 500 MB for the installed model.
- **Network:** for the install, Gmail, and paper lookups (Crossref, OpenAlex, publisher pages).

---

## Installation

### Quick install

**Linux / macOS (and WSL):**

```bash
git clone <repository-url> litroute
cd litroute
./install.sh
```

**Windows** (Command Prompt or PowerShell):

```bat
git clone <repository-url> litroute
cd litroute
install.cmd
```

`install.cmd` runs [install.ps1](install.ps1) without changing PowerShell's execution policy. From PowerShell you can also run `.\install.ps1` directly if your policy allows scripts.

Both installers are safe to re-run. They skip every step that is already done. They:

1. check for a JDK 17+, Maven and Python 3 and offer to install any that are missing:
   - Linux and macOS: apt, pacman, dnf, zypper or Homebrew;
   - Windows: winget for Temurin JDK 21 and Python 3.12. Maven is downloaded into `%LOCALAPPDATA%\Programs` and added to your user `PATH`;
2. compile the project, which downloads the Maven dependencies;
3. export SPECTER2 to `~/.litroute/models`, skipping this step if the model is already there;
4. create `~/.litroute/profiles` and `~/.litroute/data`;
5. with `--credentials` / `-Credentials`, set up the Gmail App Password.

| `install.sh` | `install.cmd` / `install.ps1` | Effect |
|---|---|---|
| `--yes` | `-Yes` | Install missing prerequisites without asking |
| `--skip-model` | `-SkipModel` | Skip the SPECTER2 export (e.g. you copied `~/.litroute/models` from another machine) |
| `--skip-build` | `-SkipBuild` | Skip the Maven build |
| `--credentials` | `-Credentials` | Set up the Gmail App Password at the end (needs a real terminal) |

Set `SPECTER2_OUT_DIR` to install the model somewhere other than `~/.litroute/models`. LitRoute itself always loads the model from `~/.litroute/models`.

> On Windows, `~` in this README means `%USERPROFILE%`, so the data lives in `C:\Users\<you>\.litroute`.
>
> `install.sh` is written for `bash`. From fish, zsh or another shell, run `./install.sh` or `bash install.sh`. On Windows, use `install.cmd` even from Git Bash.

### Manual installation

**1. Install the prerequisites.**

```bash
# Debian / Ubuntu
sudo apt install openjdk-21-jdk maven python3 python3-venv
# Arch
sudo pacman -S jdk21-openjdk maven python
# Fedora
sudo dnf install java-21-openjdk-devel maven python3
# macOS
brew install openjdk@21 maven python
```

On Windows, install the JDK and Python with winget, then get Maven from https://maven.apache.org/download.cgi. Unzip it and add its `bin` folder to `PATH`.

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
winget install Python.Python.3.12
```

Check them with `java -version` (17+) and `mvn -v`.

**2. Build.**

```bash
mvn -DskipTests compile
```

**3. Export the SPECTER2 model (once per machine).**

```bash
scripts/model-export/setup_model.sh
```

`setup_model.sh` is a bash script. On Windows, run `install.cmd -SkipBuild` instead, which does the same steps.

This script:

- creates a virtualenv in `scripts/model-export/.venv`;
- installs CPU-only PyTorch, `transformers` and `adapters`;
- merges `allenai/specter2_base` with the SPECTER2 *proximity* adapter;
- exports the result to ONNX and checks the export.

It writes these files to `~/.litroute/models`:

```
specter2.onnx  specter2.onnx.data  tokenizer.json  tokenizer_config.json  vocab.txt  special_tokens_map.json
```

You can delete the virtualenv afterwards. To use the model on another machine, copy `~/.litroute/models` there.

**4. Create the data folders.**

```bash
mkdir -p ~/.litroute/profiles ~/.litroute/data
```

On Windows (PowerShell):

```powershell
mkdir "$env:USERPROFILE\.litroute\profiles", "$env:USERPROFILE\.litroute\data"
```

Then continue with [Configuration](#configuration).

---

## Configuration

### 1. Gmail App Password

LitRoute signs in to the professor's Gmail with an **App Password**, not the account password.

1. Turn on 2-Step Verification for the Google account.
2. Go to **Google Account → Security → App passwords** and create one for "LitRoute".
3. Make sure IMAP is enabled in **Gmail → Settings → Forwarding and POP/IMAP**.
4. Store the App Password in LitRoute. Either:
   - open the app's **Gmail** tab and choose *Set up*, or
   - run this in a terminal:
     ```bash
     mvn -q compile exec:java -Dexec.mainClass=com.litroute.security.CredentialSetupCli
     ```

You will be asked for the Gmail address, the App Password and a new **master passphrase**. The credentials are encrypted with AES-256-GCM (PBKDF2, 210,000 rounds) into `~/.litroute/credentials.enc`. The passphrase is never saved. If you lose it, run the setup again.

### 2. Student profiles

Each student is a folder under `~/.litroute/profiles/`. The folder name is the student's name in LitRoute.

```
~/.litroute/profiles/
├── alice/
│   ├── email.txt          alice@university.edu
│   ├── thesis-ch2.pdf
│   └── refs/              subfolders are read too
│       └── smith2023.pdf
└── bob/
    ├── email.txt
    └── *.pdf
```

- **PDFs** are the student's reference papers: their own work and the papers closest to it. A few to a few dozen per student works well. Corrupt, encrypted or scanned (image-only) PDFs are skipped and reported.
- **`email.txt`** holds the address(es) approved papers go to. Put one or more per line, separated by commas, semicolons or spaces. Lines starting with `#` are ignored. A student with no address still gets matches but can't be sent papers.
- The folder is the source of truth. Adding, replacing or removing PDFs, or deleting a folder, updates the profiles at the next sync. Only new or changed PDFs are embedded again.

Profiles are synced at the start of every pipeline run, from **Profiles → Rebuild profiles** in the app, or with:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.litroute.profile.ProfileBuilderCli
```

### 3. AI matcher (optional)

The default matcher (*words + SPECTER2*) works offline. To use the Groq language-model matcher instead, set these environment variables **before** starting LitRoute:

| Variable | Required | Default |
|---|---|---|
| `GROQ_API_KEY` | yes | — (get one at https://console.groq.com) |
| `GROQ_MODEL` | no | `openai/gpt-oss-120b` |
| `GROQ_TOKENS_PER_MINUTE` | no | the free-tier limit; raise it on a paid plan so larger batches go in one request |

```bash
export GROQ_API_KEY=gsk_...              # bash / zsh
set -x GROQ_API_KEY gsk_...              # fish
$env:GROQ_API_KEY = "gsk_..."            # Windows PowerShell
set GROQ_API_KEY=gsk_...                 # Windows Command Prompt
```

LitRoute never writes the key to disk. The AI matcher sends each student's keywords and paper titles, and the new papers' titles and abstracts, to Groq.

---

## Usage

### Desktop app

```bash
mvn -q compile javafx:run
```

From an IDE, run `com.litroute.ui.Launcher`. Don't run `LitRouteApp` directly: the JVM refuses to start it from a plain classpath.

A typical session:

1. **Gmail tab:** unlock the credentials with the master passphrase.
2. **Profiles tab:** check that every student has a profile. **Rebuild profiles** after changing their folders. **Open profiles folder** opens `~/.litroute/profiles`.
3. **Pipeline tab:** choose the matcher and how many unread emails to examine, then **Run pipeline**. A live log shows each step:
   1. sync the profiles;
   2. fetch new alerts;
   3. extract, enrich and deduplicate the papers;
   4. mark the alerts read;
   5. match the papers and queue them.

   **Keep emails unread** is for testing. **Clear and re-run…** undoes every run so far: it marks the processed alerts unread again and empties the queue. The record of sent papers is kept.
4. **Review tab:** students are listed on the left and their matched papers in the middle, best first. Hover a score to see its breakdown and the details pane to see the rationale.
   - Press **Approve** (A), **Drop** (D) or **Back to pending** (P) to decide on the selected papers.
   - Press **Send approved** to email the approved papers to their students. Each student gets one digest with the PDFs attached, split into several emails if the attachments exceed Gmail's size limit.
   - Choose **Show: Sent** to see what has been sent and when.

### Command line

Every entry point runs through Maven's `exec:java`.

> **PowerShell:** quote every `-D` argument, e.g. `mvn -q compile exec:java "-Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli"`. Unquoted, PowerShell splits `-Dexec.mainClass=...` at the dot and Maven fails with *Unknown lifecycle phase*. Command Prompt and the bash examples below need no quotes.

```bash
# One pipeline run (asks for the master passphrase)
mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli

# ...with options
mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli \
    -Dexec.args="--matcher=ai --max-unread=100 --keep-unread --send-approved"
```

| `PipelineRunnerCli` option | Meaning |
|---|---|
| `--matcher=hybrid` \| `--matcher=ai` | Words + SPECTER2 (default) or the Groq model |
| `--max-unread=N` | Examine at most N unread emails (default 50) |
| `--keep-unread` | Leave the processed alerts unread in Gmail (for testing) |
| `--send-approved` | After the run, email the papers already approved in the app |

Other tools:

| Main class | What it does |
|---|---|
| `com.litroute.security.CredentialSetupCli` | Sets or changes the Gmail App Password. Needs a real terminal. |
| `com.litroute.profile.ProfileBuilderCli [PROFILES_DIR]` | Syncs the profiles and prints a report with each student's keywords and how similar the students are |
| `com.litroute.scoring.RelevanceScorerCli [PDF_OR_FOLDER]` | Matches the stored papers, or the PDFs you give it, against the profiles and prints the results |

### Running on a schedule

`PipelineRunnerCli` reads the passphrase from `LITROUTE_PASSPHRASE` when no terminal is attached. For example, a crontab entry that runs every weekday at 07:00:

```cron
0 7 * * 1-5  cd /path/to/litroute && LITROUTE_PASSPHRASE='...' mvn -q exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli >> ~/.litroute/cron.log 2>&1
```

Anyone who can read the crontab can read the passphrase in it. Prefer a file only you can read, e.g. `LITROUTE_PASSPHRASE="$(cat ~/.litroute/.pass)"` with `chmod 600`.

On Windows, use **Task Scheduler** instead. Create a task that runs `cmd.exe` with these arguments, replacing `C:\path\to\litroute` with your folder:

```bat
/c cd /d C:\path\to\litroute && mvn -q exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli >> %USERPROFILE%\.litroute\task.log 2>&1
```

Set `LITROUTE_PASSPHRASE` as a user environment variable (**Settings → System → About → Advanced system settings → Environment Variables**). Anyone signed in as you can read it.

---

## Where LitRoute keeps its files

Everything lives under `~/.litroute/`, outside the repository:

| Path | Contents |
|---|---|
| `models/` | The SPECTER2 ONNX model and tokenizer |
| `profiles/<student>/` | Each student's reference PDFs and `email.txt`. **You edit this.** |
| `credentials.enc` | The encrypted Gmail address and App Password |
| `data/email_cache.json` | Message-IDs of the alerts already processed (M7a) |
| `data/enrichment_cache.json` | Extracted and enriched papers, and the lookup cache (M7c) |
| `data/profiles.json` | The built profiles (M7d) |
| `data/scored_queue.json` | The review queue and the professor's decisions (M7e) |
| `data/matched/<student>/` | Each student's copies of their matched PDFs |
| `data/scored_papers.json` | Papers already scored, so they are not scored twice |
| `data/sent_papers.json` | Papers sent to each student, so they are never sent twice |
| `data/pdfs/` | PDFs downloaded during enrichment |

To start over, use **Clear and re-run…** in the app. Deleting `data/` loses the record of sent papers.

---

## Project layout

```
litroute/
├── install.sh                     one-step installer (Linux, macOS, WSL)
├── install.cmd, install.ps1       one-step installer (Windows)
├── pom.xml                        Maven build (Java 17, JavaFX 21, ONNX Runtime, DJL tokenizers, PDFBox, Jakarta Mail, Jackson, jsoup)
├── Makefile                       `make <TestClass>` runs one test class
├── scripts/model-export/          one-time SPECTER2 → ONNX export (Python)
├── m5plan.txt, m6plan.txt         design notes for profiles (M5) and matching (M6)
└── src/
    ├── main/java/com/litroute/
    │   ├── ingestion/{email,extract,enrich,dedup}   M1–M4
    │   ├── profile/                                 M5
    │   ├── scoring/, scoring/ai/                    M6
    │   ├── store/                                   M7
    │   ├── ui/                                      M8
    │   ├── pipeline/                                M9
    │   ├── delivery/                                M10
    │   ├── embedding/  model/  security/  util/
    │   └── readme.md                                architecture overview
    └── test/                                        JUnit 4 tests + recorded emails, HTTP and AI replies
```

---

## Testing

```bash
mvn test                       # the whole suite (several minutes)
make RelevanceScorerTest       # one test class
```

The tests use the alert emails stored in `src/test/resources` and recorded Crossref, OpenAlex, publisher and Groq replies, so they need no network. The profile and matching tests load the **real SPECTER2 model** and the profiles in `~/.litroute/profiles`, so install the model and add profiles first. The Gmail tests use an embedded GreenMail server.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `SPECTER2 model not found in … run scripts/model-export/setup_model.sh` | Run `scripts/model-export/setup_model.sh` or `./install.sh`. The model must be in `~/.litroute/models`. |
| `pip` can't find `torch` | Your Python is newer than PyTorch supports. Run the export with an older Python 3, e.g. `python3.12 -m venv scripts/model-export/.venv`, then re-run `setup_model.sh`. |
| `Error: JavaFX runtime components are missing` | Start the app with `mvn javafx:run`, or run `com.litroute.ui.Launcher` rather than `LitRouteApp`. |
| The window doesn't open on Linux | Install GTK 3 (`libgtk-3-0` / `gtk3`). |
| Windows: `install.ps1 cannot be loaded because running scripts is disabled` | Run `install.cmd` instead. It bypasses the execution policy for this one script. |
| Windows: `mvn`, `java` or `python` not found right after the installer added them | Open a new terminal window so it picks up the updated `PATH`, then re-run the installer. |
| Windows: `Unknown lifecycle phase ".mainClass=..."` | You're in PowerShell. Quote the argument: `"-Dexec.mainClass=..."`. |
| Windows: ONNX Runtime fails to load (`UnsatisfiedLinkError`, `onnxruntime.dll`) | Install the Microsoft Visual C++ Redistributable (x64): `winget install Microsoft.VCRedist.2015+.x64`. |
| Gmail login rejected | Use an **App Password**, not the account password. Check that 2-Step Verification and IMAP are on. |
| Wrong passphrase / can't decrypt | The passphrase can't be recovered. Set up the credentials again in the Gmail tab or with `CredentialSetupCli`. |
| `CredentialSetupCli` refuses to run | It needs a real terminal (not piped input or an IDE console), so the password is never echoed. Use the app's Gmail tab instead. |
| A run finds no alerts | Only **unread** inbox messages that look like publication or citation alerts are fetched, and processed ones are skipped. Use **Clear and re-run…** to process them again. |
| AI matcher unavailable | Set `GROQ_API_KEY` in the environment you start LitRoute from. On the free tier, large batches are split and rate-limited, so runs are slower. |
| A student gets no emails | Check that their folder has an `email.txt` with a valid address, then rebuild the profiles. |
