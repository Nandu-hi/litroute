package com.litroute.ui;

import com.litroute.delivery.ApprovedPaperSender;
import com.litroute.delivery.ApprovedPaperSender.Report;
import com.litroute.delivery.GmailSmtpSender;
import com.litroute.model.PaperCandidate;
import com.litroute.model.ReviewStatus;
import com.litroute.model.ScoredPaper;
import com.litroute.scoring.RelevanceScorer;
import com.litroute.security.GmailCredentials;
import com.litroute.store.ProfileStore;
import com.litroute.store.ScoredQueueStore;
import com.litroute.store.SentPaperStore;
import com.litroute.store.SentPaperStore.Sent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.util.Duration;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * M8's review screen over the M7e queue: the students on the left with how many of their papers wait
 * for a decision, their matched papers in the middle (always best score first), and the chosen
 * paper's details and M6's rationale below. Approve, Drop and Back to pending (or the A, D and P keys)
 * record the decision for every selected paper; the queue file is written off the UI thread. Send approved
 * emails every approved paper to its student (M10), which moves it from the queue to the sent papers; it
 * needs the Gmail credentials unlocked. Show: Sent lists the sent papers, read only.
 */
final class ReviewPane extends BorderPane {

    private static final String ALL_STUDENTS = "All students";
    private static final DateTimeFormatter SENT_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Which entries the table shows. */
    private enum Filter {
        PENDING("Pending"), APPROVED("Approved"), DROPPED("Dropped"), SENT("Sent"), ALL("All");

        private final String label;

        /**
         * Input: the label shown in the Show box.
         * Output: the filter constant.
         * Used by: the enum constants.
         * Example: APPROVED -> "Approved"
         */
        Filter(String label) {
            this.label = label;
        }

        /**
         * Input: a paper's status.
         * Output: true if this filter shows it (ALL shows everything).
         * Used by: {@link ReviewPane#applyFilter}.
         * Example: PENDING.accepts(APPROVED) -> false; ALL.accepts(SENT) -> true
         */
        boolean accepts(ReviewStatus status) {
            return this == ALL || name().equals(status.name());
        }

        /**
         * Input: none.
         * Output: the label shown in the Show box.
         * Used by: the filter ComboBox.
         * Example: SENT -> "Sent"
         */
        @Override
        public String toString() {
            return label;
        }
    }

    private final Session session;
    private final ScoredQueueStore queue;
    private final SentPaperStore sent;
    /** When each paper shown as sent went, by {@link ScoredPaper#queueKey()}. */
    private final Map<String, Instant> sentAt = new HashMap<>();
    private final Consumer<String> openDocument;
    private final Runnable showCredentials;
    private final ObservableList<ScoredPaper> entries = FXCollections.observableArrayList();
    private final FilteredList<ScoredPaper> shown = new FilteredList<>(entries);
    private final ObservableList<String> students = FXCollections.observableArrayList();
    private final ListView<String> studentList = new ListView<>(students);
    private final ComboBox<Filter> filter = new ComboBox<>(FXCollections.observableArrayList(Filter.values()));
    private final CheckBox recommendedOnly = new CheckBox("Recommended only");
    private final TableView<ScoredPaper> table = new TableView<>();
    private final Label summary = new Label();
    private final Button send = new Button();
    private final BooleanProperty noneApproved = new SimpleBooleanProperty(true);
    private final Detail detail = new Detail();

