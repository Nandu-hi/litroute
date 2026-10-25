package com.litroute.ui;

import com.litroute.security.GmailCredentials;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * The UI's replacement for {@code CredentialSetupCli}: unlock the stored Gmail App Password with the
 * master passphrase, or set it up and rotate it. It is a tab of the main window rather than a dialog,
 * because tiling window managers (sway) tile a dialog beside its owner instead of floating it above,
 * leaving a modal prompt out of sight and the main window apparently frozen.
 *
 * Secrets are copied out of the password fields char by char ({@link PasswordField#getCharacters()}),
 * never through a String, and the fields are cleared afterwards.
 */
final class CredentialsPane extends VBox {

    private final Session session;
    private final Label state = new Label();
    private final PasswordField unlockPassphrase = new PasswordField();
    private final Button unlock = new Button("Unlock");
    private final Label unlockMessage = message();
    private final TextField email = new TextField();
    private final PasswordField appPassword = new PasswordField();
    private final PasswordField newPassphrase = new PasswordField();
    private final PasswordField confirm = new PasswordField();
    private final Label setUpMessage = message();

    /**
     * Input: the app session.
     * Output: the Gmail tab: an unlock form and a set-up/change form.
     * Used by: LitRouteApp.start.
     * Example: {@code new CredentialsPane(session)}
     */
    CredentialsPane(Session session) {
        super(12);
        this.session = session;
        setPadding(new Insets(16));
        setMaxWidth(720);
        state.setStyle("-fx-font-size: 1.15em; -fx-font-weight: bold;");

        unlock.setOnAction(event -> unlock());
        unlockPassphrase.setOnAction(event -> unlock());
        GridPane unlockForm = grid();
        unlockForm.addRow(0, new Label("Master passphrase:"), unlockPassphrase, unlock);

        email.setPromptText("name@gmail.com");
        appPassword.setPromptText("Google Account > Security > App passwords");
        Button save = new Button("Save credentials");
        save.setOnAction(event -> save());
        confirm.setOnAction(event -> save());
        GridPane setUpForm = grid();
        setUpForm.addRow(0, new Label("Gmail address:"), email);
        setUpForm.addRow(1, new Label("Gmail App Password:"), appPassword);
        setUpForm.addRow(2, new Label("New master passphrase:"), newPassphrase);
        setUpForm.addRow(3, new Label("Confirm passphrase:"), confirm);
        setUpForm.add(save, 1, 4);

        Label about = new Label("LitRoute reads alert emails over IMAP with a Gmail App Password (not your login "
                + "password). It is stored encrypted with a master passphrase, which you enter once each time "
                + "LitRoute starts.");
        about.setWrapText(true);
        Label setUpTitle = new Label("Set up or change the credentials (overwrites any stored before)");
        setUpTitle.setStyle("-fx-font-weight: bold;");
        getChildren().addAll(state, about, unlockForm, unlockMessage, new Separator(), setUpTitle, setUpForm,
                setUpMessage);
        refresh();
    }

    /**
     * Puts the cursor where the professor has to type next.
     * Input: none.
     * Output: none; focus goes to the passphrase if credentials are stored and locked, else to the Gmail address.
     * Used by: LitRouteApp (showCredentials).
     * Example: stored and locked -> cursor in "Master passphrase"
     */
    void focusNextField() {
        (session.credentialStore().exists() && !session.unlocked() ? unlockPassphrase : email).requestFocus();
    }

    /**
     * Input: none.
     * Output: none; the state line and the unlock form follow whether credentials are stored and unlocked.
     * Used by: the constructor, unlock, save.
     * Example: nothing stored -> "No Gmail credentials yet: set them up below.", unlock disabled
     */
    private void refresh() {
        boolean stored = session.credentialStore().exists();
        boolean unlocked = session.unlocked();
        state.setText(!stored ? "No Gmail credentials yet: set them up below."
                : unlocked ? "Gmail credentials unlocked for this session."
                : "Gmail credentials are locked: enter the master passphrase.");
        unlockPassphrase.setDisable(!stored || unlocked);
        unlock.setDisable(!stored || unlocked);
    }

    /**
     * Input: none (the passphrase field).
     * Output: none; the session is unlocked, or "Wrong passphrase." shown; the field is cleared.
     * Used by: the Unlock button and Enter in the passphrase field.
     * Example: wrong passphrase -> red "Wrong passphrase."
     */
    private void unlock() {
        try {
            if (session.unlock(chars(unlockPassphrase))) {
                unlockMessage.setText("");
            } else {
                error(unlockMessage, "Wrong passphrase.");
            }
        } catch (IOException e) {
            error(unlockMessage, "Could not read the credential file: " + e.getMessage());
        }
        unlockPassphrase.clear();
        refresh();
    }

    /**
     * Input: none (the set-up form).
     * Output: none; credentials saved under the new passphrase and unlocked, or an error shown; secrets wiped.
     * Used by: the Save credentials button and Enter in the confirm field.
     * Example: passphrases differ -> red "The passphrases do not match."
     */
    private void save() {
        char[] password = chars(appPassword);
        char[] secret = chars(newPassphrase);
        char[] again = chars(confirm);
        try {
            if (secret.length == 0) {
                error(setUpMessage, "Choose a master passphrase.");
            } else if (!Arrays.equals(secret, again)) {
                error(setUpMessage, "The passphrases do not match.");
            } else {
                session.saveCredentials(new GmailCredentials(email.getText().strip(), password), secret);
                secret = null; // the session keeps it
                success(setUpMessage, "Saved, and unlocked for this session.");
                appPassword.clear();
                newPassphrase.clear();
                confirm.clear();
            }
        } catch (IllegalArgumentException e) {
            error(setUpMessage, e.getMessage());
        } catch (IOException | GeneralSecurityException e) {
            error(setUpMessage, "Could not save the credentials: " + e.getMessage());
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(again, '\0');
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
        refresh();
    }

    /**
     * Input: a password field.
     * Output: a copy of its characters, without making a String.
     * Used by: unlock, save.
     * Example: field "secret" -> ['s','e','c','r','e','t']
     */
    private static char[] chars(PasswordField field) {
        CharSequence text = field.getCharacters();
        char[] copy = new char[text.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = text.charAt(i);
        }
        return copy;
    }

    /**
     * Input: a message label and text.
     * Output: none; the text is shown in red.
     * Used by: unlock, save.
     * Example: error(msg, "Wrong passphrase.")
     */
    private static void error(Label label, String text) {
        label.setStyle("-fx-text-fill: #b00020;");
        label.setText(text);
    }

    /**
     * Input: a message label and text.
     * Output: none; the text is shown in green.
     * Used by: save.
     * Example: success(msg, "Saved, and unlocked for this session.")
     */
    private static void success(Label label, String text) {
        label.setStyle("-fx-text-fill: #1b5e20;");
        label.setText(text);
    }

    /**
     * Input: none.
     * Output: a grid with 10 px / 8 px gaps.
     * Used by: the constructor's two forms.
     * Example: -> GridPane(hgap 10, vgap 8)
     */
    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        return grid;
    }

    /**
     * Input: none.
     * Output: an empty wrapping label for messages.
     * Used by: the unlock and set-up message fields.
     * Example: -> Label("")
     */
    private static Label message() {
        Label label = new Label();
        label.setWrapText(true);
        return label;
    }
}
