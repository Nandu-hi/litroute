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
     * Input: a Gmail address and the App Password (copied; the caller may wipe its own array).
     * Output: the credentials; throws if the address is malformed or the password is empty.
     * Used by: CredentialStore.load, CredentialsPane (M8), CredentialSetupCli.
     * Example: {@code new GmailCredentials("prof@gmail.com", pw)} -> ok; {@code "prof"} -> throws
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

    /**
     * Input: none.
     * Output: the Gmail address.
     * Used by: GmailEmailSource (M1) IMAP login, GmailSmtpSender (M10) as sender, Session (M8).
     * Example: -> {@code "prof@gmail.com"}
     */
    public String emailAddress() {
        return emailAddress;
    }

    /**
     * Input: none.
     * Output: a copy of the App Password; the caller must clear it after use.
     * Used by: GmailEmailSource (M1) and GmailSmtpSender (M10) to log in; CredentialStore.save.
     * Example: -> {@code ['a','b','c','d',...]}
     */
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
