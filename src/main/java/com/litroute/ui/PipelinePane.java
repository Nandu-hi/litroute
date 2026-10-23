package com.litroute.ui;

import com.litroute.ingestion.email.GmailEmailSource;
import com.litroute.pipeline.PipelineRunner;
import com.litroute.pipeline.PipelineRunner.MatcherKind;
import com.litroute.pipeline.PipelineRunner.Report;
import com.litroute.scoring.ai.GroqClient;
import com.litroute.security.GmailCredentials;
import java.util.prefs.Preferences;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/**
 * Runs M9 on demand: profile sync, Gmail, extraction, enrichment, deduplication, matching and the review
 * queue, with a live log. The matcher is chosen here, the AI one needing {@value GroqClient#KEY_VARIABLE}
 * in LitRoute's environment, and so is how many unread Gmail messages a run examines and whether the
 * processed alerts are left unread (for testing); both are remembered between sessions.
 */
final class PipelinePane extends BorderPane {

    private static final String MAX_UNREAD_KEY = "maxUnreadEmails";
    private static final String KEEP_UNREAD_KEY = "keepEmailsUnread";
    private static final int MAX_UNREAD_CEILING = 5000;

    private final Session session;
    private final Runnable queueChanged;
    private final Runnable showCredentials;
    private final ComboBox<MatcherKind> matcher = new ComboBox<>(FXCollections.observableArrayList(MatcherKind.values()));
    private final Preferences preferences = Preferences.userNodeForPackage(PipelinePane.class);
    private final Spinner<Integer> maxUnread = new Spinner<>(1, MAX_UNREAD_CEILING,
            Math.max(1, Math.min(MAX_UNREAD_CEILING,
                    preferences.getInt(MAX_UNREAD_KEY, GmailEmailSource.DEFAULT_MAX_UNREAD))), 10);
    private final CheckBox keepUnread = new CheckBox("Keep emails unread");
    private final Label hint = new Label();
    private final Label summary = new Label("Not run yet in this session.");
    private final LogPane log = new LogPane();

    /**
     * @param queueChanged    called on the UI thread after a run that may have added papers to the queue
     * @param showCredentials brings up the Gmail tab, for a run started while the credentials are locked
     */
    PipelinePane(Session session, Runnable queueChanged, Runnable showCredentials) {
        this.session = session;
        this.queueChanged = queueChanged;
        this.showCredentials = showCredentials;

        matcher.getSelectionModel().select(MatcherKind.HYBRID);
        matcher.valueProperty().addListener((o, was, now) -> updateHint());
        maxUnread.setEditable(true);
        maxUnread.setPrefWidth(90);
        maxUnread.valueProperty().addListener((o, was, now) -> preferences.putInt(MAX_UNREAD_KEY, now));
        maxUnread.disableProperty().bind(session.busyProperty());
        keepUnread.setSelected(preferences.getBoolean(KEEP_UNREAD_KEY, false));
        keepUnread.selectedProperty().addListener((o, was, now) -> preferences.putBoolean(KEEP_UNREAD_KEY, now));
        keepUnread.disableProperty().bind(session.busyProperty());
        Button run = new Button("Run pipeline");
        run.setDefaultButton(true);
        run.disableProperty().bind(session.busyProperty());
        run.setOnAction(event -> run());
        hint.setStyle("-fx-text-fill: #8a6d00;");
        updateHint();

        HBox bar = new HBox(10, new Label("Matcher:"), matcher, new Label("Max unread emails:"), maxUnread, keepUnread, run, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        summary.setPadding(new Insets(8));
        summary.setWrapText(true);
        setTop(bar);
        setCenter(log);
        setBottom(summary);
        BorderPane.setMargin(log, new Insets(0, 8, 0, 8));
    }

    private void updateHint() {
        boolean missingKey = matcher.getValue() == MatcherKind.AI && System.getenv(GroqClient.KEY_VARIABLE) == null;
        hint.setText(missingKey ? "Set " + GroqClient.KEY_VARIABLE + " before starting LitRoute to use the AI matcher." : "");
    }

    private void run() {
        if (!session.unlocked()) {
            summary.setText("Not run: unlock the Gmail credentials first (Gmail tab).");
            showCredentials.run();
            return;
        }
        MatcherKind kind = matcher.getValue();
        int limit = maxUnread.getValue();
        boolean markRead = !keepUnread.isSelected();
        log.line("Run started, matching with " + kind + ", examining at most " + limit + " unread email(s)"
                + (markRead ? "." : ", leaving them unread."));
        summary.setText("Running...");
        session.runJob(new Task<Report>() {
            @Override
            protected Report call() throws Exception {
                try (AutoCloseable captured = log.capture()) {
                    log.line("Loading the SPECTER2 model...");
                    PipelineRunner runner = PipelineRunner.atDefaultLocations(session.model(), kind, limit, markRead);
                    GmailCredentials gmail = session.gmail();
                    try {
                        return runner.run(gmail, log::line);
                    } finally {
                        gmail.clear();
                    }
                }
            }

            @Override
            protected void succeeded() {
                Report report = getValue();
                String waiting = report.unscored() > 0 ? ", " + report.unscored() + " waiting for profiles" : "";
                summary.setText(String.format("Last run: %d new alert(s), %d paper(s) scored, %d match(es) queued "
                        + "(%d recommended)%s. The review queue holds %d.", report.newAlerts(), report.papersScored(),
                        report.matches(), report.recommended(), waiting, report.queueSize()));
                log.line("Run finished.");
                queueChanged.run();
            }

            @Override
            protected void failed() {
                Throwable cause = getException();
                log.line("Run failed: " + cause);
                summary.setText("Last run failed: " + cause.getMessage()
                        + " Steps already finished keep their results; run again to continue.");
                queueChanged.run();
            }
        });
    }
}
