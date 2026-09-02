package whalepihost.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import whalepihost.model.CardScanner;
import whalepihost.model.DataNode;

/**
 * Asks, twice, before anything is removed from a card.
 *
 * <p>The first page lists what the selection amounts to by kind — so many sound files,
 * a database, so many binary files — and lets any of them be put back. The second is
 * the last word: one button that names the number of files it is about to destroy, and
 * Cancel. Two pages rather than two dialogs, because the second is a consequence of the
 * first and should feel like it.
 *
 * <p>The configuration group starts unticked. It holds the settings file the card is
 * recognised by, and someone clearing recordings off a card to redeploy it almost never
 * means to lose that as well.
 */
public final class DeleteDialog {

    /** One row of the first page: everything selected within one card-level group. */
    private record Category(DataNode.Kind kind, List<DataNode> items, long files, long bytes) {

        /** "1,234 sound files", "1 database file". */
        private String describe() {
            return count(files) + " " + noun(kind, files);
        }
    }

    private final Stage owner;
    private final List<Category> categories;

    private final Map<Category, CheckBox> ticks = new LinkedHashMap<>();
    private final Label runningTotal = new Label();
    private final VBox chooser = new VBox(10);
    private final VBox lastWord = new VBox(10);
    private final DialogPane pane = new DialogPane();
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ButtonType confirm = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);

    private boolean onLastPage;

    private DeleteDialog(Stage owner, List<Category> categories) {
        this.owner = owner;
        this.categories = categories;
    }

    /**
     * Puts the question and returns what the user settled on.
     *
     * @param selected the copy units ticked in the contents tree
     * @return the items to delete, or empty if the user backed out
     */
    public static List<DataNode> ask(Stage owner, List<DataNode> selected,
                                     List<String> stylesheets) {
        List<Category> categories = categorise(selected);
        if (categories.isEmpty()) return List.of();
        return new DeleteDialog(owner, categories).show(stylesheets);
    }

    /** Groups the selection by the card-level group each item sits under. */
    private static List<Category> categorise(List<DataNode> selected) {
        Map<DataNode.Kind, List<DataNode>> byKind = new LinkedHashMap<>();
        for (DataNode.Kind kind : List.of(DataNode.Kind.RECORDINGS, DataNode.Kind.DATABASE,
                DataNode.Kind.BINARY, DataNode.Kind.CONFIG)) {
            byKind.put(kind, new ArrayList<>());
        }
        for (DataNode node : selected) {
            List<DataNode> bucket = byKind.get(node.group().getKind());
            // Anything outside the four known groups is not offered for deletion.
            if (bucket != null) bucket.add(node);
        }

        List<Category> categories = new ArrayList<>();
        byKind.forEach((kind, items) -> {
            if (items.isEmpty()) return;
            long files = 0;
            long bytes = 0;
            for (DataNode node : items) {
                files += node.getFileCount();
                bytes += node.getTotalBytes();
            }
            categories.add(new Category(kind, items, files, bytes));
        });
        return categories;
    }

    // -------------------------------------------------------------------- the pages

    private List<DataNode> show(List<String> stylesheets) {
        pane.getStylesheets().setAll(stylesheets);
        pane.getStyleClass().add("root");
        pane.getButtonTypes().setAll(confirm, ButtonType.CANCEL);
        pane.setContent(buildChooser());
        pane.setHeaderText("Delete from the card?");

        dialog.initOwner(owner);
        dialog.setTitle("WhalePi Host");
        dialog.setDialogPane(pane);
        dialog.setResizable(true);

        Button ok = (Button) pane.lookupButton(confirm);
        ok.getStyleClass().add("button-danger");
        // The last page renames this button to something long and specific. Left in the
        // button bar's uniform sizing it would be truncated to "Pe...", which is exactly
        // the word that has to be readable before anyone presses it.
        ButtonBar.setButtonUniformSize(ok, false);
        ok.setMinWidth(Region.USE_PREF_SIZE);
        // The first OK moves to the last word rather than closing; only the second acts.
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            if (!onLastPage) {
                event.consume();
                showLastWord(ok);
            }
        });

        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancel.setDefaultButton(true); // safest button under the Return key, at both pages

        refreshTotal(ok);
        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != confirm) return List.of();

        List<DataNode> chosen = new ArrayList<>();
        ticks.forEach((category, tick) -> {
            if (tick.isSelected()) chosen.addAll(category.items());
        });
        return chosen;
    }

    private Region buildChooser() {
        Label intro = new Label("These are the items ticked in the contents list. Untick "
                + "anything that should stay on the card.");
        intro.setWrapText(true);
        intro.setMaxWidth(430);
        intro.getStyleClass().add("panel-hint");

        for (Category category : categories) {
            CheckBox tick = new CheckBox(category.describe());
            // Losing the settings file makes the card unrecognisable, so it is never
            // swept away as a side effect of clearing recordings.
            tick.setSelected(category.kind() != DataNode.Kind.CONFIG);
            tick.selectedProperty().addListener((obs, was, is) -> refreshTotal(
                    (Button) pane.lookupButton(confirm)));
            ticks.put(category, tick);

            Label size = new Label(CardScanner.formatBytes(category.bytes()));
            size.getStyleClass().add("summary-label");

            HBox row = new HBox(10, Icons.of(iconFor(category.kind()), 14, Palette.PRIMARY),
                    tick, spacer(), size);
            row.setAlignment(Pos.CENTER_LEFT);
            chooser.getChildren().add(row);
        }

        Label caution = new Label("Deleted files are not moved to a wastebasket and cannot be "
                + "recovered. Download anything worth keeping first.");
        caution.setWrapText(true);
        caution.setMaxWidth(430);
        caution.getStyleClass().add("hint-warning");

        runningTotal.getStyleClass().add("summary-value");
        HBox totalRow = new HBox(8, dim("To delete:"), runningTotal);
        totalRow.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(14, intro, chooser, totalRow, caution);
        page.setPadding(new Insets(4, 0, 0, 0));
        return page;
    }

    /** Replaces the chooser with the last warning and renames the button to match. */
    private void showLastWord(Button ok) {
        onLastPage = true;
        long files = chosenFiles();
        long bytes = chosenBytes();

        Label heading = new Label("This cannot be undone");
        heading.getStyleClass().add("panel-title");

        Label detail = new Label("About to remove " + count(files) + " files ("
                + CardScanner.formatBytes(bytes) + ") from the card:");
        detail.setWrapText(true);
        detail.setMaxWidth(430);

        VBox list = new VBox(4);
        ticks.forEach((category, tick) -> {
            if (!tick.isSelected()) return;
            Label line = new Label("•   " + category.describe());
            line.getStyleClass().add("cell-dim");
            list.getChildren().add(line);
        });

        Label closing = new Label("There is no wastebasket on an SD card. Once this runs, the "
                + "files are gone.");
        closing.setWrapText(true);
        closing.setMaxWidth(430);
        closing.getStyleClass().add("hint-warning");

        VBox text = new VBox(10, heading, detail, list, closing);
        HBox page = new HBox(14, Icons.of(Icons.WARNING, 30, Palette.RED), text);
        page.setPadding(new Insets(4, 0, 0, 0));

        lastWord.getChildren().setAll(page);
        pane.setHeaderText("Last chance");
        pane.setContent(lastWord);
        ok.setText("Permanently delete " + count(files) + " files");
        pane.getScene().getWindow().sizeToScene();
    }

    // ---------------------------------------------------------------------- totals

    private void refreshTotal(Button ok) {
        long files = chosenFiles();
        runningTotal.setText(files == 0 ? "nothing"
                : count(files) + " files, " + CardScanner.formatBytes(chosenBytes()));
        if (ok != null) ok.setDisable(files == 0);
    }

    private long chosenFiles() {
        long files = 0;
        for (Map.Entry<Category, CheckBox> entry : ticks.entrySet()) {
            if (entry.getValue().isSelected()) files += entry.getKey().files();
        }
        return files;
    }

    private long chosenBytes() {
        long bytes = 0;
        for (Map.Entry<Category, CheckBox> entry : ticks.entrySet()) {
            if (entry.getValue().isSelected()) bytes += entry.getKey().bytes();
        }
        return bytes;
    }

    // --------------------------------------------------------------------- wording

    /** "sound files", "database file" — singular when there is only one. */
    private static String noun(DataNode.Kind kind, long files) {
        String one = switch (kind) {
            case RECORDINGS -> "sound file";
            case DATABASE -> "database file";
            case BINARY -> "binary file";
            case CONFIG -> "configuration file";
            default -> "file";
        };
        return files == 1 ? one : one + "s";
    }

    private static String iconFor(DataNode.Kind kind) {
        return switch (kind) {
            case RECORDINGS -> Icons.GRAPHIC_EQ;
            case DATABASE -> Icons.STORAGE;
            case BINARY -> Icons.HEXAGON;
            case CONFIG -> Icons.DESCRIPTION;
            default -> Icons.FILE;
        };
    }

    private static String count(long value) {
        return String.format(Locale.UK, "%,d", value);
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
}
