package com.litroute.ui;

import com.litroute.embedding.Specter2EmbeddingProvider;
import com.litroute.security.CredentialStore;
import com.litroute.security.GmailCredentials;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.concurrent.Task;

/**
 * What the UI holds for the life of the process: the master passphrase once the professor has unlocked
 * the credentials (kept as a char[] and wiped on exit, as {@link CredentialStore} expects of its caller),
 * the SPECTER2 model (loaded on first use, it takes seconds), and one job thread. The pipeline run and the
 * profile rebuild both use the model and the profile store, so they run one at a time on that thread;
 * {@link #busyProperty()} is true while one is running.
 */
final class Session implements AutoCloseable {

    private final CredentialStore credentials = CredentialStore.atDefaultLocation();
    private final ExecutorService jobs = Executors.newSingleThreadExecutor(daemon("litroute-job"));
    private final ExecutorService io = Executors.newSingleThreadExecutor(daemon("litroute-io"));
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper unlockedState = new ReadOnlyBooleanWrapper(false);
    private char[] passphrase;
    private Specter2EmbeddingProvider model;

    CredentialStore credentialStore() {
        return credentials;
    }

    synchronized boolean unlocked() {
        return passphrase != null;
    }

    /**
     * Keeps the passphrase if it opens the stored credentials.
     *
     * @param candidate taken over by the session: kept if right, wiped if wrong
     * @return false if the passphrase is wrong
     */
    synchronized boolean unlock(char[] candidate) throws IOException {
        try {
            credentials.load(candidate).ifPresent(GmailCredentials::clear);
        } catch (GeneralSecurityException e) {
            Arrays.fill(candidate, '\0');
            return false;
        }
        forgetPassphrase();
        passphrase = candidate;
        unlockedState.set(true);
        return true;
    }

    /** Saves new credentials under a new passphrase and keeps that passphrase; both arrays are taken over. */
    synchronized void saveCredentials(GmailCredentials newCredentials, char[] newPassphrase)
            throws IOException, GeneralSecurityException {
        try {
            credentials.save(newCredentials, newPassphrase);
        } finally {
            newCredentials.clear();
        }
        forgetPassphrase();
        passphrase = newPassphrase;
        unlockedState.set(true);
    }

    /** The unlocked Gmail credentials, freshly decrypted; the caller clears them. */
    synchronized GmailCredentials gmail() throws IOException {
        if (passphrase == null) {
            throw new IllegalStateException("The credentials are locked");
        }
        try {
            Optional<GmailCredentials> loaded = credentials.load(passphrase);
            return loaded.orElseThrow(() -> new IOException("No Gmail credentials are stored"));
        } catch (GeneralSecurityException e) {
            throw new IOException("The stored credentials no longer open with this passphrase", e);
        }
    }

    /** The SPECTER2 model, loaded on first use; call from a job, not the UI thread. */
    synchronized Specter2EmbeddingProvider model() throws IOException {
        if (model == null) {
            model = Specter2EmbeddingProvider.atDefaultLocation();
        }
        return model;
    }

    /** True once the credentials are unlocked; changes on the UI thread, which is where they are unlocked. */
    ReadOnlyBooleanProperty unlockedProperty() {
        return unlockedState.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    /** Runs a job on the job thread; call from the UI thread. {@link #busyProperty()} is true until it ends. */
    void runJob(Task<?> task) {
        busy.set(true);
        task.runningProperty().addListener((observable, wasRunning, running) -> {
            if (!running) {
                busy.set(false);
            }
        });
        jobs.execute(task);
    }

    /** Runs a short file write (a review decision) off the UI thread, in order. */
    void runIo(Task<?> task) {
        io.execute(task);
    }

    @Override
    public synchronized void close() {
        jobs.shutdownNow();
        io.shutdown();
        forgetPassphrase();
        if (model != null) {
            model.close();
            model = null;
        }
    }

    private void forgetPassphrase() {
        if (passphrase != null) {
            Arrays.fill(passphrase, '\0');
            passphrase = null;
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