    /**
     * Input: the session, the M7e queue, the sent papers, a way to open files/links, and a way to show the Gmail tab.
     * Output: the Review tab: student list, paper table, detail pane, Show/Recommended filters, Send approved and Refresh.
     * Used by: LitRouteApp.start.
     * Example: {@code new ReviewPane(session, ScoredQueueStore.atDefaultLocation(), SentPaperStore.atDefaultLocation(), open, showGmail)}
     */
    ReviewPane(Session session, ScoredQueueStore queue, SentPaperStore sent, Consumer<String> openDocument,
            Runnable showCredentials) {
        this.session = session;
        this.queue = queue;
        this.sent = sent;
        this.openDocument = openDocument;
        this.showCredentials = showCredentials;

        studentList.setCellFactory(list -> new ListCell<>() {
            /**
             * Input: a student name (or "All students") and whether the cell is empty.
             * Output: none; the cell shows the name and its pending count.
             * Used by: JavaFX, for each student list cell.
             * Example: "alice" -> "alice   (3 pending)"
             */
            @Override
            protected void updateItem(String student, boolean empty) {
                super.updateItem(student, empty);
                setText(empty || student == null ? null : student + "   (" + pending(student) + " pending)");
            }
        });
        studentList.setPrefWidth(250);
        studentList.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> applyFilter());
        filter.getSelectionModel().select(Filter.PENDING);
        filter.valueProperty().addListener((o, was, now) -> applyFilter());
        recommendedOnly.selectedProperty().addListener((o, was, now) -> applyFilter());
        Button refresh = new Button("Refresh");
        refresh.setOnAction(event -> reload());
        send.setOnAction(event -> sendApproved());
        send.disableProperty().bind(session.busyProperty().or(noneApproved));
        send.setTooltip(new Tooltip("Email every approved paper to its student, with the PDFs attached"));

