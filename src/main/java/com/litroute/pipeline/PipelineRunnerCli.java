package com.litroute.pipeline;

import com.litroute.delivery.ApprovedPaperSender;
import com.litroute.embedding.Specter2EmbeddingProvider;
import com.litroute.ingestion.email.GmailEmailSource;
import com.litroute.pipeline.PipelineRunner.MatcherKind;
import com.litroute.pipeline.PipelineRunner.Report;
import com.litroute.security.CredentialStore;
import com.litroute.security.GmailCredentials;
import java.io.Console;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs the pipeline once without the UI, e.g. from cron:
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.litroute.pipeline.PipelineRunnerCli [-Dexec.args="--matcher=ai --max-unread=100 --keep-unread --send-approved"]
 * </pre>
 *
 * {@code --matcher=hybrid} (the default) or {@code --matcher=ai} picks M6's matcher; {@code --max-unread}
 * caps how many unread Gmail messages are examined (default {@value GmailEmailSource#DEFAULT_MAX_UNREAD}).
 * {@code --keep-unread} leaves the processed alerts unread in Gmail, for testing.
 * {@code --send-approved} then emails the papers the professor has approved to their students (M10).
 * The master passphrase that unlocks the Gmail credentials is read from the terminal, or, where there is
 * none (cron), from the {@value #PASSPHRASE_VARIABLE} environment variable. Set the credentials up first with
 * {@code CredentialSetupCli} or the UI.
 */
public final class PipelineRunnerCli {

    static final String PASSPHRASE_VARIABLE = "LITROUTE_PASSPHRASE";

    private PipelineRunnerCli() {
    }

    public static void main(String[] args) throws Exception {
        MatcherKind kind = MatcherKind.HYBRID;
        int maxUnread = GmailEmailSource.DEFAULT_MAX_UNREAD;
        boolean markRead = true;
        boolean sendApproved = false;
        for (String arg : args) {
            if (arg.startsWith("--matcher=")) {
                kind = MatcherKind.valueOf(arg.substring("--matcher=".length()).toUpperCase(Locale.ROOT));
            } else if (arg.startsWith("--max-unread=")) {
                maxUnread = Integer.parseInt(arg.substring("--max-unread=".length()));
            } else if (arg.equals("--keep-unread")) {
                markRead = false;
            } else if (arg.equals("--send-approved")) {
                sendApproved = true;
            } else {
                System.err.println("Unknown argument: " + arg
                        + " (expected --matcher=hybrid|ai, --max-unread=N, --keep-unread or --send-approved)");
                System.exit(2);
            }
        }
        CredentialStore credentialStore = CredentialStore.atDefaultLocation();
        if (!credentialStore.exists()) {
            System.err.println("No Gmail credentials yet: set them up with CredentialSetupCli or the UI first.");
            System.exit(1);
        }
        char[] passphrase = readPassphrase();
        if (passphrase == null) {
            System.err.println("No terminal attached and " + PASSPHRASE_VARIABLE + " is not set.");
            System.exit(1);
        }
        try {
            Optional<GmailCredentials> credentials;
            try {
                credentials = credentialStore.load(passphrase);
            } catch (GeneralSecurityException e) {
                System.err.println("Wrong master passphrase.");
                System.exit(1);
                return;
            }
            if (credentials.isEmpty()) {
                System.err.println("No Gmail credentials found.");
                System.exit(1);
            }
            try (Specter2EmbeddingProvider provider = Specter2EmbeddingProvider.atDefaultLocation()) {
                Report report = PipelineRunner.atDefaultLocations(provider, kind, maxUnread, markRead).run(credentials.get(), System.out::println);
                System.out.printf("%nDone: %d new alert(s), %d paper(s) scored, %d match(es) (%d recommended), "
                        + "%d in the review queue.%n", report.newAlerts(), report.papersScored(), report.matches(),
                        report.recommended(), report.queueSize());
                if (sendApproved) {
                    ApprovedPaperSender.Report sent = ApprovedPaperSender.atDefaultLocations()
                            .send(credentials.get(), System.out::println);
                    System.out.printf("Sent %d paper(s) in %d email(s), %d PDF(s) attached.%n", sent.papers(),
                            sent.emails(), sent.attachments());
                    sent.unaddressed().forEach(student -> System.out.println("No address for " + student));
                    sent.failures().forEach(failure -> System.out.println("Failed: " + failure));
                }
            } finally {
                credentials.get().clear();
            }
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    private static char[] readPassphrase() {
        Console console = System.console();
        if (console != null) {
            return console.readPassword("Master passphrase: ");
        }
        String fromEnvironment = System.getenv(PASSPHRASE_VARIABLE);
        return fromEnvironment == null ? null : fromEnvironment.toCharArray();
    }
}
