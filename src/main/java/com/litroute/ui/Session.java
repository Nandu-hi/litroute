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

    /**
     * Input: none.
     * Output: the credential store this session unlocks.
     * Used by: CredentialsPane, to tell set-up from unlock.
     * Example: -> store for ~/.litroute/credentials.enc
     */
    CredentialStore credentialStore() {
        return credentials;
    }

    /**
     * Input: none.
     * Output: whether a working passphrase is held.
     * Used by: CredentialsPane, PipelinePane, ReviewPane.
     * Example: after a successful unlock -> true
     */
    synchronized boolean unlocked() {
        return passphrase != null;
    }

    /**
     * Keeps the passphrase if it opens the stored credentials.
     * Input: a passphrase (taken over: kept if right, wiped if wrong).
     * Output: true and unlocked if it opens the stored credentials, else false; IOException if the file can't be read.
     * Used by: CredentialsPane's Unlock button.
     * Example: unlock("wrong") -> false; unlock("secret") -> true
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

    /**
     * Saves new credentials under a new passphrase and keeps that passphrase; both arrays are taken over.
     * Input: new credentials and a new passphrase (both taken over).
     * Output: none; they are saved encrypted and the session is unlocked with that passphrase.
     * Used by: CredentialsPane's Save button.
     * Example: saveCredentials(prof@gmail.com + app password, "secret") -> credentials.enc rewritten, unlocked
     */
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

    /**
     * The unlocked Gmail credentials, freshly decrypted; the caller clears them.
     * Input: none.
     * Output: the stored credentials, freshly decrypted (caller clears them); IllegalStateException if locked, IOException if missing or no longer opening.
     * Used by: PipelinePane (run/reset), ReviewPane (send approved).
     * Example: {@code try { GmailCredentials g = session.gmail(); runner.run(g, ...); } finally { g.clear(); }}
     */
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

    /**
     * The SPECTER2 model, loaded on first use; call from a job, not the UI thread.
     * Input: none.
     * Output: the SPECTER2 model, loaded on first use (seconds); call from a job thread.
     * Used by: PipelinePane and ProfilesPane jobs.
     * Example: first call loads ~/.litroute/models; later calls return the same model
     */
    synchronized Specter2EmbeddingProvider model() throws IOException {
        if (model == null) {
            model = Specter2EmbeddingProvider.atDefaultLocation();
        }
        return model;
    }

    /**
     * True once the credentials are unlocked; changes on the UI thread, which is where they are unlocked.
     * Input: none.
     * Output: a property that is true once unlocked.
     * Used by: LitRouteApp (hides the locked banner).
     * Example: unlock -> property flips to true
     */
    ReadOnlyBooleanProperty unlockedProperty() {
        return unlockedState.getReadOnlyProperty();
    }

    /**
     * Input: none.
     * Output: a property that is true while a job runs.
     * Used by: PipelinePane, ProfilesPane, ReviewPane (disable their buttons).
     * Example: during a pipeline run -> true
     */
    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    /**
     * Runs a job on the job thread; call from the UI thread. {@link #busyProperty()} is true until it ends.
     * Input: a long task (call from the UI thread).
     * Output: none; it runs on the single job thread, busy until it ends.
     * Used by: PipelinePane (run), ProfilesPane (rebuild), ReviewPane (load, send).
     * Example: two Run clicks -> the second waits (buttons are disabled meanwhile)
     */
    void runJob(Task<?> task) {
        busy.set(true);
        task.runningProperty().addListener((observable, wasRunning, running) -> {
            if (!running) {
                busy.set(false);
            }
        });
        jobs.execute(task);
    }

    /**
     * Runs a short file write (a review decision) off the UI thread, in order.
     * Input: a short file write.
     * Output: none; it runs off the UI thread, in submission order.
     * Used by: ReviewPane, for approve/drop decisions.
     * Example: approve then drop the same paper -> written in that order
     */
    void runIo(Task<?> task) {
        io.execute(task);
    }

    /**
     * Input: none.
     * Output: none; threads stopped, passphrase wiped, model closed.
     * Used by: LitRouteApp.stop.
     * Example: -> session unusable afterwards
     */
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

    /**
     * Input: none.
     * Output: none; the held passphrase (if any) is zeroed and dropped.
     * Used by: unlock, saveCredentials, close.
     * Example: -> unlocked() false
     */
    private void forgetPassphrase() {
        if (passphrase != null) {
            Arrays.fill(passphrase, '\0');
            passphrase = null;
        }
    }

    /**
     * Input: a thread name.
     * Output: a factory of daemon threads with that name, so they never keep the JVM alive.
     * Used by: the jobs and io executors.
     * Example: daemon("litroute-job") -> threads named litroute-job, daemon = true
     */
    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
