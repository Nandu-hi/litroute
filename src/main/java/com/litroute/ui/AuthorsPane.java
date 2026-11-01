package com.litroute.ui;

import com.litroute.model.AuthorName;
import com.litroute.pipeline.PriorityAuthors;
import com.litroute.scoring.RelevanceScorer;
import java.io.IOException;
import java.util.Locale;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The authors whose papers M6 favours: their papers are held to a lower cutoff and listed first in the Review
 * tab, with the author given as the reason. An author is entered as a first and a last name, and matches strictly
 * ({@link AuthorName#matches}). A change is a job ({@link PriorityAuthors} writes the log a pipeline run writes), and
 * takes effect on the next run.
 */
final class AuthorsPane extends BorderPane {

    private final Session session;
    private final PriorityAuthors authors = PriorityAuthors.atDefaultLocations();
    private final ObservableList<AuthorName> names = FXCollections.observableArrayList();
    private final ListView<AuthorName> list = new ListView<>(names);
    private final TextField firstName = new TextField();
    private final TextField lastName = new TextField();
    private final Label status = new Label();

    /**
     * Input: the session.
     * Output: the Authors tab: what the list does, the names, first and last name fields, and Add / Remove.
     * Used by: LitRouteApp.start.
     * Example: {@code new AuthorsPane(session)}
     */
    AuthorsPane(Session session) {
        this.session = session;

        Label about = new Label(String.format(Locale.ROOT, "Papers by these authors are listed first in the Review tab, "
                + "with the author given as the reason for the match, and need a relevance of only %.2f instead of %.2f "
                + "(from a title alone, %.2f instead of %.2f). A paper that doesn't fit any student's work is still "
                + "ignored, whoever wrote it.%n%nNames match strictly: the paper must print the first and last name in "
                + "full. Alice Carter finds Alice Carter and Alice K. Carter, but not A. Carter, AK Carter, Alice, or "
                + "Carter.", RelevanceScorer.AUTHOR_FLOOR, RelevanceScorer.RELEVANCE_FLOOR,
                RelevanceScorer.AUTHOR_TITLE_FLOOR, RelevanceScorer.TITLE_FLOOR));
        about.setWrapText(true);

        firstName.setPromptText("First name, e.g. Alice");
        firstName.setOnAction(event -> add());
        HBox.setHgrow(firstName, Priority.ALWAYS);
        lastName.setPromptText("Last name, e.g. Carter");
        lastName.setOnAction(event -> add());
        HBox.setHgrow(lastName, Priority.ALWAYS);
        Button add = new Button("Add");
        add.disableProperty().bind(session.busyProperty().or(firstName.textProperty().isEmpty())
                .or(lastName.textProperty().isEmpty()));
        add.setOnAction(event -> add());
        Button remove = new Button("Remove");
        remove.disableProperty().bind(session.busyProperty()
                .or(Bindings.isNull(list.getSelectionModel().selectedItemProperty())));
        remove.setOnAction(event -> remove());
        HBox bar = new HBox(10, firstName, lastName, add, remove);
        bar.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);

        list.setPlaceholder(new Label("No authors yet: add one below."));
        VBox bottom = new VBox(8, bar, status);
        bottom.setPadding(new Insets(8));
        about.setPadding(new Insets(8));
        setTop(about);
        setCenter(list);
        setBottom(bottom);
        BorderPane.setMargin(list, new Insets(0, 8, 0, 8));
        try {
            names.setAll(authors.list());
        } catch (IOException e) {
            error("Could not read the authors list", e);
        }
    }

    /**
     * Input: none (the first and last name typed).
     * Output: none; as a job, the name joins the list and its held papers are forgotten by the scored log; a missing part, an initial or a name listed already is refused in the status line.
     * Used by: the Add button, Enter in either name field.
     * Example: "A." and "Carter" -> "Spell out both names: an initial can't identify an author."
     */
    private void add() {
        AuthorName typed;
        try {
            typed = new AuthorName(firstName.getText(), lastName.getText());
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        if (names.stream().anyMatch(typed::sameAs)) {
            error(typed + " is already on the list.");
            return;
        }
        session.runJob(new Task<Integer>() {
            /**
             * Input: none (job thread).
             * Output: how many held papers the next run will score again.
             * Used by: Session.runJob.
             * Example: -> 2
             */
            @Override
            protected Integer call() throws IOException {
                return authors.add(typed);
            }

            /**
             * Input: none (UI thread).
             * Output: none; the list shows the name, and the status line what happens next.
             * Used by: JavaFX, when call() returns.
             * Example: 2 -> "Added Alice Carter. The next run looks again at 2 paper(s) of theirs it had turned down."
             */
            @Override
            protected void succeeded() {
                firstName.clear();
                lastName.clear();
                show();
                int again = getValue();
                status.setStyle("");
                status.setText("Added " + typed + ". " + (again > 0
                        ? "The next run looks again at " + again + " paper(s) of theirs it had turned down."
                        : "Their papers are favoured from the next run on."));
            }

            /**
             * Input: none (UI thread).
             * Output: none; the error is shown in the status line.
             * Used by: JavaFX, when call() throws.
             * Example: -> "Could not add the author: disk full"
             */
            @Override
            protected void failed() {
                show();
                error("Could not add the author", getException());
            }
        });
    }

    /**
     * Input: none (the name selected).
     * Output: none; as a job, the name leaves the list.
     * Used by: the Remove button.
     * Example: -> "Removed Alice Carter. Papers already queued for their sake stay queued."
     */
    private void remove() {
        AuthorName chosen = list.getSelectionModel().getSelectedItem();
        session.runJob(new Task<Void>() {
            /**
             * Input: none (job thread).
             * Output: none; the list is saved without the name.
             * Used by: Session.runJob.
             * Example: -> priority_authors.json without it
             */
            @Override
            protected Void call() throws IOException {
                authors.remove(chosen);
                return null;
            }

            /**
             * Input: none (UI thread).
             * Output: none; the list no longer shows the name, and the status line says what stays.
             * Used by: JavaFX, when call() returns.
             * Example: -> "Removed Alice Carter. Papers already queued for their sake stay queued."
             */
            @Override
            protected void succeeded() {
                show();
                status.setStyle("");
                status.setText("Removed " + chosen + ". Papers already queued for their sake stay queued.");
            }

            /**
             * Input: none (UI thread).
             * Output: none; the error is shown in the status line.
             * Used by: JavaFX, when call() throws.
             * Example: -> "Could not remove the author: disk full"
             */
            @Override
            protected void failed() {
                show();
                error("Could not remove the author", getException());
            }
        });
    }

    /**
     * Input: none.
     * Output: none; the list shows the names now saved.
     * Used by: add and remove, when their job ends.
     * Example: -> rows Alice Carter, John van de Lindt
     */
    private void show() {
        try {
            names.setAll(authors.list());
        } catch (IOException e) {
            error("Could not read the authors list", e);
        }
    }

    /**
     * Input: what failed and why.
     * Output: none; shown in red in the status line (not a dialog).
     * Used by: the constructor, add, remove, show.
     * Example: -> "Could not add the author: disk full"
     */
    private void error(String what, Throwable cause) {
        error(what + ": " + cause.getMessage());
    }

    /**
     * Input: a message.
     * Output: none; shown in red in the status line.
     * Used by: add, {@link #error(String, Throwable)}.
     * Example: -> "Alice Carter is already on the list."
     */
    private void error(String message) {
        status.setStyle("-fx-text-fill: #b00020;");
        status.setText(message);
    }
}
