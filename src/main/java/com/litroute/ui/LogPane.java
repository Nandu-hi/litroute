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

    LogPane() {
        setEditable(false);
        setWrapText(true);
        setStyle("-fx-font-family: monospace;");
    }

    /** Appends a line; safe to call from any thread. */
    void line(String text) {
        String stamped = LocalTime.now().format(TIME) + "  " + text + "\n";
        if (Platform.isFxApplicationThread()) {
            appendText(stamped);
        } else {
            Platform.runLater(() -> appendText(stamped));
        }
    }

    /** Shows LitRoute's log records here until the returned handle is closed. */
    AutoCloseable capture() {
        Handler handler = new Handler() {
            private final SimpleFormatter formatter = new SimpleFormatter();

            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    line(record.getLevel().getName() + ": " + formatter.formatMessage(record));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.INFO);
        LITROUTE.addHandler(handler);
        return () -> LITROUTE.removeHandler(handler);
    }
}
