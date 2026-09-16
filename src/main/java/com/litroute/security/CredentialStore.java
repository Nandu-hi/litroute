package com.litroute.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.security.CredentialCipher.EncryptedBlob;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Loads/saves the Gmail App Password used by M1, encrypted at rest with a
 * master passphrase the user supplies at process start (never persisted).
 *
 * File layout on disk (~/.litroute/credentials.enc by default) is a small
 * JSON envelope of base64 fields: salt and iv are not secret and are stored
 * in the clear; ciphertext is the AES-GCM-encrypted JSON of {emailAddress,
 * appPassword}. Losing the passphrase means the file cannot be decrypted;
 * there is no recovery path other than re-running the setup flow to
 * overwrite it with a fresh app password.
 *
 * "Changing" credentials is just calling save() again with a new
 * GmailCredentials - it overwrites the previous encrypted file, there is no
 * versioning or migration to worry about since this store only ever holds
 * one record.
 */
public final class CredentialStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path storeFile;
    private final CredentialCipher cipher = new CredentialCipher();

    public CredentialStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static CredentialStore atDefaultLocation() {
        return new CredentialStore(Path.of(System.getProperty("user.home"), ".litroute", "credentials.enc"));
    }

    public boolean exists() {
        return Files.exists(storeFile);
    }

    public Optional<GmailCredentials> load(char[] passphrase) throws IOException, GeneralSecurityException {
        if (!exists()) {
            return Optional.empty();
        }
        Envelope envelope = JSON.readValue(Files.readAllBytes(storeFile), Envelope.class);
        EncryptedBlob blob = new EncryptedBlob(
                Base64.getDecoder().decode(envelope.salt()),
                Base64.getDecoder().decode(envelope.iv()),
                Base64.getDecoder().decode(envelope.ciphertext()));

        byte[] plaintext = cipher.decrypt(blob, passphrase);
        try {
            PlaintextPayload payload = JSON.readValue(plaintext, PlaintextPayload.class);
            return Optional.of(new GmailCredentials(payload.emailAddress(), payload.appPassword().toCharArray()));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void save(GmailCredentials credentials, char[] passphrase) throws IOException, GeneralSecurityException {
        char[] appPassword = credentials.appPassword();
        byte[] plaintext;
        try {
            PlaintextPayload payload = new PlaintextPayload(credentials.emailAddress(), new String(appPassword));
            plaintext = JSON.writeValueAsBytes(payload);
        } finally {
            Arrays.fill(appPassword, '\0');
        }

        try {
            EncryptedBlob blob = cipher.encrypt(plaintext, passphrase);
            Envelope envelope = new Envelope(
                    Base64.getEncoder().encodeToString(blob.salt()),
                    Base64.getEncoder().encodeToString(blob.iv()),
                    Base64.getEncoder().encodeToString(blob.ciphertext()));
            AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(envelope));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(storeFile);
    }

    private record Envelope(
            @JsonProperty("salt") String salt,
            @JsonProperty("iv") String iv,
            @JsonProperty("ciphertext") String ciphertext) {

        @JsonCreator
        Envelope {
        }
    }

    private record PlaintextPayload(
            @JsonProperty("emailAddress") String emailAddress,
            @JsonProperty("appPassword") String appPassword) {

        @JsonCreator
        PlaintextPayload {
        }
    }
}
