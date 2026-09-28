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

    public String emailAddress() {
        return emailAddress;
    }

    /** Returns a copy; caller is responsible for clearing it after use. */
    public char[] appPassword() {
        return appPassword.clone();
    }

    public void clear() {
        Arrays.fill(appPassword, '\0');
    }
}
