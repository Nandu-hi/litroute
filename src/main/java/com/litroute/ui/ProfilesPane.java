package com.litroute.ui;

import com.litroute.model.Profile;
import com.litroute.profile.ProfileBuilder;
import com.litroute.profile.ProfileBuilder.BuildReport;
import com.litroute.profile.ProfileBuilder.ProfileReport;
import com.litroute.profile.ProfileBuilder.SkippedPdf;
import com.litroute.store.ProfileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/**
 * M5 from the UI: the stored profiles, and a Rebuild button that syncs them with their folders (one
 * folder of PDFs per student, plus an optional email.txt). Only new or changed PDFs are embedded.
 */
final class ProfilesPane extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Session session;
    private final Path root = ProfileBuilder.defaultProfilesRoot();
    private final ProfileStore store = ProfileStore.atDefaultLocation();
    private final TableView<Profile> table = new TableView<>();
    private final LogPane log = new LogPane();

    /**
     * Input: the session and a way to open a folder in the desktop's file manager.
     * Output: the Profiles tab: Rebuild / Open folder buttons, the profile table (M7d) and a log.
     * Used by: LitRouteApp.start.
     * Example: {@code new ProfilesPane(session, uri -> hostServices.showDocument(uri))}
     */
    ProfilesPane(Session session, Consumer<String> openDocument) {
        this.session = session;

        Button rebuild = new Button("Rebuild profiles");
        rebuild.disableProperty().bind(session.busyProperty());
        rebuild.setOnAction(event -> rebuild());
        Button openFolder = new Button("Open profiles folder");
        openFolder.setOnAction(event -> {
            try {
                Files.createDirectories(root);
                openDocument.accept(root.toUri().toString());
            } catch (IOException e) {
                log.line("Could not create " + root + ": " + e.getMessage());
            }
        });
        HBox bar = new HBox(10, rebuild, openFolder, new Label("Folder: " + root));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        table.getColumns().add(column("Profile", 200, Profile::name));
        table.getColumns().add(column("Papers", 70, profile -> String.valueOf(profile.papers().size())));
        table.getColumns().add(column("Emails", 220, profile -> profile.emails().isEmpty() ? "(no email.txt)"
                : String.join(", ", profile.emails())));
        table.getColumns().add(column("Updated", 130, profile -> WHEN.format(profile.updatedAt())));
        table.getColumns().add(column("Keywords", 600, profile -> String.join("; ", profile.keywords())));
        table.setPlaceholder(new Label("No profiles yet: create one folder of PDFs per student, then Rebuild."));

        SplitPane split = new SplitPane(table, log);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        setTop(bar);
        setCenter(split);
        reload();
    }

    /**
     * Input: none.
     * Output: none; the table shows the profiles now in M7d (errors go to the log).
     * Used by: the constructor, after a rebuild.
     * Example: -> rows alice (40 papers), bob (12 papers), ...
     */
    private void reload() {
        try {
            table.getItems().setAll(store.loadAll().values());
        } catch (IOException e) {
            log.line("Could not read the profile store: " + e.getMessage());
        }
    }

    /**
     * Input: none.
     * Output: none; ProfileBuilder.syncAll runs as a job and its report is logged, then the table reloads.
     * Used by: the Rebuild button.
     * Example: -> "alice: 1 embedded, 39 unchanged, 0 skipped" ... "Rebuild finished."
     */
    private void rebuild() {
        log.line("Rebuilding profiles from " + root + "...");
        session.runJob(new Task<BuildReport>() {
            /**
             * Input: none (job thread).
             * Output: the sync's report, with LitRoute's log shown in the pane meanwhile.
             * Used by: Session.runJob.
             * Example: -> BuildReport([alice, bob], removed [])
             */
            @Override
            protected BuildReport call() throws Exception {
                try (AutoCloseable captured = log.capture()) {
                    return new ProfileBuilder(root, store, session.model()).syncAll();
                }
            }

            /**
             * Input: none (UI thread).
             * Output: none; each profile's counts and skipped PDFs are logged, then the table reloads.
             * Used by: JavaFX, when call() returns.
             * Example: -> "bob: 0 embedded, 12 unchanged, 1 skipped", "    skipped x.pdf: password-protected"
             */
            @Override
            protected void succeeded() {
                BuildReport report = getValue();
                for (ProfileReport profile : report.profiles()) {
                    log.line(String.format("%s: %d embedded, %d unchanged, %d skipped%s", profile.name(),
                            profile.embedded(), profile.reused(), profile.skipped().size(),
                            profile.stored() ? "" : " - no readable PDF, not saved"));
                    for (SkippedPdf skipped : profile.skipped()) {
                        log.line("    skipped " + skipped.relativePath() + ": " + skipped.reason());
                    }
                }
                report.removed().forEach(name -> log.line("Removed " + name + " (folder deleted)"));
                log.line("Rebuild finished.");
                reload();
            }

            /**
             * Input: none (UI thread).
             * Output: none; the error is logged and the table reloads.
             * Used by: JavaFX, when call() throws.
             * Example: model missing -> "Rebuild failed: java.io.IOException: SPECTER2 model not found ..."
             */
            @Override
            protected void failed() {
                log.line("Rebuild failed: " + getException());
                reload();
            }
        });
    }

    /**
     * Input: a header, a width and how to show a profile in it.
     * Output: a read-only text column.
     * Used by: the constructor.
     * Example: column("Papers", 70, p -> "40")
     */
    private static TableColumn<Profile, String> column(String name, double width, Function<Profile, String> value) {
        TableColumn<Profile, String> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }
}
