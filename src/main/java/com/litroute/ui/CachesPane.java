package com.litroute.ui;

import com.litroute.store.DataCaches;
import com.litroute.store.DataCaches.Cache;
import com.litroute.store.DataCaches.Report;
import com.litroute.store.DataCaches.Usage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * How much each of the pipeline's caches holds ({@link DataCaches}), and clearing them. Choosing a cache shows what
 * clearing it means; Clear only works once the professor has ticked that they accept that, and the tick is undone
 * whenever the choice changes, so it can't carry over to a cache they haven't read about. In the window, not a
 * dialog, which a tiling window manager would tile out of sight. Clearing is a job, so it never overlaps a pipeline
 * run.
 */
final class CachesPane extends BorderPane {

    private final Session session;
    private final Runnable cleared;
    private final DataCaches caches = DataCaches.atDefaultLocation();
    private final TableView<Usage> table = new TableView<>();
    private final Label total = new Label();
    private final TextArea consequences = new TextArea();
    private final CheckBox agree = new CheckBox("I understand these consequences and want to clear what is selected");
    private final Label status = new Label();

    /**
     * Input: the session, and what to reload after a clear changes what other tabs show.
     * Output: the Caches tab: Refresh and the total, a row per cache, the chosen caches' consequences, the agreement and Clear.
     * Used by: LitRouteApp.start.
     * Example: {@code new CachesPane(session, review::reload)}
     */
    CachesPane(Session session, Runnable cleared) {
        this.session = session;
        this.cleared = cleared;

        Button refresh = new Button("Refresh");
        refresh.setOnAction(event -> refresh());
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        total.setWrapText(true);
        HBox bar = new HBox(10, refresh, total);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        table.getColumns().add(column("Cache", 140, usage -> usage.cache().toString()));
        table.getColumns().add(column("Size", 80, usage -> humanSize(usage.bytes())));
        table.getColumns().add(column("Holds", 340, Usage::contents));
        table.getColumns().add(column("Where", 230, Usage::where));
        table.getColumns().forEach(column -> column.setSortable(false));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("Reading the caches..."));
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Usage>) change -> showConsequences());

        consequences.setEditable(false);
        consequences.setWrapText(true);
        consequences.setPrefRowCount(9);
        Button clear = new Button("Clear selected");
        clear.setStyle("-fx-base: #ffcdd2;");
        clear.disableProperty().bind(session.busyProperty().or(agree.selectedProperty().not())
                .or(Bindings.isEmpty(table.getSelectionModel().getSelectedItems())));
        clear.setOnAction(event -> clearSelected());
        agree.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        HBox confirm = new HBox(10, agree, clear);
        confirm.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        VBox bottom = new VBox(8, new Label("What clearing means:"), consequences, confirm, status);
        bottom.setPadding(new Insets(8));

        setTop(bar);
        setCenter(table);
        setBottom(bottom);
        BorderPane.setMargin(table, new Insets(0, 8, 0, 8));
        showConsequences();
    }

    /**
     * Measures the caches off the UI thread, keeping the chosen ones chosen (which still undoes the agreement).
     * Input: none.
     * Output: none; the table and the total show the caches as they stand.
     * Used by: LitRouteApp (on opening the tab, when a job ends), Refresh, after a clear.
     * Example: -> "Student profiles   13.6 MB   8 profile(s), 294 paper(s)   profiles.json"
     */
    void refresh() {
        session.runIo(new Task<Report>() {
            /**
             * Input: none (io thread).
             * Output: every cache's size and contents.
             * Used by: Session.runIo.
             * Example: -> Report([...], 180000)
             */
            @Override
            protected Report call() throws Exception {
                return caches.report();
            }

            /**
             * Input: none (UI thread).
             * Output: none; the rows and the total are replaced, and the same caches chosen again.
             * Used by: JavaFX, when call() returns.
             * Example: -> "28.1 MB in 7 caches in /home/u/.litroute/data, ..."
             */
            @Override
            protected void succeeded() {
                Report report = getValue();
                Set<Cache> chosen = chosen();
                table.getItems().setAll(report.caches());
                for (Usage usage : report.caches()) {
                    if (chosen.contains(usage.cache())) {
                        table.getSelectionModel().select(usage);
                    }
                }
                total.setText(humanSize(report.totalBytes()) + " in " + report.caches().size() + " caches in "
                        + caches.folder() + ", and " + humanSize(report.otherBytes())
                        + " in other files there, which are never cleared here.");
            }

            /**
             * Input: none (UI thread).
             * Output: none; the error is shown in the status line.
             * Used by: JavaFX, when call() throws.
             * Example: -> "Could not measure the caches: ..."
             */
            @Override
            protected void failed() {
                error("Could not measure the caches", getException());
            }
        });
    }

    /**
     * Input: none (the chosen rows).
     * Output: none; the agreement is undone, and the consequences of the chosen caches are shown.
     * Used by: the table's selection listener, the constructor.
     * Example: Sent papers chosen -> its size, contents and "A student can get the same paper twice ..."
     */
    private void showConsequences() {
        agree.setSelected(false);
        List<String> sections = new ArrayList<>();
        for (Usage usage : List.copyOf(table.getSelectionModel().getSelectedItems())) {
            if (usage != null) {
                sections.add(usage.cache() + " (" + humanSize(usage.bytes()) + ", " + usage.contents() + "):\n"
                        + consequenceOf(usage.cache()));
            }
        }
        consequences.setText(sections.isEmpty() ? "Choose one or more caches above; Ctrl-click to choose several."
                : String.join("\n\n", sections) + "\n\nThis can't be undone. Your Gmail isn't touched: to process the "
                        + "emails again from scratch, use Clear and re-run on the Pipeline tab instead.");
    }

    /**
     * Input: a cache.
     * Output: what clearing it costs, and what it leaves.
     * Used by: {@link #showConsequences}.
     * Example: SCORED_PAPERS -> "The next run matches every extracted paper again ..."
     */
    private static String consequenceOf(Cache cache) {
        return switch (cache) {
            case REVIEW_QUEUE -> "Every paper in the Review tab goes, with your approve and drop decisions and its PDF "
                    + "copy; approved papers not yet sent will not be sent. They don't come back on the next run, "
                    + "because they stay recorded as scored. Clear Scored papers too to have them matched again, as "
                    + "pending, with your decisions gone.";
            case SCORED_PAPERS -> "The next run matches every paper in Extracted papers again (seconds per paper with a "
                    + "PDF), except those in the review queue. Papers turned down before may now be queued. A paper "
                    + "already sent isn't queued for the student who received it, but it could be queued for another.";
            case SENT_PAPERS -> "The record of what each student received goes, with those papers' PDF copies, and the "
                    + "Review tab's Sent list empties. Nothing else stops a student getting a paper twice: a paper sent "
                    + "earlier that is matched again (after Clear and re-run, or clearing Scored papers) can be queued "
                    + "and emailed to the same student again.";
            case EXTRACTED_PAPERS -> "The papers read from alert emails and their looked-up details go. Papers not yet "
                    + "matched (waiting for a profile, say) are lost, because their emails stay recorded as processed. "
                    + "A paper seen again in a later alert is looked up online again. Adding an author can no longer "
                    + "bring back these papers. The review queue and sent papers aren't affected.";
            case PROCESSED_EMAILS -> "LitRoute forgets which alert emails it has read. Emails still unread in Gmail (Keep "
                    + "emails unread) are read again on the next run. Emails already marked read are not, and Clear and "
                    + "re-run can no longer mark them unread, because it finds them through this list, so they can't "
                    + "be processed again without marking them unread by hand.";
            case DOWNLOADED_PDFS -> "The PDFs downloaded for alert papers go; the review queue and sent papers keep their "
                    + "own copies. Papers not yet matched are scored from their abstract or title instead, which is "
                    + "less accurate. A paper seen again in a later alert is downloaded again.";
            case PROFILES -> "Every student's embedded profile goes; their PDF folders stay. The next Run pipeline or "
                    + "Rebuild profiles embeds every PDF again, at 2-3 seconds each (about 12 minutes for 300). Until then "
                    + "nothing can be matched, and Send approved finds no student addresses.";
        };
    }

    /**
     * Input: none (the chosen rows, agreed to).
     * Output: none; as a job, they are cleared; the status line says what went, and the tabs reload.
     * Used by: the Clear selected button.
     * Example: Sent papers -> "Cleared Sent papers, 2.5 MB."
     */
    private void clearSelected() {
        Set<Cache> chosen = chosen();
        long bytes = table.getSelectionModel().getSelectedItems().stream().filter(Objects::nonNull)
                .mapToLong(Usage::bytes).sum();
        String what = chosen.stream().map(Cache::toString).collect(Collectors.joining(", "));
        agree.setSelected(false);
        status.setStyle("");
        status.setText("Clearing " + what + "...");
        session.runJob(new Task<Void>() {
            /**
             * Input: none (job thread).
             * Output: none; the chosen caches are empty.
             * Used by: Session.runJob.
             * Example: -> sent_papers.json gone
             */
            @Override
            protected Void call() throws Exception {
                caches.clear(chosen);
                return null;
            }

            /**
             * Input: none (UI thread).
             * Output: none; the status line says what went, the choice is dropped, and the tabs reload.
             * Used by: JavaFX, when call() returns.
             * Example: -> "Cleared Sent papers, 2.5 MB."
             */
            @Override
            protected void succeeded() {
                status.setText("Cleared " + what + ", " + humanSize(bytes) + ".");
                table.getSelectionModel().clearSelection();
                cleared.run();
                refresh();
            }

            /**
             * Input: none (UI thread).
             * Output: none; the error is shown, and the tabs reload, since caches before the failed one are cleared.
             * Used by: JavaFX, when call() throws.
             * Example: corrupt sent_papers.json -> "Clearing stopped: ..."
             */
            @Override
            protected void failed() {
                error("Clearing stopped; the caches above show what is left", getException());
                cleared.run();
                refresh();
            }
        });
    }

    /**
     * Input: none.
     * Output: the caches of the chosen rows.
     * Used by: refresh, clearSelected.
     * Example: rows Sent papers and Student profiles chosen -> {SENT_PAPERS, PROFILES}
     */
    private Set<Cache> chosen() {
        Set<Cache> chosen = EnumSet.noneOf(Cache.class);
        table.getSelectionModel().getSelectedItems().stream().filter(Objects::nonNull).map(Usage::cache).forEach(chosen::add);
        return chosen;
    }

    /**
     * Input: what failed and why.
     * Output: none; shown in red in the status line (not a dialog).
     * Used by: refresh, clearSelected.
     * Example: -> "Could not measure the caches: ..."
     */
    private void error(String what, Throwable cause) {
        status.setStyle("-fx-text-fill: #b00020;");
        status.setText(what + ": " + cause.getMessage());
    }

    /**
     * Input: a header, a width and how to show a cache in it.
     * Output: a read-only text column.
     * Used by: the constructor.
     * Example: column("Size", 90, usage -> "2.5 MB")
     */
    private static TableColumn<Usage, String> column(String name, double width, Function<Usage, String> value) {
        TableColumn<Usage, String> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }

    /**
     * Input: a size in bytes.
     * Output: it for a person to read, in the largest unit that keeps it at 1 or more.
     * Used by: the Size column, the total, the consequences, the status line.
     * Example: 427 -> "427 B"; 14222244 -> "13.6 MB"
     */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double size = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }
}
