package com.litroute.security;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * A Gmail address plus an IMAP App Password (not the account's login password).
 * appPassword is a char[] rather than a String so callers can wipe it from
 * memory with clear() once the IMAP session is established, instead of
 * waiting on GC to collect an immutable String.
 */
public final class GmailCredentials {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final String emailAddress;
    private final char[] appPassword;

    /**
     * Validated credentials.
     *
     * @param emailAddress a Gmail address
     * @param appPassword  the App Password; copied, so the caller may wipe its own array
     * @throws IllegalArgumentException if the address is malformed or the password is empty
     */
    public GmailCredentials(String emailAddress, char[] appPassword) {
        if (emailAddress == null || emailAddress.isBlank()) {
            throw new IllegalArgumentException("emailAddress must not be blank");
        }
        if (!EMAIL_PATTERN.matcher(emailAddress).matches()) {
            throw new IllegalArgumentException("emailAddress is not a valid email address: " + emailAddress);
        }
        if (appPassword == null || appPassword.length == 0) {
            throw new IllegalArgumentException("appPassword must not be empty");
        }
        this.emailAddress = emailAddress;
        this.appPassword = appPassword.clone();
    }

    /** {@return the Gmail address} */
    public String emailAddress() {
        return emailAddress;
    }

    /** {@return a copy of the App Password}; the caller must clear it after use. */
    public char[] appPassword() {
        return appPassword.clone();
    }

    /**
     * Input: none.
     * Output: none; this object's password is overwritten with zeros (unusable afterwards).
     * Used by: Session (M8), CredentialSetupCli, PipelineRunnerCli, once a login is done.
     * Example: {@code c.clear(); c.appPassword()} -> all {@code '\0'}
     */
    public void clear() {
        Arrays.fill(appPassword, '\0');
    }
}
