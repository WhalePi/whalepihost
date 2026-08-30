package whalepihost.ui;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import whalepihost.copy.CopyJob;
import whalepihost.copy.ScanJob;
import whalepihost.model.CardContents;
import whalepihost.model.CardScanner;
import whalepihost.model.DataNode;
import whalepihost.model.FolderStats;
import whalepihost.model.Volume;

/** The single window: pick a card, review what is on it, choose where it goes. */
public class MainView extends BorderPane {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Stage stage;

    private final ComboBox<Volume> volumeBox = new ComboBox<>();
    private final Label volumeDetail = new Label();
    private final Button fixButton = new Button("How to fix…");
    private final Button scanButton = new Button("Read card");

    private final Label serialBadge = new Label("NO CARD");
    private final Label cardSummary = new Label("No card read yet.");
    private final TreeTableView<DataNode> tree = new TreeTableView<>();
    private final TabPane contentTabs = new TabPane();
    private final Label selectionSummary = new Label();
    private final Button selectAllButton = new Button("Select all");
    private final Button selectNoneButton = new Button("Select none");

    private final TextField destField = new TextField();
    private final Label destPreview = new Label();
    private final Button downloadButton = new Button("Download");

    private final SettingsPane settingsPane = new SettingsPane(
            message -> status(message, "status-ok"),
            message -> status(message, "status-error"),
            this::showInfo);

    private final Label statusLabel = new Label("Insert a WhalePi SD card, then choose it above.");
    private final ProgressBar progressBar = new ProgressBar(0);

    private CardContents contents;
    private Task<?> runningTask;

    public MainView(Stage stage) {
        this.stage = stage;
        getStyleClass().add("root");

        setTop(buildHeader());
        setCenter(buildBody());
        setBottom(buildStatusBar());

        refreshVolumes();
        destField.setText(defaultDownloadFolder().toString());
        updateDestPreview();
        setCardLoaded(false);
    }

    // ------------------------------------------------------------------ header

