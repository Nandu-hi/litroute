package com.litroute.ui;

import com.litroute.store.ScoredQueueStore;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * M8: the professor's desktop UI. Four tabs - Review (the M7e queue: approve or drop each matched paper, and
 * email the approved ones to the students),
 * Pipeline (run M9 on demand), Profiles (rebuild M5) and Gmail (unlock, set up or rotate the credentials).
 * Everything happens inside this one window, with no dialogs, so a tiling window manager cannot hide a
 * modal prompt behind it. While the credentials are locked a banner says so, and LitRoute opens on the
 * Gmail tab.
 *
 * <pre>
 * mvn -q compile javafx:run
 * </pre>
 */
public final class LitRouteApp extends Application {

    private final Session session = new Session();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Consumer<String> openDocument = uri -> getHostServices().showDocument(uri);
        CredentialsPane credentials = new CredentialsPane(session);
        Tab gmailTab = new Tab("Gmail", credentials);
        TabPane tabs = new TabPane();
        Runnable showCredentials = () -> {
            tabs.getSelectionModel().select(gmailTab);
            Platform.runLater(credentials::focusNextField);
        };
        ReviewPane review = new ReviewPane(session, ScoredQueueStore.atDefaultLocation(), openDocument, showCredentials);
        Tab reviewTab = new Tab("Review", review);
        tabs.getTabs().addAll(reviewTab,
                new Tab("Pipeline", new PipelinePane(session, review::reload, showCredentials)),
                new Tab("Profiles", new ProfilesPane(session, openDocument)),
                gmailTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == reviewTab) {
                review.reload();
            }
        });

        MenuItem gmail = new MenuItem("Gmail credentials");
        gmail.setOnAction(event -> showCredentials.run());
        MenuItem quit = new MenuItem("Quit");
        quit.setOnAction(event -> Platform.exit());

        Label locked = new Label("Gmail credentials are locked: unlock them to run the pipeline or send "
                + "approved papers. Reviewing works without them.");
        Button unlock = new Button("Unlock...");
        unlock.setOnAction(event -> showCredentials.run());
        HBox banner = new HBox(10, locked, unlock);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(6, 10, 6, 10));
        banner.setStyle("-fx-background-color: #fff3cd;");
        banner.visibleProperty().bind(session.unlockedProperty().not());
        banner.managedProperty().bind(banner.visibleProperty());

        BorderPane root = new BorderPane(tabs);
        root.setTop(new VBox(new MenuBar(new Menu("File", null, gmail, quit)), banner));

        stage.setTitle("LitRoute");
        stage.setScene(new Scene(root, 1280, 820));
        stage.show();
        showCredentials.run();
    }

    @Override
    public void stop() {
        session.close();
    }
}
