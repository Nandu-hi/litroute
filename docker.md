# Running LitRoute with Docker

LitRoute runs inside a Docker container. You use its window in your web browser, so you do not need to install Java, Maven or Python.

## What you need

| | Minimum | Recommended |
|---|---|---|
| Processor | 64-bit Intel or AMD | 4 or more cores, for the AI reading mode |
| Operating system | Windows 10/11, macOS or Linux | |
| Memory (RAM) | 8 GB, with 6 GB given to Docker | 16 GB |
| Free disk space | 25 GB (about 15 GB after setup) | 30 GB |
| Software | Docker Desktop (Windows/Mac) or Docker Engine (Linux), and a web browser | |
| Gmail | 2-Step Verification on, IMAP enabled | |
| Internet | Gmail's mail ports (993, 465/587) not blocked | |

- **Apple Silicon Macs (M1–M4)** run the image through emulation. It works, but slowly, especially the AI reading mode.
- **Windows:** Docker Desktop needs WSL 2 and virtualization enabled in the BIOS. Its installer guides you through both.
- Some university networks block mail ports. If yours does, LitRoute cannot read or send email there.

## 1. One-time setup

1. Install **Docker Desktop** (Windows/Mac) or Docker Engine (Linux) and start it. In Docker Desktop, set the memory to at least 6 GB (Settings → Resources).
2. Load the image file you were given. This takes a few minutes:
   ```
   docker load < litroute.tar.gz
   ```
   (Windows PowerShell: `docker load -i litroute.tar.gz`)

   Once it says `Loaded image: litroute:latest`, you can delete the `.tar.gz` file.
3. Make an empty folder on your computer called `litroute-profiles`. Your students come built into LitRoute: on the first start, it fills this folder with one sub-folder per student, holding their PDFs and `email.txt`, with their profiles already built. After that it is an ordinary folder you can edit:
   ```
   litroute-profiles/
   ├── alice/
   │   ├── email.txt      alice@university.edu   (one address per line; # for comments)
   │   └── *.pdf          her own papers and the papers closest to her work
   └── bob/
       ├── email.txt
       └── *.pdf
   ```
   **After the first start, add each student's email address** to the `email.txt` in their folder (they arrive empty), then click **Rebuild profiles** on the Profiles tab. Until then, papers are still matched to students but cannot be sent to them.

   To add a student, add a sub-folder named after them; to remove one, delete their sub-folder. Then rebuild the profiles (Profiles tab). Unreadable PDFs are skipped and reported.
4. Create a Gmail **App Password**: Google Account → Security → 2-Step Verification → App passwords. Make sure IMAP is enabled in Gmail settings. This is not your normal Gmail password.

## 2. Start LitRoute

Open a terminal (Windows: PowerShell) **in the folder that contains `litroute-profiles`**, and run the command below. Use exactly this command every time, including both `-v` lines. Without them, your saved Gmail credentials and review queue are deleted when LitRoute stops.

**Linux / Mac**
```
docker run --rm -p 6080:6080 \
  -v litroute-data:/root/.litroute \
  -v "$PWD/litroute-profiles":/root/.litroute/profiles \
  litroute
```

**Windows PowerShell**
```
docker run --rm -p 6080:6080 `
  -v litroute-data:/root/.litroute `
  -v "${PWD}\litroute-profiles:/root/.litroute/profiles" `
  litroute
```

Then open **http://localhost:6080/vnc.html** in your browser and click **Connect**. The LitRoute window appears. Stop it with `Ctrl+C` in the terminal.

What the two `-v` options do:
- `litroute-data` keeps your encrypted Gmail credentials, the review queue, sent-paper history and caches between runs. Without it, everything is lost when the container stops.
- The second option makes your student folders visible to LitRoute. You edit them on your own computer; the app sees the change.

## 3. Using the features

1. **Gmail tab, first time only:** enter your Gmail address, the App Password, and a master passphrase of your choice, then **Save credentials**. They are stored encrypted. **A lost passphrase cannot be recovered**; set the credentials up again if you forget it. Each time LitRoute starts, unlock with the passphrase (banner at the top, or the Gmail tab).
2. **Profiles tab:** rebuild the student profiles after adding or changing PDFs.
3. **Pipeline tab:** **Run pipeline** reads the unread alert emails, extracts the papers, fetches details, removes duplicates, marks the emails as read and matches papers to students. **Clear and re-run…** marks the alerts unread again and empties the queue (papers already sent are kept).
4. **Review tab:** check each matched paper. **Approve** (A), **Drop** (D), **Back to pending** (P), or **Move** (M) it to another student. **Send approved** emails each student one digest.
5. **Authors tab:** papers by listed authors only need a relevance of 0.50 instead of 0.85. Names match strictly: "Alice Carter" matches "Alice K. Carter", not "A. Carter".
6. **Caches tab:** inspect and clear stored data.

## 4. Things to know

- **Reading mode** (Pipeline tab): *pattern matching* is the default (instant, reads the known alert layouts). The *local AI model* option works too: Ollama with `llama3.2:3b` is built into the image and starts automatically, so nothing needs installing. It takes 10–20 seconds per email on a CPU, so a run of 50 alerts takes roughly 8–17 minutes. It is the better choice if a publisher changes its email layout.
- **Disk and memory:** the image takes about 14 GB once loaded, and your students' PDFs another 1.3 GB. If the AI mode is slow or fails, give Docker more memory (Docker Desktop → Settings → Resources).
- **Internet access** is needed for Gmail and for fetching paper details. All matching runs locally; no student data is sent to any AI service.
- **Backups:** your data lives in the Docker volume `litroute-data` and your `litroute-profiles` folder. To reset everything: `docker volume rm litroute-data`.
- **Browser window is blank or small?** Reload the page and click Connect again.
- Port 6080 in use? Use `-p 7080:6080` and open `http://localhost:7080/vnc.html`.