    private Node buildHeader() {
        ImageView logo = new ImageView();
        Image icon = loadAppIcon();
        if (icon != null) {
            logo.setImage(icon);
            logo.setFitWidth(42);
            logo.setFitHeight(42);
            logo.setPreserveRatio(true);
            logo.setSmooth(true);
        }

        Label title = new Label("WhalePi Host");
        title.getStyleClass().add("header-title");
        Label subtitle = new Label("Download recordings and data from a WhalePi SD card");
        subtitle.getStyleClass().add("header-subtitle");

        VBox text = new VBox(1, title, subtitle);
        text.setAlignment(Pos.CENTER_LEFT);

        serialBadge.getStyleClass().addAll("serial-badge", "serial-badge-idle");

        Label serialCaption = new Label("UNIT");
        serialCaption.getStyleClass().add("header-subtitle");
        VBox serialBox = new VBox(3, serialCaption, serialBadge);
        serialBox.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(14, logo, text, spacer(), serialBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        return header;
    }

    private Image loadAppIcon() {
        var url = MainView.class.getResource("/whalepihost/whalepi_icon.png");
        return url == null ? null : new Image(url.toExternalForm(), 128, 128, true, true);
    }

    // -------------------------------------------------------------------- body

    private Node buildBody() {
        Node destination = buildDestinationPanel();
        VBox body = new VBox(14, buildSourcePanel(), buildContentsPanel(), destination);
        body.setPadding(new Insets(16));
        VBox.setVgrow(body.getChildren().get(1), Priority.ALWAYS);

        // Downloading is a Files-tab activity; while Settings is open the panel would
        // only be taking room the form needs.
        contentTabs.getSelectionModel().selectedIndexProperty().addListener((obs, was, is) -> {
            boolean filesTab = is.intValue() == 0;
            destination.setVisible(filesTab);
            destination.setManaged(filesTab);
        });
        return body;
    }

    private Node buildSourcePanel() {
        volumeBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(volumeBox, Priority.ALWAYS);
        volumeBox.setButtonCell(new VolumeCell());
        volumeBox.setCellFactory(list -> new VolumeCell());
        volumeBox.valueProperty().addListener((obs, old, value) -> showVolumeDetail(value));

        Button refresh = quietButton("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> refreshVolumes());

        scanButton.setGraphic(Icons.of(Icons.SEARCH, 15, Palette.PRIMARY));
        scanButton.setOnAction(e -> startScan());
        scanButton.setDefaultButton(true);

        // The combo is the only thing that should give when the window narrows;
        // buttons collapsing to "..." makes the toolbar unusable.
        Label cardLabel = new Label("Card:");
        for (Region fixed : List.of(cardLabel, refresh, scanButton)) {
            fixed.setMinWidth(Region.USE_PREF_SIZE);
        }
        volumeBox.setMinWidth(160);

        HBox row = new HBox(10, cardLabel, volumeBox, refresh, scanButton);
        row.setAlignment(Pos.CENTER_LEFT);

        volumeDetail.getStyleClass().add("panel-hint");
        volumeDetail.setWrapText(true);
        volumeDetail.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(volumeDetail, Priority.ALWAYS);

        fixButton.getStyleClass().add("button-quiet");
        fixButton.setVisible(false);
        fixButton.setManaged(false);
        fixButton.setOnAction(e -> showRemedy(volumeBox.getValue()));

        HBox detailRow = new HBox(10, volumeDetail, fixButton);
        detailRow.setAlignment(Pos.CENTER_LEFT);
        detailRow.setMinHeight(30);

        return panel(1, "SD card", Icons.SD_CARD, new VBox(8, row, detailRow));
    }

    /**
     * Explains the highlighted drive, and blocks Read on one the computer cannot
     * open – the WhalePi's ext4 data partition on a machine with no ext4 driver.
     */
    private void showVolumeDetail(Volume volume) {
        volumeDetail.getStyleClass().remove("hint-warning");
        boolean offerFix = volume != null && volume.hasRemedy();
        fixButton.setVisible(offerFix);
        fixButton.setManaged(offerFix);

        if (volume == null) {
            volumeDetail.setText("");
            scanButton.setDisable(true);
            return;
        }
        volumeDetail.setText(volume.detail());
        if (!volume.isReadable()) volumeDetail.getStyleClass().add("hint-warning");
        scanButton.setDisable(!volume.isReadable() || isBusy());
    }

    /**
     * Shows the steps for a card that cannot be opened yet, with the command in a
     * selectable box and a button that copies it — retyping a sudo line by eye from a
     * dialog is exactly how people end up with the wrong command.
     */
    private void showRemedy(Volume volume) {
        if (volume == null || !volume.hasRemedy()) return;

        TextArea text = new TextArea(volume.remedy());
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(11);
        text.setPrefColumnCount(60);

        Alert alert = styled(new Alert(Alert.AlertType.INFORMATION));
        alert.setTitle("WhalePi Host");
        alert.setHeaderText(volume.label());
        alert.getDialogPane().setContent(text);

        ButtonType copy = new ButtonType("Copy command");
        alert.getButtonTypes().setAll(copy, ButtonType.CLOSE);
        alert.showAndWait().filter(b -> b == copy).ifPresent(b -> copyCommand(volume.remedy()));
    }

    /** Puts the indented command line from a remedy on the clipboard. */
    private void copyCommand(String remedy) {
        String command = remedy.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("sudo "))
                .findFirst()
                .orElse(remedy);
        ClipboardContent content = new ClipboardContent();
        content.putString(command);
        Clipboard.getSystemClipboard().setContent(content);
        status("Command copied to the clipboard.", "status-ok");
    }

    /** Greys out drives that cannot be opened, so the list explains itself. */
    private static final class VolumeCell extends javafx.scene.control.ListCell<Volume> {
        @Override
        protected void updateItem(Volume volume, boolean empty) {
            super.updateItem(volume, empty);
            getStyleClass().remove("cell-unavailable");
            if (empty || volume == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(volume.toString());
            setGraphic(Icons.of(Icons.SD_CARD, 13,
                    volume.isReadable() ? Palette.PRIMARY : Palette.GREY));
            if (!volume.isReadable()) getStyleClass().add("cell-unavailable");
        }
    }

    private Node buildContentsPanel() {
        buildTreeColumns();
        tree.setShowRoot(false);
        tree.setPlaceholder(new Label("Read a card to see what is on it."));
        tree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tree.setPrefHeight(340);
        VBox.setVgrow(tree, Priority.ALWAYS);

        cardSummary.getStyleClass().add("path-label");
        cardSummary.setMaxWidth(Double.MAX_VALUE);

        selectAllButton.getStyleClass().add("button-quiet");
        selectAllButton.setOnAction(e -> setAllSelected(true));
        selectNoneButton.getStyleClass().add("button-quiet");
        selectNoneButton.setOnAction(e -> setAllSelected(false));

        selectionSummary.getStyleClass().add("summary-value");

        HBox footer = new HBox(8, selectAllButton, selectNoneButton, spacer(),
                dim("Selected:"), selectionSummary);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox files = new VBox(10, cardSummary, tree, footer);
        VBox.setVgrow(files, Priority.ALWAYS);

        Tab filesTab = new Tab("FILES", files);
        filesTab.setClosable(false);
        filesTab.setGraphic(Icons.of(Icons.GRAPHIC_EQ, 13, Palette.PRIMARY));

        Tab settingsTab = new Tab("SETTINGS", settingsPane);
        settingsTab.setClosable(false);
        settingsTab.setGraphic(Icons.of(Icons.TUNE, 13, Palette.PRIMARY));

        contentTabs.getTabs().setAll(filesTab, settingsTab);
        contentTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(contentTabs, Priority.ALWAYS);

        return panel(2, "Contents", Icons.GRAPHIC_EQ, contentTabs);
    }

    private void buildTreeColumns() {
        TreeTableColumn<DataNode, DataNode> itemCol = new TreeTableColumn<>("ITEM");
        itemCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().getValue()));
        itemCol.setCellFactory(c -> new ItemCell());
        itemCol.setPrefWidth(380);
        itemCol.setSortable(false);

        TreeTableColumn<DataNode, String> typeCol = new TreeTableColumn<>("TYPE");
        typeCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(describe(p.getValue().getValue())));
        typeCol.setPrefWidth(150);
        typeCol.setSortable(false);
        typeCol.setCellFactory(c -> styledCell("cell-dim"));

