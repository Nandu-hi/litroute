package com.litroute.ui;

import com.litroute.ingestion.email.GmailEmailSource;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.pipeline.PipelineRunner;
import com.litroute.pipeline.PipelineRunner.MatcherKind;
import com.litroute.pipeline.PipelineRunner.Report;
import com.litroute.scoring.ai.GroqClient;
import com.litroute.security.GmailCredentials;
import com.litroute.store.EmailCacheStore;
import com.litroute.store.ScoredQueueStore;
import java.io.IOException;
import java.util.List;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
 * processed alerts are left unread (for testing); both are remembered between sessions. Clear and re-run
 * undoes every run so far (see {@link PipelineRunner#reset}) and runs again over the same emails.
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
     * Input: session - the app session; queueChanged - called on the UI thread after a run that may have added papers;
     * showCredentials - brings up the Gmail tab.
     * Output: the Pipeline tab: matcher, max-unread, keep-unread, Run and Clear-and-re-run controls, a log and a summary.
     * Used by: LitRouteApp.start.
     * Example: {@code new PipelinePane(session, review::reload, showCredentials)}
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
        run.setOnAction(event -> run(false));
        Button rerun = new Button("Clear and re-run…");
        rerun.disableProperty().bind(session.busyProperty());
        rerun.setOnAction(event -> clearAndRerun());
        hint.setStyle("-fx-text-fill: #8a6d00;");
        updateHint();

        HBox bar = new HBox(10, new Label("Matcher:"), matcher, new Label("Max unread emails:"), maxUnread, keepUnread, run, rerun, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        summary.setPadding(new Insets(8));
        summary.setWrapText(true);
        setTop(bar);
        setCenter(log);
        setBottom(summary);
        BorderPane.setMargin(log, new Insets(0, 8, 0, 8));
    }

    /**
     * Input: none.
     * Output: none; warns when the AI matcher is chosen but GROQ_API_KEY is not set.
     * Used by: the constructor and the matcher box's listener.
     * Example: choose AI without the key -> "Set GROQ_API_KEY before starting LitRoute to use the AI matcher."
     */
    private void updateHint() {
        boolean missingKey = matcher.getValue() == MatcherKind.AI && System.getenv(GroqClient.KEY_VARIABLE) == null;
        hint.setText(missingKey ? "Set " + GroqClient.KEY_VARIABLE + " before starting LitRoute to use the AI matcher." : "");
    }

    /**
     * Asks before discarding anything, saying exactly what will go, then resets and runs.
     * Input: none.
     * Output: none; after the professor confirms (told how many emails, papers and decisions are affected), resets and runs.
     * Used by: the Clear and re-run button.
     * Example: 6 processed emails, 9 queued (3 decided) -> dialog stating those numbers; OK -> run(true)
     */
    private void clearAndRerun() {
        if (!session.unlocked()) {
            summary.setText("Not run: unlock the Gmail credentials first (Gmail tab).");
            showCredentials.run();
            return;
        }
        List<ScoredPaper> queued;
        int processed;
        try {
            queued = ScoredQueueStore.atDefaultLocation().load();
            processed = EmailCacheStore.atDefaultLocation().load().size();
        } catch (IOException e) {
            summary.setText("Could not read the stores: " + e.getMessage());
            return;
        }
        long decided = queued.stream().filter(paper -> paper.status() != ReviewStatus.PENDING).count();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, String.format("This undoes every run so far and runs "
                + "the pipeline again over the same alert emails:%n%n"
                + "• the %d processed alert email(s) are marked unread in Gmail;%n"
                + "• the review queue is emptied: %d paper(s), %d of them approved or dropped - those decisions are "
                + "lost;%n"
                + "• the matched PDF copies in the students' folders, the scored papers and the extracted papers are "
                + "cleared.%n%n"
                + "Papers already sent are kept on record, and are never queued again for the student who received "
                + "them. If Gmail can't be reached, nothing is cleared.", processed, queued.size(), decided),
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Clear and re-run");
        confirm.setHeaderText("Clear all matches and re-run the pipeline?");
        confirm.getDialogPane().setMinWidth(560);
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            run(true);
        }
    }

    /**
     * Input: whether to undo every run first (PipelineRunner.reset).
     * Output: none; one pipeline run as a job, its progress in the log and its report in the summary.
     * Used by: the Run button (false), {@link #clearAndRerun} (true).
     * Example: locked credentials -> "Not run: unlock the Gmail credentials first" and the Gmail tab opens
     */
    private void run(boolean resetFirst) {
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
            /**
             * Input: none (job thread).
             * Output: the run's report (after the reset, if asked); the credentials are cleared afterwards.
             * Used by: Session.runJob.
             * Example: -> Report(newAlerts 6, papersScored 31, matches 11, ...)
             */
            @Override
            protected Report call() throws Exception {
                try (AutoCloseable captured = log.capture()) {
                    log.line("Loading the SPECTER2 model...");
                    PipelineRunner runner = PipelineRunner.atDefaultLocations(session.model(), kind, limit, markRead);
                    GmailCredentials gmail = session.gmail();
                    try {
                        if (resetFirst) {
                            runner.reset(gmail, log::line);
                            // Show the emptied queue now, not only once the re-run has finished.
                            Platform.runLater(queueChanged);
                        }
                        return runner.run(gmail, log::line);
                    } finally {
                        gmail.clear();
                    }
                }
            }

            /**
             * Input: none (UI thread).
             * Output: none; the summary shows the run's numbers and the review queue reloads.
             * Used by: JavaFX, when call() returns.
             * Example: -> "Last run: 6 new alert(s), 31 paper(s) scored, 11 match(es) queued (11 recommended). The review queue holds 11."
             */
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

            /**
             * Input: none (UI thread).
             * Output: none; the error goes to the log and summary, and the queue reloads.
             * Used by: JavaFX, when call() throws.
             * Example: Groq down -> "Last run failed: Groq unavailable: HTTP 503 ... run again to continue."
             */
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
