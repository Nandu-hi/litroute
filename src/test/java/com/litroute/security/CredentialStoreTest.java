package com.litroute.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;
import org.junit.Test;

public class CredentialStoreTest {

    @Test
    public void loadReturnsEmptyWhenNoFileExists() throws IOException, GeneralSecurityException {
        Path missingFile = Files.createTempDirectory("litroute-cred-test").resolve("credentials.enc");
        CredentialStore store = new CredentialStore(missingFile);

        assertTrue(store.load("any passphrase".toCharArray()).isEmpty());
        assertFalse(store.exists());
    }

    @Test
    public void savedCredentialsRoundTripWithCorrectPassphrase() throws IOException, GeneralSecurityException {
        Path file = Files.createTempDirectory("litroute-cred-test").resolve("credentials.enc");
        CredentialStore store = new CredentialStore(file);
        GmailCredentials original = new GmailCredentials("student@gmail.com", "app-password-1234".toCharArray());

        store.save(original, "correct horse battery staple".toCharArray());

        Optional<GmailCredentials> loaded = store.load("correct horse battery staple".toCharArray());
        assertTrue(loaded.isPresent());
        assertEquals("student@gmail.com", loaded.get().emailAddress());
        assertArrayEquals("app-password-1234".toCharArray(), loaded.get().appPassword());
    }

    @Test
    public void wrongPassphraseFailsToDecrypt() throws IOException, GeneralSecurityException {
        Path file = Files.createTempDirectory("litroute-cred-test").resolve("credentials.enc");
        CredentialStore store = new CredentialStore(file);
        store.save(new GmailCredentials("student@gmail.com", "app-password-1234".toCharArray()),
                "correct horse battery staple".toCharArray());

        assertThrows(GeneralSecurityException.class, () -> store.load("wrong passphrase".toCharArray()));
    }

    @Test
    public void savingAgainOverwritesPreviousCredentials() throws IOException, GeneralSecurityException {
        Path file = Files.createTempDirectory("litroute-cred-test").resolve("credentials.enc");
        CredentialStore store = new CredentialStore(file);
        char[] passphrase = "correct horse battery staple".toCharArray();

        store.save(new GmailCredentials("student@gmail.com", "old-app-password".toCharArray()), passphrase);
        store.save(new GmailCredentials("student@gmail.com", "new-app-password".toCharArray()), passphrase);

        Optional<GmailCredentials> loaded = store.load(passphrase);
        assertArrayEquals("new-app-password".toCharArray(), loaded.get().appPassword());
    }

    @Test
    public void plaintextFileContentsNeverContainTheAppPassword() throws IOException, GeneralSecurityException {
        Path file = Files.createTempDirectory("litroute-cred-test").resolve("credentials.enc");
        CredentialStore store = new CredentialStore(file);

        store.save(new GmailCredentials("student@gmail.com", "super-secret-app-password".toCharArray()),
                "correct horse battery staple".toCharArray());

        String rawFileContents = Files.readString(file);
        assertFalse(rawFileContents.contains("super-secret-app-password"));
    }
}