        TreeTableColumn<DataNode, String> filesCol = new TreeTableColumn<>("FILES");
        filesCol.setCellValueFactory(p -> {
            DataNode node = p.getValue().getValue();
            return new ReadOnlyObjectWrapper<>(node.getFileCount() == 0 && !node.getNote().isEmpty()
                    ? "–" : String.valueOf(node.getFileCount()));
        });
        filesCol.setPrefWidth(90);
        filesCol.setSortable(false);
        filesCol.setCellFactory(c -> styledCell("cell-number"));

        TreeTableColumn<DataNode, String> sizeCol = new TreeTableColumn<>("SIZE");
        sizeCol.setCellValueFactory(p -> {
            DataNode node = p.getValue().getValue();
            return new ReadOnlyObjectWrapper<>(node.getFileCount() == 0 && !node.getNote().isEmpty()
                    ? "–" : CardScanner.formatBytes(node.getTotalBytes()));
        });
        sizeCol.setPrefWidth(110);
        sizeCol.setSortable(false);
        sizeCol.setCellFactory(c -> styledCell("cell-number"));

        tree.getColumns().setAll(List.of(itemCol, typeCol, filesCol, sizeCol));
    }

    private Node buildDestinationPanel() {
        destField.setPromptText("Folder to download into");
        HBox.setHgrow(destField, Priority.ALWAYS);
        destField.textProperty().addListener((obs, old, value) -> updateDestPreview());

        Button browse = quietButton("Browse…", Icons.FOLDER_OPEN);
        browse.setOnAction(e -> chooseDestinationFolder());

        downloadButton.getStyleClass().add("button-primary");
        downloadButton.setGraphic(Icons.of(Icons.DOWNLOAD, 16, Palette.BACKGROUND));
        downloadButton.setOnAction(e -> {
            if (runningTask instanceof CopyJob job && job.isRunning()) {
                job.cancel();
            } else {
                startDownload();
            }
        });

        destPreview.getStyleClass().add("path-label");

        HBox row = new HBox(10, new Label("Folder:"), destField, browse);
        row.setAlignment(Pos.CENTER_LEFT);

        HBox actions = new HBox(12, destPreview, spacer(), downloadButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        return panel(3, "Download to", Icons.DOWNLOAD, new VBox(10, row, actions));
    }

    private Node buildStatusBar() {
        statusLabel.getStyleClass().add("status-text");
        progressBar.setPrefWidth(240);
        progressBar.setVisible(false);
        HBox bar = new HBox(14, statusLabel, spacer(), progressBar);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ----------------------------------------------------------------- actions

    private void refreshVolumes() {
        Volume previous = volumeBox.getValue();
        List<Volume> volumes = Volume.list();
        volumeBox.getItems().setAll(volumes);
        volumeBox.setDisable(volumes.isEmpty());

        if (volumes.isEmpty()) {
            volumeBox.setPromptText("No drives found – insert a card and press Refresh");
            showVolumeDetail(null);
            status("No drives found. Insert the WhalePi SD card, then press Refresh.",
                    "status-error");
            return;
        }
        // Keep the user's choice across a refresh where it still exists, otherwise
        // land on the first drive that can actually be read.
        Volume selected = volumes.contains(previous) ? previous
                : volumes.stream().filter(Volume::isReadable).findFirst().orElse(volumes.get(0));
        volumeBox.setValue(selected);
        showVolumeDetail(selected);
    }

    private void chooseDestinationFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select where to download the data");
        Path current = destinationParent();
        if (current != null && Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) destField.setText(chosen.getAbsolutePath());
    }

    private void startScan() {
        Volume volume = volumeBox.getValue();
        if (volume == null) {
            status("Choose a card to read first.", "status-error");
            return;
        }
        if (!volume.isReadable()) {
            showError("This card cannot be opened", volume.detail());
            return;
        }
        closeCard();

        setBusy(true, "Reading card…");
        setCardLoaded(false);

        ScanJob task = new ScanJob(volume);
        task.messageProperty().addListener((obs, old, message) -> status(message, "status-busy"));
        task.setOnSucceeded(e -> {
            setBusy(false, null);
            showContents(task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable error = task.getException();
            status("Could not read the card.", "status-error");
            showError("Could not read the card",
                    error == null ? "Unknown error." : error.getMessage());
        });
        runTask(task);
    }

    /** Releases the card when the window closes. */
    public void dispose() {
        if (runningTask != null) runningTask.cancel();
        closeCard();
    }

    /**
     * Releases the previously read card. A raw ext4 source holds the device open, so
     * it has to be closed before another card is read or the window shuts.
     */
    private void closeCard() {
        if (contents == null) return;
        try {
            contents.getSource().close();
        } catch (IOException ignored) {
            // nothing useful to do about a card we are finished with
        }
        contents = null;
    }

    private void showContents(CardContents loaded) {
        this.contents = loaded;

        serialBadge.setText(loaded.getSettings().getDisplaySerial());
        serialBadge.getStyleClass().remove("serial-badge-idle");

        TreeItem<DataNode> root = buildTreeItem(loaded.getRoot(), true);
        tree.setRoot(root);
        tree.scrollTo(0); // a freshly read card should show its first group, not wherever
                          // the previous card happened to be scrolled to
        attachSelectionListeners(loaded.getRoot());

        FolderStats totals = new FolderStats(loaded.getRoot().getFileCount(),
                loaded.getRoot().getTotalBytes());
        String summary = "On card: " + totals.fileCount() + " files, "
                + CardScanner.formatBytes(totals.totalBytes())
                + "   •   settings read from " + loaded.getSettings().getSettingsPath();
        cardSummary.setText(summary);
        cardSummary.setTooltip(new Tooltip(summary));

        setCardLoaded(true);
        updateSelectionSummary();
        updateDestPreview();
        status("Card " + loaded.getSettings().getDisplaySerial() + " read. "
                + "Choose what to download.", "status-ok");

        settingsPane.load(loaded);

        String warning = loaded.getSource().warning();
        if (!warning.isEmpty()) {
            showWarning("Card was not shut down cleanly", warning);
        }
    }

    /**
     * How many children a group may have and still open by default. Kept small so
     * that all four groups stay visible when the card is first read – a user who
     * cannot see the Binary files row will not know there are binaries to fetch.
     */
    private static final int AUTO_EXPAND_LIMIT = 3;

    private TreeItem<DataNode> buildTreeItem(DataNode node, boolean expanded) {
        TreeItem<DataNode> item = new TreeItem<>(node);
        item.setExpanded(expanded);
        for (DataNode child : node.getChildren()) {
            // Short groups open so their contents are visible at a glance; a long
            // deployment's day folders stay collapsed so all four groups still fit.
            item.getChildren().add(buildTreeItem(child,
                    !child.getChildren().isEmpty()
                            && child.getChildren().size() <= AUTO_EXPAND_LIMIT));
        }
        return item;
    }

    private void attachSelectionListeners(DataNode node) {
        node.selectedProperty().addListener((obs, old, value) -> updateSelectionSummary());
        for (DataNode child : node.getChildren()) attachSelectionListeners(child);
    }

    private void setAllSelected(boolean selected) {
        if (contents == null) return;
        contents.getRoot().setSelectedByUser(selected);
        tree.refresh();
        updateSelectionSummary();
    }

    private void updateSelectionSummary() {
        if (contents == null) {
            setSelectionText("nothing", false);
            downloadButton.setDisable(true);
            return;
        }
        FolderStats totals = contents.selectedTotals();
        setSelectionText(totals.fileCount() + " files, "
                + CardScanner.formatBytes(totals.totalBytes()), totals.fileCount() > 0);
        downloadButton.setDisable(totals.fileCount() == 0 || isBusy());
    }

    /** Green once there is something to download, dim while there is not. */
    private void setSelectionText(String text, boolean anything) {
        selectionSummary.setText(text);
        selectionSummary.getStyleClass().setAll("label",
                anything ? "summary-value" : "summary-label");
    }

    private void startDownload() {
        if (contents == null) return;
        List<DataNode> items = contents.selectedItems();
        if (items.isEmpty()) {
            status("Nothing is selected to download.", "status-error");
            return;
        }
        Path target = destinationFolder();
        if (target == null) {
            status("Choose a folder to download into.", "status-error");
            return;
        }
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            status("Download folder does not exist: " + parent, "status-error");
            return;
        }

        CopyJob job = new CopyJob(items, target);
        long free = usableSpace(parent);
        if (free > 0 && job.getTotalBytes() > free) {
            showError("Not enough space",
                    "This download needs " + CardScanner.formatBytes(job.getTotalBytes())
                    + " but only " + CardScanner.formatBytes(free) + " is free on the destination drive.");
            return;
        }

        setBusy(true, "Downloading…");
        downloadButton.setDisable(false);
        downloadButton.setText("Cancel");
        downloadButton.getStyleClass().add("button-danger");
        downloadButton.setGraphic(Icons.of(Icons.STOP, 15, Palette.RED));

        progressBar.progressProperty().bind(job.progressProperty());
        job.messageProperty().addListener((obs, old, message) -> status(message, "status-busy"));

        job.setOnSucceeded(e -> {
            finishDownload();
            List<String> problems = job.getValue();
            if (job.isCancelled()) {
                status("Download cancelled.", "status-error");
            } else if (problems.isEmpty()) {
                status("Downloaded " + job.getCopiedFiles() + " files ("
                        + CardScanner.formatBytes(job.getCopiedBytes()) + ").", "status-ok");
                showFinished(target, job);
            } else {
                status("Finished with " + problems.size() + " problem(s).", "status-error");
                showProblems(target, problems);
            }
        });
        job.setOnCancelled(e -> {
            finishDownload();
            status("Download cancelled.", "status-error");
        });
        job.setOnFailed(e -> {
            finishDownload();
            Throwable error = job.getException();
            status("Download failed.", "status-error");
            showError("Download failed", error == null ? "Unknown error." : error.getMessage());
        });
        runTask(job);
    }

    private void finishDownload() {
        progressBar.progressProperty().unbind();
        progressBar.setProgress(0);
        setBusy(false, null);
        downloadButton.setText("Download");
        downloadButton.getStyleClass().remove("button-danger");
        downloadButton.setGraphic(Icons.of(Icons.DOWNLOAD, 16, Palette.BACKGROUND));
        updateSelectionSummary();
    }

    // ------------------------------------------------------------------ dialogs

    private void showFinished(Path folder, CopyJob job) {
        Alert alert = styled(new Alert(Alert.AlertType.INFORMATION));
        alert.setTitle("Download complete");
        alert.setHeaderText("Downloaded " + job.getCopiedFiles() + " files ("
                + CardScanner.formatBytes(job.getCopiedBytes()) + ")");
        alert.setContentText(folder.toString());

        ButtonType open = new ButtonType("Open folder");
        alert.getButtonTypes().setAll(open, ButtonType.CLOSE);
        alert.showAndWait().filter(b -> b == open).ifPresent(b -> openInFileManager(folder));
    }

    private void showProblems(Path folder, List<String> problems) {
        Alert alert = styled(new Alert(Alert.AlertType.WARNING));
        alert.setTitle("Download finished with problems");
        alert.setHeaderText(problems.size() + " item(s) could not be copied");
        TextArea area = new TextArea(String.join("\n", problems));
        area.setEditable(false);
        area.setPrefRowCount(Math.min(12, problems.size() + 1));
        alert.getDialogPane().setExpandableContent(area);
        alert.getDialogPane().setExpanded(true);
        alert.setContentText("Everything else was saved to:\n" + folder);
        alert.showAndWait();
    }

    /** Shows a long message with room to read it, for the Settings tab. */
    private void showInfo(String header, String message) {
        TextArea area = new TextArea(message);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(Math.min(14, (int) message.lines().count() + 3));
        area.setPrefColumnCount(58);

        Alert alert = styled(new Alert(Alert.AlertType.INFORMATION));
        alert.setTitle("WhalePi Host");
        alert.setHeaderText(header);
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    private void showWarning(String header, String message) {
        Alert alert = styled(new Alert(Alert.AlertType.WARNING));
        alert.setTitle("WhalePi Host");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String header, String message) {
        Alert alert = styled(new Alert(Alert.AlertType.ERROR));
        alert.setTitle("WhalePi Host");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private Alert styled(Alert alert) {
        alert.initOwner(stage);
        var pane = alert.getDialogPane();
        pane.getStylesheets().setAll(getScene() == null
                ? List.of() : getScene().getStylesheets());
        pane.getStyleClass().add("root");
        return alert;
    }

    private void openInFileManager(Path folder) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(folder.toFile());
            }
        } catch (IOException | UnsupportedOperationException e) {
            status("Saved to " + folder, "status-ok");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void runTask(Task<?> task) {
        runningTask = task;
        Thread thread = new Thread(task, "whalepi-host-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean isBusy() {
        return runningTask != null && runningTask.isRunning();
    }

    private void setBusy(boolean busy, String message) {
        Volume current = volumeBox.getValue();
        scanButton.setDisable(busy || current == null || !current.isReadable());
        volumeBox.setDisable(busy || volumeBox.getItems().isEmpty());
        destField.setDisable(busy);
        selectAllButton.setDisable(busy || contents == null);
        selectNoneButton.setDisable(busy || contents == null);
        tree.setDisable(busy);
        settingsPane.setDisable(busy || contents == null);
        downloadButton.setDisable(busy);
        progressBar.setVisible(busy);
        if (busy) {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            if (message != null) status(message, "status-busy");
        } else {
            updateSelectionSummary();
        }
    }

    private void setCardLoaded(boolean loaded) {
        selectAllButton.setDisable(!loaded);
        selectNoneButton.setDisable(!loaded);
        downloadButton.setDisable(!loaded);
        if (!loaded) {
            tree.setRoot(null);
            settingsPane.clear();
            closeCard();
            setSelectionText("nothing", false);
            serialBadge.setText("NO CARD");
            if (!serialBadge.getStyleClass().contains("serial-badge-idle")) {
                serialBadge.getStyleClass().add("serial-badge-idle");
            }
            cardSummary.setText("No card read yet.");
        }
    }

    private void status(String text, String styleClass) {
        Runnable update = () -> {
            statusLabel.setText(text == null ? "" : text);
            statusLabel.getStyleClass().removeAll("status-ok", "status-error", "status-busy");
            if (styleClass != null) statusLabel.getStyleClass().add(styleClass);
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    private Path destinationParent() {
        String text = destField.getText();
        if (text == null || text.isBlank()) return null;
        try {
            return Paths.get(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The timestamped folder this download will create. */
    private Path destinationFolder() {
        Path parent = destinationParent();
        if (parent == null || contents == null) return null;
        return parent.resolve(contents.suggestedFolderName(LocalDateTime.now().format(STAMP)));
    }

    private void updateDestPreview() {
        Path parent = destinationParent();
        if (parent == null) {
            destPreview.setText("");
            return;
        }
        String serial = contents == null ? "<unit>" : contents.getSettings().getDisplaySerial();
        destPreview.setText("Creates: " + parent.getFileName() + File.separator
                + "WhalePi_" + serial + "_<date>_<time>");
    }

    private static long usableSpace(Path folder) {
        try {
            return Files.getFileStore(folder).getUsableSpace();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Path defaultDownloadFolder() {
        Path home = Paths.get(System.getProperty("user.home", "."));
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : home;
    }

    private static String describe(DataNode node) {
        if (!node.getNote().isEmpty()) return node.getNote();
        return switch (node.getKind()) {
            case RECORDINGS -> "sound files";
            case DATABASE -> "SQLite database";
            case CONFIG -> "configuration";
            case BINARY -> "PAMGuard binaries";
            case FOLDER -> "folder";
            case FILE -> "file";
        };
    }

    private Node panel(int step, String title, String iconPath, Node body) {
        Label number = new Label(String.valueOf(step));
        number.getStyleClass().add("step-number");

        Label label = new Label(title);
        label.getStyleClass().add("panel-title");

        HBox head = new HBox(10, number, Icons.of(iconPath, 16, Palette.PRIMARY), label);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("panel-header");

        VBox content = new VBox(body);
        content.getStyleClass().add("panel-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox panel = new VBox(head, content);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private Button quietButton(String text, String iconPath) {
        Button button = new Button(text);
        button.getStyleClass().add("button-quiet");
        button.setGraphic(Icons.of(iconPath, 13, Palette.TEXT_DIM));
        return button;
    }

    private static Label dim(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("summary-label");
        return label;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static <S> TreeTableCell<S, String> styledCell(String styleClass) {
        TreeTableCell<S, String> cell = new TreeTableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        };
        cell.getStyleClass().add(styleClass);
        return cell;
    }

    /** Item column: a tick box, an icon matching the data type, and the name. */
    private final class ItemCell extends TreeTableCell<DataNode, DataNode> {

        private final CheckBox check = new CheckBox();
        private final Label name = new Label();
        private final HBox box = new HBox(8, check, name);

        private DataNode bound;

        private ItemCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            check.setAllowIndeterminate(true);
            check.setOnAction(e -> {
                if (bound != null) {
                    // A click always means "all on" or "all off", never indeterminate.
                    boolean value = check.isIndeterminate() || check.isSelected();
                    check.setIndeterminate(false);
                    check.setSelected(value);
                    bound.setSelectedByUser(value);
                    getTreeTableView().refresh();
                    updateSelectionSummary();
                }
            });
        }

        @Override
        protected void updateItem(DataNode node, boolean empty) {
            super.updateItem(node, empty);
            bound = empty ? null : node;
            if (empty || node == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            check.setSelected(node.isSelected());
            check.setIndeterminate(node.indeterminateProperty().get());
            check.setDisable(node.getSource() == null && node.getChildren().isEmpty());

            name.setText(node.getName());
            name.getStyleClass().setAll("label",
                    node.getParent() != null && node.getParent().getParent() == null
                            ? "cell-group" : "cell-dim");

            box.getChildren().setAll(check, iconFor(node), name);
            if (!node.getNote().isEmpty()) {
                Label note = new Label("(" + node.getNote() + ")");
                note.getStyleClass().add("cell-note");
                box.getChildren().add(note);
            }
            setGraphic(box);
            setTooltip(node.getSource() == null ? null : tooltip(node.getSource().toString()));
        }

        private Node iconFor(DataNode node) {
            String path = switch (node.getKind()) {
                case RECORDINGS -> Icons.GRAPHIC_EQ;
                case DATABASE -> Icons.STORAGE;
                case CONFIG -> Icons.DESCRIPTION;
                case BINARY -> Icons.HEXAGON;
                case FOLDER -> Icons.FOLDER;
                case FILE -> Icons.FILE;
            };
            boolean missing = !node.getNote().isEmpty();
            return Icons.of(path, 14, missing ? Palette.GREY : Palette.PRIMARY);
        }

        private Tooltip tooltip(String text) {
            Tooltip tip = new Tooltip(text);
            tip.setShowDelay(Duration.millis(400));
            return tip;
        }
    }
}
