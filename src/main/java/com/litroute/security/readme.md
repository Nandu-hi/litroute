# `security`: the Gmail App Password, encrypted at rest

The Gmail address and App Password live in `~/.litroute/credentials.enc`, encrypted under a master passphrase that is never saved. The password is held as `char[]` (never a `String`) and wiped after use.

```
AES-256-GCM, key = PBKDF2-HMAC-SHA256(passphrase, 16-byte salt, 210,000 rounds), 12-byte IV
file = {salt, iv, ciphertext}, written atomically
```

| Class | Role |
|---|---|
| [GmailCredentials](GmailCredentials.java) | Address + password copy; `appPassword()` returns a copy for the caller to wipe |
| [CredentialCipher](CredentialCipher.java) | AES/GCM; a wrong passphrase or tampered file fails instead of decrypting garbage |
| [CredentialStore](CredentialStore.java) | `load` / `save` / `exists` / `clear`; streaming JSON so the password never becomes a `String` |
| [CredentialSetupCli](CredentialSetupCli.java) | Terminal setup (refuses without a real terminal); the Gmail tab does the same |

A lost passphrase can't be recovered; run the setup again.

**Tests:** [CredentialStoreTest](../../../../../test/java/com/litroute/security/CredentialStoreTest.java): round-trip, wrong passphrase fails, overwrite, no plain text on disk.
