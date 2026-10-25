package com.litroute.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javafx.application.Platform;
import javafx.scene.control.TextArea;

/**
 * A read-only, timestamped log of what a job is doing. While {@link #capture} is open it also shows what
 * LitRoute's own classes log at INFO and above (a Gmail login refused, a publisher blocking a download),
 * which is where the pipeline reports the problems it recovers from.
 */
final class LogPane extends TextArea {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Logger LITROUTE = Logger.getLogger("com.litroute");

    /**
     * Input: none.
     * Output: an empty, read-only, wrapping, monospace text area.
     * Used by: PipelinePane and ProfilesPane.
     * Example: {@code new LogPane()}
     */
    LogPane() {
        setEditable(false);
        setWrapText(true);
        setStyle("-fx-font-family: monospace;");
    }

    /**
     * Appends a line; safe to call from any thread.
     * Input: a line of progress (from any thread).
     * Output: none; the line is appended with an HH:mm:ss stamp, on the UI thread.
     * Used by: PipelinePane and ProfilesPane, as progress sinks ({@code log::line}).
     * Example: line("6 new alert email(s).") -> "14:02:31  6 new alert email(s)."
     */
    void line(String text) {
        String stamped = LocalTime.now().format(TIME) + "  " + text + "\n";
        if (Platform.isFxApplicationThread()) {
            appendText(stamped);
        } else {
            Platform.runLater(() -> appendText(stamped));
        }
    }

    /**
     * Shows LitRoute's log records here until the returned handle is closed.
     * Input: none.
     * Output: a handle; until it is closed, com.litroute log records at INFO and above are shown here too.
     * Used by: PipelinePane and ProfilesPane, around each job.
     * Example: a WARNING from PdfDownloader during a run -> "14:02:40  WARNING: ..."
     */
    AutoCloseable capture() {
        Handler handler = new Handler() {
            private final SimpleFormatter formatter = new SimpleFormatter();

            /**
             * Input: a log record.
             * Output: none; it is shown as a line if loggable.
             * Used by: java.util.logging.
             * Example: WARNING "Skipping a.pdf" -> line "WARNING: Skipping a.pdf"
             */
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    line(record.getLevel().getName() + ": " + formatter.formatMessage(record));
                }
            }

            /**
             * Input: none.
             * Output: none (lines are written as they come).
             * Used by: java.util.logging.
             * Example: flush() -> no effect; lines are already on screen
             */
            @Override
            public void flush() {
            }

            /**
             * Input: none.
             * Output: none (nothing to release).
             * Used by: java.util.logging.
             * Example: close() -> no effect; capture()'s handle removes the handler
             */
            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.INFO);
        LITROUTE.addHandler(handler);
        return () -> LITROUTE.removeHandler(handler);
    }
}
