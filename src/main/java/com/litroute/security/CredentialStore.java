package com.litroute.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.security.CredentialCipher.EncryptedBlob;
import com.litroute.util.AtomicFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
 *
 * The plaintext {emailAddress, appPassword} payload is written and read
 * through Jackson's streaming JsonGenerator/JsonParser API rather than
 * ObjectMapper's record binding, specifically so the app password is never
 * materialized as a java.lang.String: writeString(char[], int, int) and
 * getTextCharacters() move the secret straight between char[] buffers that
 * this class clones, uses, and zeroes. A String is immutable, cannot be
 * wiped, and would defeat the char[]-for-secrets design GmailCredentials
 * documents.
 *
 * Neither load() nor save() clears the caller-supplied passphrase array: the
 * master passphrase is read once at process start and typically reused for
 * the process's whole lifetime (e.g. a later save() after a load()), so this
 * class treats it as borrowed, not owned. The caller that originally read
 * the passphrase is responsible for zeroing it exactly once, when the
 * process is done with it for good.
 */
public final class CredentialStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path storeFile;
    private final CredentialCipher cipher = new CredentialCipher();

    /**
     * Input: the encrypted file's path.
     * Output: a store for that file (nothing is read yet).
     * Used by: {@link #atDefaultLocation}; tests with a temp file.
     * Example: {@code new CredentialStore(tmp.resolve("c.enc")).exists()} -> false
     */
    public CredentialStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * Input: none.
     * Output: a store for {@code ~/.litroute/credentials.enc}.
     * Used by: Session (M8), CredentialSetupCli, PipelineRunnerCli.
     * Example: {@code atDefaultLocation()} -> store for /home/u/.litroute/credentials.enc
     */
    public static CredentialStore atDefaultLocation() {
        return new CredentialStore(Path.of(System.getProperty("user.home"), ".litroute", "credentials.enc"));
    }

    /**
     * Input: none.
     * Output: whether a credential file is on disk.
     * Used by: CredentialsPane (M8) to choose between "set up" and "unlock"; PipelineRunnerCli.
     * Example: before first setup -> false
     */
    public boolean exists() {
        return Files.exists(storeFile);
    }

    /**
     * Input: the master passphrase (borrowed, not cleared).
     * Output: the decrypted credentials; empty if there is no file; throws on a corrupt file or wrong passphrase.
     * Used by: Session (M8) on unlock and passphrase check, PipelineRunnerCli.
     * Example: {@code load("secret")} after {@code save(creds, "secret")} -> {@code Optional[prof@gmail.com]}
     */
    public Optional<GmailCredentials> load(char[] passphrase) throws IOException, GeneralSecurityException {
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(storeFile);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }

        Envelope envelope = JSON.readValue(fileBytes, Envelope.class);
        if (envelope.salt() == null || envelope.iv() == null || envelope.ciphertext() == null) {
            throw new IOException("Corrupt credential file: missing salt, iv, or ciphertext in " + storeFile);
        }
        EncryptedBlob blob = new EncryptedBlob(
                Base64.getDecoder().decode(envelope.salt()),
                Base64.getDecoder().decode(envelope.iv()),
                Base64.getDecoder().decode(envelope.ciphertext()));

        byte[] plaintext = cipher.decrypt(blob, passphrase);
        char[] appPassword = null;
        // Stream the JSON so the password goes char[] -> char[] and never becomes a String.
        try {
            String emailAddress = null;
            try (JsonParser parser = JSON.getFactory().createParser(plaintext)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("Corrupt credential file: expected a JSON object");
                }
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String fieldName = parser.currentName();
                    parser.nextToken();
                    if ("emailAddress".equals(fieldName)) {
                        emailAddress = parser.getText();
                    } else if ("appPassword".equals(fieldName)) {
                        int length = parser.getTextLength();
                        appPassword = new char[length];
                        System.arraycopy(parser.getTextCharacters(), parser.getTextOffset(), appPassword, 0, length);
                    }
                }
            }
            if (emailAddress == null || appPassword == null) {
                throw new IOException("Corrupt credential file: missing emailAddress or appPassword");
            }
            return Optional.of(new GmailCredentials(emailAddress, appPassword));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (appPassword != null) {
                Arrays.fill(appPassword, '\0');
            }
        }
    }

    /**
     * Input: credentials and the master passphrase (borrowed, not cleared).
     * Output: none; the file is atomically replaced by a freshly encrypted envelope.
     * Used by: Session (M8) when credentials are entered, CredentialSetupCli.
     * Example: {@code save(new GmailCredentials("prof@gmail.com", pw), "secret")} -> {@code {"salt":"..","iv":"..","ciphertext":".."}}
     */
    public void save(GmailCredentials credentials, char[] passphrase) throws IOException, GeneralSecurityException {
        char[] appPassword = credentials.appPassword();
        byte[] plaintext;
        try {
            plaintext = serializePayload(credentials.emailAddress(), appPassword);
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

    /**
     * Input: none.
     * Output: none; the credential file is deleted if present.
     * Used by: tests (to start from no credentials).
     * Example: {@code clear(); exists()} -> false
     */
    public void clear() throws IOException {
        Files.deleteIfExists(storeFile);
    }

    /**
     * Input: the address and the App Password.
     * Output: UTF-8 JSON bytes {@code {"emailAddress":..,"appPassword":..}}, written without making the password a String.
     * Used by: {@link #save}.
     * Example: {@code serializePayload("a@b.com", "pw")} -> {@code {"emailAddress":"a@b.com","appPassword":"pw"}}
     */
    private static byte[] serializePayload(String emailAddress, char[] appPassword) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON.getFactory().createGenerator(buffer)) {
            generator.writeStartObject();
            generator.writeStringField("emailAddress", emailAddress);
            generator.writeFieldName("appPassword");
            generator.writeString(appPassword, 0, appPassword.length);
            generator.writeEndObject();
        }
        return buffer.toByteArray();
    }

    /** The on-disk JSON: base64 salt, IV and ciphertext. */
    private record Envelope(
            @JsonProperty("salt") String salt,
            @JsonProperty("iv") String iv,
            @JsonProperty("ciphertext") String ciphertext) {

        /** Jackson's constructor for reading the file; no checks (load checks for missing fields). */
        @JsonCreator
        Envelope {
        }
    }
}
