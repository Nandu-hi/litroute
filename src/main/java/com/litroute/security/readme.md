# `security`: the Gmail App Password, encrypted at rest

The Gmail address and App Password are stored in `~/.litroute/credentials.enc`, encrypted under a master passphrase that is never saved. The secret is held in `char[]` (never a `String`) and wiped after use.

```
save: {emailAddress, appPassword} ─streaming JSON─▶ AES-256-GCM ─▶ {salt, iv, ciphertext} ─atomic write─▶ credentials.enc
        key = PBKDF2-HMAC-SHA256(passphrase, 16-byte salt, 210,000 rounds); 12-byte IV; 128-bit tag
load: credentials.enc ─decrypt─▶ char[] ─▶ GmailCredentials ─▶ M1 (IMAP) and M10 (SMTP)
```

| Class | Role |
|---|---|
| [GmailCredentials](GmailCredentials.java) | Holds the address and the password `char[]`. It stores a copy, `appPassword()` returns another copy (the caller wipes it), and `clear()` zeroes it. |
| [CredentialCipher](CredentialCipher.java) | Package-private AES/GCM with PBKDF2. A wrong passphrase or a tampered file **fails** decryption (GCM authenticates the data) instead of producing garbage. Key bytes are wiped. |
| [CredentialStore](CredentialStore.java) | `load` / `save` / `exists` / `clear`. It uses Jackson's streaming API so the password never becomes a `String`. Saving again overwrites; that is how the password is rotated. It never wipes the passphrase it is given: whoever read the passphrase owns it. |
| [CredentialSetupCli](CredentialSetupCli.java) | Prompts for the address, the App Password and the passphrase (twice), and saves them. It refuses to run without a real terminal so secrets are never echoed. The app's **Gmail** tab does the same thing. |

- The salt and IV are stored in the clear; all the security rests on the passphrase.
- **A lost passphrase can't be recovered:** run the setup again.
- The file sits outside `data/`, and no secret ever reaches an M7 store.

**Tests:** [CredentialStoreTest](../../../../../test/java/com/litroute/security/CredentialStoreTest.java) checks that a missing file loads empty, that a save loads back with the right passphrase, that a wrong passphrase fails, that saving overwrites, and that the password never appears in plain text on disk.
