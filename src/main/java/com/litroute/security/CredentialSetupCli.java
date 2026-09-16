package com.litroute.security;

import java.io.Console;
import java.util.Arrays;

/**
 * One-time onboarding / "change credentials" entry point until M8's JavaFX
 * UI exists. Run it whenever the Gmail App Password needs to be set for the
 * first time or rotated (e.g. after revoking the old one in the Google
 * Account security settings) - it simply overwrites the encrypted file.
 *
 * Requires a real terminal: System.console() is null when input is piped or
 * run from some IDE consoles, in which case this refuses to run rather than
 * echoing the app password and passphrase in plaintext.
 */
public final class CredentialSetupCli {

    private CredentialSetupCli() {
    }

    public static void main(String[] args) throws Exception {
        Console console = System.console();
        if (console == null) {
            System.err.println("No terminal attached; run this from an actual terminal so the "
                    + "app password and passphrase aren't echoed to the screen.");
            System.exit(1);
            return;
        }

        String emailAddress = console.readLine("Gmail address: ");
        char[] appPassword = console.readPassword("Gmail App Password (from Google Account > Security > "
                + "App Passwords): ");
        char[] passphrase = console.readPassword("New master passphrase (used to encrypt the app password "
                + "on disk): ");
        char[] passphraseConfirm = console.readPassword("Confirm master passphrase: ");

        try {
            if (!Arrays.equals(passphrase, passphraseConfirm)) {
                System.err.println("Passphrases did not match; aborting.");
                System.exit(1);
                return;
            }

            GmailCredentials credentials = new GmailCredentials(emailAddress, appPassword);
            try {
                CredentialStore store = CredentialStore.atDefaultLocation();
                store.save(credentials, passphrase);
                console.printf("Saved. Existing credentials (if any) were overwritten.%n");
            } finally {
                credentials.clear();
            }
        } finally {
            Arrays.fill(appPassword, '\0');
            Arrays.fill(passphrase, '\0');
            Arrays.fill(passphraseConfirm, '\0');
        }
    }
}
