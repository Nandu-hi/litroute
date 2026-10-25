package com.litroute.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts/decrypts small byte payloads (the serialized GmailCredentials)
 * with AES-256-GCM, using a key derived from a user-supplied master
 * passphrase via PBKDF2. The salt and IV are not secret and are stored
 * alongside the ciphertext (see EncryptedBlob / CredentialStore's JSON file);
 * secrecy comes entirely from the passphrase.
 */
final class CredentialCipher {

    private static final String KEY_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int KEY_LENGTH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    /** What encrypt produces and decrypt consumes: salt and IV (public) plus the GCM ciphertext+tag. */
    record EncryptedBlob(byte[] salt, byte[] iv, byte[] ciphertext) {
    }

    /**
     * Input: plaintext bytes and the master passphrase (not cleared here).
     * Output: a blob with a fresh random salt, a fresh random IV and the AES-256-GCM ciphertext.
     * Used by: CredentialStore.save.
     * Example: {@code encrypt("{...}".getBytes(), "pass".toCharArray())} -> {@code EncryptedBlob(salt[16], iv[12], ciphertext[n+16])}
     */
    EncryptedBlob encrypt(byte[] plaintext, char[] passphrase) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        // A new salt and IV every save: the same passphrase never reuses a key/IV pair.
        SecretKey key = deriveKey(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        return new EncryptedBlob(salt, iv, ciphertext);
    }

    /**
     * Input: a blob from {@link #encrypt} and the passphrase.
     * Output: the original plaintext; throws AEADBadTagException on a wrong passphrase or tampered file.
     * Used by: CredentialStore.load.
     * Example: {@code decrypt(encrypt(b, p), p)} -> {@code b}; with another passphrase -> throws
     */
    byte[] decrypt(EncryptedBlob blob, char[] passphrase) throws GeneralSecurityException {
        SecretKey key = deriveKey(passphrase, blob.salt());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, blob.iv()));
        return cipher.doFinal(blob.ciphertext());
    }

    /**
     * Input: the passphrase and a salt.
     * Output: a 256-bit AES key from PBKDF2-HMAC-SHA256 (210,000 iterations); temporary key bytes are wiped.
     * Used by: {@link #encrypt} and {@link #decrypt}.
     * Example: {@code deriveKey("pass", salt)} -> the same key every time for the same salt
     */
    private SecretKey deriveKey(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(keyBytes, "AES");
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }
}
