# The `security` module

**In one sentence:** it stores the Gmail App Password that M1 needs, encrypted on disk with AES-256-GCM under a key derived from a master passphrase that is never saved, and keeps the secret in `char[]` buffers that are wiped after use.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** (setup) | Terminal prompts in [CredentialSetupCli](CredentialSetupCli.java) | Gmail address, App Password (from Google Account → Security → App Passwords), and a new master passphrase entered twice. |
| **Input** (each run) | `char[]` master passphrase | Entered by the user when the process starts. It is never written to disk. |
| **Stored** | `~/.litroute/credentials.enc` | JSON `{salt, iv, ciphertext}` in base64. `salt` and `iv` are not secret; `ciphertext` is the encrypted JSON `{emailAddress, appPassword}`. |
| **Output** | [GmailCredentials](GmailCredentials.java) | The address plus the App Password as a `char[]`, passed to M1's [GmailEmailSource](../ingestion/email/GmailEmailSource.java). |

## How it works

```
Setup (once, or to rotate the password):
  CredentialSetupCli ── console prompts (no echo) ──▶ GmailCredentials(address, appPassword)
        │
        ▼  CredentialStore.save(credentials, passphrase)
        │     {emailAddress, appPassword} ── streaming JSON into a byte[] (no String for the password)
        │     CredentialCipher.encrypt:
        │         salt (16 B random) + passphrase ──PBKDF2-HMAC-SHA256, 210 000 rounds──▶ AES-256 key
        │         iv (12 B random) ──AES/GCM, 128-bit tag──▶ ciphertext
        │     AtomicFiles.writeAtomically(credentials.enc, {salt, iv, ciphertext})
        ▼     plaintext bytes and password copies wiped

Every run:
  passphrase ──▶ CredentialStore.load(passphrase)
        │     read credentials.enc (missing ─▶ Optional.empty)
        │     CredentialCipher.decrypt (wrong passphrase or tampered file ─▶ GeneralSecurityException)
        │     streaming JSON parse straight into a char[]
        ▼
  GmailCredentials ──▶ M1 GmailEmailSource.fetchNewAlerts (wipes its copy when done)
```

### [GmailCredentials](GmailCredentials.java): the secret holder

A Gmail address and an IMAP **App Password** (not the account's real login password).
- The constructor checks that the address looks like an email and that the password isn't empty. It stores a **copy** of the password array.
- `appPassword()` returns another **copy**; the caller must wipe it after use.
- `clear()` overwrites the stored password with `'\0'`.
- The password is a `char[]`, not a `String`, because a `String` is immutable and can't be erased; it would stay in memory until garbage collection.

### [CredentialCipher](CredentialCipher.java): the cryptography (package-private)

- **[encrypt](CredentialCipher.java#L34):** generates a random 16-byte salt and 12-byte IV, derives the key, encrypts with `AES/GCM/NoPadding` and a 128-bit authentication tag, and returns an `EncryptedBlob(salt, iv, ciphertext)`.
- **`decrypt`:** derives the key again from the stored salt and decrypts. GCM checks that the data hasn't been altered, so a wrong passphrase or a modified file **fails** instead of producing garbage.
- **`deriveKey`:** PBKDF2-HMAC-SHA256 with **210,000 iterations**, producing a 256-bit key. The high iteration count makes guessing passphrases slow. The temporary key bytes and the copy of the passphrase in `PBEKeySpec` are wiped afterwards.
- All the security comes from the passphrase. The salt and IV are stored in plain form next to the ciphertext.

### [CredentialStore](CredentialStore.java): the encrypted file

- **[load(passphrase)](CredentialStore.java#L72):** returns empty if the file doesn't exist. Otherwise it reads the JSON envelope (and rejects one with missing fields), decrypts it, and reads `{emailAddress, appPassword}` with Jackson's **streaming** parser, copying the password characters directly into a `char[]`. The decrypted bytes are wiped in a `finally` block.
- **[save(credentials, passphrase)](CredentialStore.java#L121):** writes the payload with Jackson's streaming generator (`writeString(char[], …)`), so the password never becomes a `String`. It then encrypts and writes the envelope with [AtomicFiles](../util/AtomicFiles.java), so a crash during the write leaves the old file intact. Saving again **overwrites** the file; this is how credentials are changed. There is no versioning, since the store only ever holds one record.
- **`exists()` / `clear()`:** check for or delete the file.
- **It never clears the passphrase it is given.** The passphrase is read once at startup and reused (e.g. a `save` after a `load`), so the code that read it is responsible for wiping it.
- **Losing the passphrase means the file can't be decrypted.** The only fix is to run the setup again with a new App Password.

### [CredentialSetupCli](CredentialSetupCli.java): setup and rotation

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.security.CredentialSetupCli
```

This is the onboarding step until the M8 JavaFX UI exists. It prompts for the address, App Password and passphrase (twice), stops if the two passphrases differ, saves to the default location, and wipes every `char[]` in a `finally` block. It **refuses to run without a real terminal**: `System.console()` is null when input is piped or in some IDE consoles, and in that case the secrets would be echoed on screen.

## How it's tested

| Test class | What it covers |
|---|---|
| [CredentialStoreTest](../../../../../test/java/com/litroute/security/CredentialStoreTest.java) | `load` returns empty when no file exists. Saved credentials load back correctly with the right passphrase. A wrong passphrase fails to decrypt. Saving again overwrites the previous credentials. The file on disk never contains the App Password in plain text. |