        buildTable();
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, new Label("Show:"), filter, recommendedOnly, spacer, summary, send, refresh);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        SplitPane papers = new SplitPane(table, detail);
        papers.setOrientation(Orientation.VERTICAL);
        papers.setDividerPositions(0.55);
        SplitPane split = new SplitPane(studentList, papers);
        split.setDividerPositions(0.22);
        SplitPane.setResizableWithParent(studentList, false);
        setTop(bar);
        setCenter(split);
        reload();
    }

    /**
     * Reads the queue and the sent papers again, keeping the chosen student; call after the pipeline adds papers.
     * Input: none.
     * Output: none; the queue (M7e) and sent papers are read again, keeping the chosen student; a paper both queued and sent shows as sent.
     * Used by: LitRouteApp (on selecting the tab), PipelinePane (after a run), Refresh, sendApproved.
     * Example: after a run added 2 papers for alice -> "alice   (5 pending)"
     */
    void reload() {
        try {
            List<Sent> sentPapers = sent.load();
            sentAt.clear();
            sentPapers.forEach(entry -> sentAt.put(entry.paper().queueKey(), entry.sentAt()));
            // A paper recorded as sent but not yet taken out of the queue (a crash in between) shows as sent.
            List<ScoredPaper> loaded = new ArrayList<>(queue.load().stream()
                    .filter(paper -> !sentAt.containsKey(paper.queueKey())).toList());
            sentPapers.forEach(entry -> loaded.add(entry.paper()));
            String chosen = studentList.getSelectionModel().getSelectedItem();
            entries.setAll(loaded);
            List<String> names = new ArrayList<>(List.of(ALL_STUDENTS));
            loaded.stream().map(ScoredPaper::profileName).distinct().sorted().forEach(names::add);
            students.setAll(names);
            studentList.getSelectionModel().select(chosen != null && names.contains(chosen) ? chosen : ALL_STUDENTS);
            applyFilter();
        } catch (java.io.IOException e) {
            error("Could not read the review queue", e);
        }
    }

    /**
     * Input: none.
     * Output: none; the table's columns, best-score-first order, double-click/Enter to open, and A/D/P keys.
     * Used by: the constructor.
     * Example: press A on a selected row -> decide(APPROVED)
     */
    private void buildTable() {
        table.getColumns().add(column("", 32, paper -> paper.recommended() ? "★" : ""));
        table.getColumns().add(explained(column("Score", 60, paper -> String.format(Locale.ROOT, "%.2f", paper.score())),
                SCORE_HELP, ReviewPane::scoreHelp));
        table.getColumns().add(explained(column("Relevance", 80,
                paper -> paper.relevance().map(r -> String.format(Locale.ROOT, "%.2f", r)).orElse("–")),
                RELEVANCE_HELP, ReviewPane::relevanceHelp));
        TableColumn<ScoredPaper, String> title = column("Title", 520, paper -> paper.candidate().title());
        table.getColumns().add(title);
        table.getColumns().add(column("Student", 160, ScoredPaper::profileName));
        table.getColumns().add(column("Venue", 200, paper -> paper.candidate().venue().orElse("")));
        table.getColumns().add(column("Year", 55, paper -> paper.candidate().year().map(String::valueOf).orElse("")));
        table.getColumns().add(column("PDF", 45, paper -> paper.candidate().pdfPath().isPresent() ? "yes" : ""));
        table.getColumns().add(column("Status", 85, paper -> paper.status().name().toLowerCase(Locale.ROOT)));

        // Always best score first, whatever the papers' status or star; the columns don't re-sort.
        table.getColumns().forEach(column -> column.setSortable(false));
        SortedList<ScoredPaper> sorted = new SortedList<>(shown,
                Comparator.comparingDouble(ScoredPaper::score).reversed());
        table.setItems(sorted);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("No papers here. Run the pipeline to fetch and score new alerts."));
        table.setRowFactory(view -> {
            TableRow<ScoredPaper> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    open(row.getItem());
                }
            });
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> detail.show(now));
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.A) {
                decide(ReviewStatus.APPROVED);
            } else if (event.getCode() == KeyCode.D) {
                decide(ReviewStatus.DROPPED);
            } else if (event.getCode() == KeyCode.P) {
                decide(ReviewStatus.PENDING);
            } else if (event.getCode() == KeyCode.ENTER) {
                Optional.ofNullable(table.getSelectionModel().getSelectedItem()).ifPresent(this::open);
            }
        });
    }

    private static final String SCORE_HELP = "Score = relevance × share. The table is sorted by it, best first.\n\n"
            + "Share: how much of the paper's fit belongs to this student rather than to the other students "
            + "(the shares of all students add up to 1). A paper that suits two students splits its share, so it "
            + "can score low and still be a strong match - check its relevance.\n\n"
            + "Papers matched by the AI matcher show its own 0-100 score, divided by 100.";
    private static final String RELEVANCE_HELP = String.format(Locale.ROOT, "How close the paper is to this student's "
            + "own papers, whoever else it suits.\n\n0.9: as close as the student's own papers typically are to each "
            + "other.\n0.5: halfway - no closer to this student than to the others.\n0.1: as close as another "
            + "student's papers.\n\nA paper is matched only at %.2f or more, or %.2f when only its title is known (a "
            + "title alone seldom scores high). A paper's title can lift the relevance of its abstract when it looks "
            + "closer still. \"–\" means the matcher did not measure it (the AI matcher).",
            RelevanceScorer.RELEVANCE_FLOOR, RelevanceScorer.TITLE_FLOOR);

    /**
     * Puts the column's explanation on its header and a paper-specific one on each of its cells.
     * Input: a column, its header help, and per-paper cell help.
     * Output: the column with tooltips on its header and cells.
     * Used by: {@link #buildTable} (Score, Relevance).
     * Example: hover a Score cell -> "0.62 = relevance 0.81 × share 0.76 ..."
     */
    private static TableColumn<ScoredPaper, String> explained(TableColumn<ScoredPaper, String> column, String help,
            Function<ScoredPaper, String> cellHelp) {
        Label header = new Label(column.getText());
        header.setTooltip(tooltip(help));
        column.setText("");
        column.setGraphic(header);
        column.setCellFactory(view -> new TableCell<>() {
            /**
             * Input: the cell's text and whether it is empty.
             * Output: none; the cell shows the text and the paper's own tooltip.
             * Used by: JavaFX, for each Score/Relevance cell.
             * Example: value "0.62" -> text 0.62 with the score explanation as tooltip
             */
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                ScoredPaper paper = empty || getTableRow() == null ? null : getTableRow().getItem();
                setText(empty ? null : value);
                setTooltip(paper == null ? null : tooltip(cellHelp.apply(paper)));
            }
        });
        return column;
    }

    /**
     * Input: tooltip text.
     * Output: a wrapping tooltip (420 px wide, 250 ms delay, shown 30 s).
     * Used by: explained.
     * Example: tooltip("Score = relevance × share ...")
     */
    private static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        tooltip.setShowDelay(Duration.millis(250));
        tooltip.setShowDuration(Duration.seconds(30));
        return tooltip;
    }

    /**
     * Input: a scored paper.
     * Output: an explanation of its score: relevance × share, or the AI matcher's 0-100 score.
     * Used by: explained, for Score cells.
     * Example: score 0.40, relevance 0.80 -> "0.40 = relevance 0.80 × share 0.50 ..."
     */
    private static String scoreHelp(ScoredPaper paper) {
        if (paper.relevance().isEmpty()) {
            return String.format(Locale.ROOT, "%.2f: the AI matcher's score for %s (0-100, divided by 100).", paper.score(),
                    paper.profileName());
        }
        double relevance = paper.relevance().get();
        double share = relevance > 0 ? paper.score() / relevance : 0;
        return String.format(Locale.ROOT, "%.2f = relevance %.2f × share %.2f\n\n%.0f%% of this paper's fit goes to %s; "
                + "the rest is spread over the other students.%s", paper.score(), relevance, share, 100 * share,
                paper.profileName(), share < 0.5
                        ? " Below half: another student's work is also close to this paper, which lowers the score."
                        : "");
    }

    /**
     * Input: a scored paper.
     * Output: what its relevance means against the floor, and what it was measured from.
     * Used by: explained, for Relevance cells.
     * Example: 0.75 from a full PDF -> "0.75 for alice: close enough to match (at least 0.70 ...). Measured from its full PDF."
     */
    private static String relevanceHelp(ScoredPaper paper) {
        if (paper.relevance().isEmpty()) {
            return "Not measured: the AI matcher scores papers without comparing them with the students' papers.";
        }
        double relevance = paper.relevance().get();
        double floor = paper.rationale().contains("title alone") ? RelevanceScorer.TITLE_FLOOR : RelevanceScorer.RELEVANCE_FLOOR;
        String meaning = relevance >= 0.8 ? "about as close as the student's own papers are to each other"
                : relevance >= floor ? String.format(Locale.ROOT, "close enough to match (at least %.2f for this evidence)", floor)
                : relevance >= 0.5 ? "only somewhat closer to this student's papers than to others' - below the match floor"
                : "no closer to this student's papers than to other students' papers";
        String basis = paper.rationale().contains("full PDF") ? "its full PDF"
                : paper.rationale().contains("title and abstract") ? "its title and abstract"
                : paper.rationale().contains("title alone") ? "its title alone, so it is less certain" : null;
        return String.format(Locale.ROOT, "%.2f for %s: %s.%s", relevance, paper.profileName(), meaning,
                basis == null ? "" : "\n\nMeasured from " + basis + ".");
    }

    /**
     * Input: a header, a width and how to show a paper in it.
     * Output: a read-only text column.
     * Used by: {@link #buildTable}.
     * Example: column("Year", 55, p -> "2024")
     */
    private static TableColumn<ScoredPaper, String> column(String name, double width,
            Function<ScoredPaper, String> value) {
        TableColumn<ScoredPaper, String> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }

    /**
     * Input: none.
     * Output: none; the table shows the chosen student's papers with the chosen status (and star), the counts and Send button update.
     * Used by: the student list, filter boxes, reload, decide.
     * Example: Show: Approved, student alice -> only alice's approved papers; "Send approved (4)"
     */
    private void applyFilter() {
        String student = studentList.getSelectionModel().getSelectedItem();
        Filter status = filter.getValue();
        boolean onlyRecommended = recommendedOnly.isSelected();
        shown.setPredicate(paper -> (student == null || student.equals(ALL_STUDENTS) || student.equals(paper.profileName()))
                && status.accepts(paper.status())
                && (!onlyRecommended || paper.recommended()));
        long pending = entries.stream().filter(paper -> paper.status() == ReviewStatus.PENDING).count();
        long approved = entries.stream().filter(paper -> paper.status() == ReviewStatus.APPROVED).count();
        long sentCount = entries.stream().filter(paper -> paper.status() == ReviewStatus.SENT).count();
        summary.setStyle("");
        summary.setText((entries.size() - sentCount) + " queued: " + pending + " pending, " + approved + " approved · "
                + sentCount + " sent");
        send.setText("Send approved (" + approved + ")");
        noneApproved.set(approved == 0);
        studentList.refresh();
        if (table.getSelectionModel().isEmpty() && !table.getItems().isEmpty()) {
            table.getSelectionModel().select(0);
        }
        detail.show(table.getSelectionModel().getSelectedItem());
    }

    /**
     * Input: a student name or "All students".
     * Output: how many of their papers are pending.
     * Used by: the student list cells.
     * Example: pending("alice") -> 3
     */
    private long pending(String student) {
        return entries.stream()
                .filter(paper -> paper.status() == ReviewStatus.PENDING)
                .filter(paper -> student.equals(ALL_STUDENTS) || student.equals(paper.profileName()))
                .count();
    }

    /**
     * Records the decision for every selected paper, then shows it in the table.
     * Input: the professor's decision.
     * Output: none; written to M7e for every selected non-sent paper (off the UI thread), then shown.
     * Used by: A/D/P keys and the Approve/Drop/Back to pending buttons.
     * Example: select 2 papers, press A -> both APPROVED in scored_queue.json
     */
    private void decide(ReviewStatus status) {
        List<ScoredPaper> chosen = table.getSelectionModel().getSelectedItems().stream()
                .filter(paper -> paper.status() != ReviewStatus.SENT).toList();
        if (chosen.isEmpty()) {
            if (!table.getSelectionModel().isEmpty()) {
                summary.setText("Sent papers are a record of what each student received; they can't be changed.");
            }
            return;
        }
        int next = table.getSelectionModel().getSelectedIndex();
        session.runIo(new Task<Map<String, ScoredPaper>>() {
            /**
             * Input: none (io thread).
             * Output: the papers whose status changed, by queue key.
             * Used by: Session.runIo.
             * Example: -> {"alice\ndoi:10.1/x" = alice/X APPROVED}
             */
            @Override
            protected Map<String, ScoredPaper> call() throws Exception {
                Map<String, ScoredPaper> decided = new LinkedHashMap<>();
                for (ScoredPaper paper : chosen) {
                    if (queue.setStatus(paper, status)) {
                        decided.put(paper.queueKey(), paper.withStatus(status));
                    }
                }
                return decided;
            }

            /**
             * Input: none (UI thread).
             * Output: none; the decided papers are updated in the table and the next row is selected.
             * Used by: JavaFX, when call() returns.
             * Example: approve row 3 of 10 -> row 3 now shows the next pending paper
             */
            @Override
            protected void succeeded() {
                Map<String, ScoredPaper> decided = getValue();
                entries.replaceAll(paper -> decided.getOrDefault(paper.queueKey(), paper));
                table.getSelectionModel().clearSelection();
                applyFilter();
                if (!table.getItems().isEmpty()) {
                    table.getSelectionModel().select(Math.min(Math.max(next, 0), table.getItems().size() - 1));
                }
                table.requestFocus();
            }

            /**
             * Input: none (UI thread).
             * Output: none; the error is shown in the bar.
             * Used by: JavaFX, when call() throws.
             * Example: -> "Could not save the decision: ..."
             */
            @Override
            protected void failed() {
                error("Could not save the decision", getException());
            }
        });
    }

    /**
     * Emails the approved papers on the job thread (it shares Gmail and the queue with a pipeline run).
     * Input: none.
     * Output: none; M10 sends every approved paper as a job, then the summary shows what went.
     * Used by: the Send approved button.
     * Example: -> "Sent 4 paper(s) in 2 email(s), 3 PDF(s) attached."
     */
    private void sendApproved() {
        if (!session.unlocked()) {
            error("Not sent", new IllegalStateException("unlock the Gmail credentials first (Gmail tab)."));
            showCredentials.run();
            return;
        }
        summary.setStyle("");
        summary.setText("Sending...");
        ApprovedPaperSender sender = new ApprovedPaperSender(queue, sent, ProfileStore.atDefaultLocation(),
                new GmailSmtpSender());
        session.runJob(new Task<Report>() {
            /**
             * Input: none (job thread).
             * Output: the send's report; the credentials are cleared afterwards.
             * Used by: Session.runJob.
             * Example: -> Report(2 emails, 4 papers, 3 attachments, [], [])
             */
            @Override
            protected Report call() throws Exception {
                GmailCredentials gmail = session.gmail();
                try {
                    return sender.send(gmail, line -> Platform.runLater(() -> summary.setText(line.strip())));
                } finally {
                    gmail.clear();
                }
            }

            /**
             * Input: none (UI thread).
             * Output: none; the queue reloads and the bar shows what was sent (red if some couldn't be).
             * Used by: JavaFX, when call() returns.
             * Example: bob has no email.txt -> "Sent 2 paper(s) in 1 email(s), ...; no address for bob (1 paper(s))."
             */
            @Override
            protected void succeeded() {
                Report report = getValue();
                reload();
                List<String> problems = new ArrayList<>();
                if (!report.unaddressed().isEmpty()) {
                    problems.add("no address for " + String.join(", ", report.unaddressed()));
                }
                if (!report.failures().isEmpty()) {
                    problems.add(report.failures().size() + " email(s) failed: " + report.failures().get(0));
                }
                summary.setStyle(problems.isEmpty() ? "" : "-fx-text-fill: #b00020;");
                summary.setText("Sent " + report.papers() + " paper(s) in " + report.emails() + " email(s), "
                        + report.attachments() + " PDF(s) attached"
                        + (problems.isEmpty() ? "." : "; " + String.join("; ", problems) + "."));
            }

            /**
             * Input: none (UI thread).
             * Output: none; the queue reloads and the error is shown in the bar.
             * Used by: JavaFX, when call() throws.
             * Example: wrong App Password -> "Sending stopped (...): Gmail refused the login ..."
             */
            @Override
            protected void failed() {
                reload();
                error("Sending stopped (papers already sent are marked sent)", getException());
            }
        });
    }

    /**
     * Opens the paper's PDF if it has one, else its landing page.
     * Input: a scored paper.
     * Output: none; its PDF opens if it has one, else its landing page.
     * Used by: double-click, Enter.
     * Example: paper with pdfPath -> file:///.../matched/alice/x.pdf opens
     */
    private void open(ScoredPaper paper) {
        PaperCandidate candidate = paper.candidate();
        Optional<String> target = candidate.pdfPath().map(path -> path.toUri().toString())
                .or(() -> link(candidate));
        target.ifPresent(openDocument);
    }

    /**
     * Input: a candidate.
     * Output: its URL, else https://doi.org/DOI, else empty.
     * Used by: open, Detail.show.
     * Example: no url, doi 10.1/x -> "https://doi.org/10.1/x"
     */
    private static Optional<String> link(PaperCandidate candidate) {
        return candidate.url().or(() -> candidate.doi().map("https://doi.org/"::concat));
    }

    /**
     * Shown in the bar rather than a dialog, which a tiling window manager would tile out of sight.
     * Input: what failed and why.
     * Output: none; shown in red in the bar (not a dialog).
     * Used by: reload, decide, sendApproved.
     * Example: -> "Could not save the decision: disk full"
     */
    private void error(String what, Throwable cause) {
        summary.setStyle("-fx-text-fill: #b00020;");
        summary.setText(what + ": " + cause.getMessage());
    }

    /** The chosen paper: what it is, why M6 matched it, and the decision buttons. */
    private final class Detail extends VBox {

        private final Label title = new Label();
        private final Label authors = new Label();
        private final Label source = new Label();
        private final Hyperlink link = new Hyperlink();
        private final Label match = new Label();
        private final TextArea rationale = new TextArea();
        private final Button openPdf = new Button("Open PDF");
        private final Button approve = new Button("Approve (A)");
        private final Button drop = new Button("Drop (D)");
        private final Button reset = new Button("Back to pending (P)");
        private ScoredPaper paper;

        /**
         * Input: none.
         * Output: the detail pane: title, authors, source, link, match line, rationale and decision buttons.
         * Used by: ReviewPane's field.
         * Example: {@code new Detail()} -> shows "No paper selected"
         */
        Detail() {
            super(6);
            setPadding(new Insets(10));
            title.setWrapText(true);
            title.setStyle("-fx-font-size: 1.15em; -fx-font-weight: bold;");
            authors.setWrapText(true);
            source.setWrapText(true);
            rationale.setEditable(false);
            rationale.setWrapText(true);
            rationale.setPrefRowCount(5);
            VBox.setVgrow(rationale, Priority.ALWAYS);
            approve.setDefaultButton(false);
            approve.setStyle("-fx-base: #c8e6c9;");
            drop.setStyle("-fx-base: #ffcdd2;");
            approve.setOnAction(event -> decide(ReviewStatus.APPROVED));
            drop.setOnAction(event -> decide(ReviewStatus.DROPPED));
            reset.setOnAction(event -> decide(ReviewStatus.PENDING));
            approve.setTooltip(new Tooltip("Send to the student (applies to every selected paper)"));
            drop.setTooltip(new Tooltip("Do not send (applies to every selected paper)"));
            openPdf.setOnAction(event -> paper.candidate().pdfPath().ifPresent(path -> openDocument.accept(path.toUri().toString())));
            link.setOnAction(event -> openDocument.accept(link.getText()));
            HBox buttons = new HBox(8, approve, drop, reset, openPdf);
            getChildren().addAll(title, authors, source, link, match, new Label("Why it was matched:"), rationale, buttons);
            show(null);
        }

        /**
         * Input: the selected paper, or null.
         * Output: none; the pane shows its details and rationale, or "No paper selected" with the buttons disabled.
         * Used by: the table's selection listener, applyFilter.
         * Example: -> "Matched to alice, score 0.62, relevance 0.81, recommended, pending"
         */
        void show(ScoredPaper chosen) {
            paper = chosen;
            boolean none = chosen == null;
            approve.setDisable(none);
            drop.setDisable(none);
            reset.setDisable(none);
            if (none) {
                title.setText("No paper selected");
                authors.setText("");
                source.setText("");
                link.setText("");
                link.setVisible(false);
                match.setText("");
                rationale.setText("");
                openPdf.setDisable(true);
                return;
            }
            PaperCandidate candidate = chosen.candidate();
            title.setText(candidate.title());
            authors.setText(candidate.authors().isEmpty() ? "Authors unknown" : String.join(", ", candidate.authors()));
            List<String> where = new ArrayList<>();
            candidate.venue().ifPresent(where::add);
            candidate.year().map(String::valueOf).ifPresent(where::add);
            candidate.articleType().ifPresent(where::add);
            candidate.doi().map("DOI "::concat).ifPresent(where::add);
            source.setText(String.join("  ·  ", where));
            Optional<String> url = link(candidate);
            link.setText(url.orElse(""));
            link.setVisible(url.isPresent());
            match.setText(String.format(Locale.ROOT, "Matched to %s, score %.2f%s%s, %s", chosen.profileName(),
                    chosen.score(), chosen.relevance().map(r -> String.format(Locale.ROOT, ", relevance %.2f", r)).orElse(""),
                    chosen.recommended() ? ", recommended" : "", chosen.status() == ReviewStatus.SENT
                            && sentAt.containsKey(chosen.queueKey())
                            ? "sent " + SENT_TIME.format(sentAt.get(chosen.queueKey()))
                            : chosen.status().name().toLowerCase(Locale.ROOT)));
            rationale.setText(chosen.rationale()
                    + candidate.abstractSnippet().map(text -> "\n\nAbstract: " + text).orElse(""));
            openPdf.setDisable(candidate.pdfPath().isEmpty());
        }
    }
}
