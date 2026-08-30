package whalepihost.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import whalepihost.model.CardContents;
import whalepihost.model.CardFile;
import whalepihost.model.CardSource;
import whalepihost.settings.SettingsDocument;
import whalepihost.settings.SettingsField;
import whalepihost.settings.SettingsSchema;

/**
 * The Settings tab: a form over {@code whalepidog_settings.json}.
 *
 * <p>Controls are generated from {@link SettingsSchema}, and values are read from and
 * written back to a {@link SettingsDocument}, so keys the schema does not mention
 * survive a save untouched.
 */
public class SettingsPane extends VBox {

    /** Reports what happened, so the window's status bar can say it. */
    private final Consumer<String> onSaved;
    private final Consumer<String> onError;
    /** Asks the window to show a long explanation properly. */
    private final Explainer explainer;

    private final Map<SettingsField, Control> controls = new LinkedHashMap<>();
    private final VBox groups = new VBox(14);
    private final Label banner = new Label();
    private final Label unitLabel = new Label();
    private final Button saveButton = new Button("Save to card");
    private final Button revertButton = new Button("Revert");
    private final Button whyButton = new Button("Why not?");
    private final Label spaceLabel = new Label();

    private CardContents contents;
    private SettingsDocument document;
    private CardFile settingsFile;
    private boolean loading;

    /** Shows a long message in a dialog, since a banner cannot hold one. */
    public interface Explainer {
        void explain(String header, String message);
    }

    public SettingsPane(Consumer<String> onSaved, Consumer<String> onError, Explainer explainer) {
        this.onSaved = onSaved;
        this.onError = onError;
        this.explainer = explainer;

        setSpacing(8);
        getChildren().addAll(buildHeader(), buildScroller(), buildFooter());
        VBox.setVgrow(getChildren().get(1), Priority.ALWAYS);
        setDisable(true);
    }

    // ------------------------------------------------------------------- layout

    private Node buildHeader() {
        unitLabel.getStyleClass().add("path-label");
        banner.getStyleClass().addAll("panel-hint", "hint-warning");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(banner, Priority.ALWAYS);

        whyButton.getStyleClass().add("button-quiet");
        whyButton.setVisible(false);
        whyButton.setManaged(false);
        whyButton.setOnAction(e -> {
            if (contents != null) {
                explainer.explain("This card cannot be changed",
                        contents.getSource().writeBlockedReason());
            }
        });

        HBox bannerRow = new HBox(10, banner, whyButton);
        bannerRow.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, unitLabel, bannerRow);
    }

    private Node buildScroller() {
        ScrollPane scroller = new ScrollPane(groups);
        scroller.setFitToWidth(true);
        // Without this the form asks for its full natural height and the panel
        // would try to grow past the window.
        scroller.setPrefViewportHeight(260);
        scroller.getStyleClass().add("settings-scroll");
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return scroller;
    }

    private Node buildFooter() {
        saveButton.getStyleClass().add("button-primary");
        saveButton.setGraphic(Icons.of(Icons.SAVE, 15, Palette.BACKGROUND));
        saveButton.setOnAction(e -> save());
        saveButton.setDisable(true);

        revertButton.getStyleClass().add("button-quiet");
        revertButton.setOnAction(e -> populate());
        revertButton.setDisable(true);

        spaceLabel.getStyleClass().add("path-label");

        HBox footer = new HBox(10, revertButton, spacer(), spaceLabel, saveButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    // --------------------------------------------------------------- loading

    /** Reads the settings file off a freshly scanned card and fills the form. */
    public void load(CardContents loaded) {
        this.contents = loaded;
        this.settingsFile = loaded.getSettings().getSettingsFileHandle();

        if (settingsFile == null) {
            clear();
            return;
        }
        try (InputStream in = settingsFile.open()) {
            document = SettingsDocument.parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            clear();
            onError.accept("Could not read the settings file: " + e.getMessage());
            return;
        }
        buildForm();
        populate();
        setDisable(false);
        updateWritability();
    }

    public void clear() {
        contents = null;
        document = null;
        settingsFile = null;
        controls.clear();
        groups.getChildren().clear();
        unitLabel.setText("");
        banner.setText("");
        setDisable(true);
        saveButton.setDisable(true);
        revertButton.setDisable(true);
    }

    private void updateWritability() {
        CardSource source = contents.getSource();
        boolean writable = source.canWrite();

        long capacity = settingsFile == null ? -1 : source.writeCapacity(settingsFile);
        if (writable) {
            banner.getStyleClass().remove("hint-warning");
            banner.setText(capacity < 0
                    ? "Changes are written straight to the card."
                    : "Changes are written in place. This file has " + capacity
                      + " bytes of space on the card.");
        } else {
            if (!banner.getStyleClass().contains("hint-warning")) {
                banner.getStyleClass().add("hint-warning");
            }
            banner.setText("Read-only: these settings can be viewed but not saved.");
        }
        whyButton.setVisible(!writable);
        whyButton.setManaged(!writable);

        for (Control control : controls.values()) control.node().setDisable(!writable);
        saveButton.setDisable(true);
        revertButton.setDisable(true);
    }

    // ------------------------------------------------------------------ the form

    private void buildForm() {
        controls.clear();
        groups.getChildren().clear();

        for (Map.Entry<String, List<SettingsField>> group : SettingsSchema.byGroup().entrySet()) {
            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(2);
            grid.getStyleClass().add("settings-grid");

            ColumnConstraints labels = new ColumnConstraints();
            labels.setMinWidth(180);
            labels.setPrefWidth(180);
            ColumnConstraints values = new ColumnConstraints();
            values.setHgrow(Priority.ALWAYS);
            values.setFillWidth(true);
            grid.getColumnConstraints().addAll(labels, values);

            int row = 0;
            for (SettingsField field : group.getValue()) {
                Control control = controlFor(field);
                controls.put(field, control);

                Label label = new Label(field.label());
                label.getStyleClass().add("settings-label");
                GridPane.setValignment(label, javafx.geometry.VPos.TOP);
                grid.add(label, 0, row);
                grid.add(control.node(), 1, row);

                Label help = new Label(field.help());
                help.getStyleClass().add("settings-help");
                help.setWrapText(true);
                grid.add(help, 1, row + 1);

                row += 2;
            }
            groups.getChildren().add(section(group.getKey(), grid));
        }
    }

    private Node section(String title, Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("panel-title");

        HBox head = new HBox(8, Icons.of(iconFor(title), 14, Palette.PRIMARY), heading);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("panel-header");

        VBox content = new VBox(body);
        content.getStyleClass().add("panel-body");

        VBox section = new VBox(head, content);
        section.getStyleClass().add("panel");
        return section;
    }

    private static String iconFor(String group) {
        return switch (group) {
            case SettingsSchema.UNIT -> Icons.SD_CARD;
            case SettingsSchema.RECORDING -> Icons.GRAPHIC_EQ;
            case SettingsSchema.PAMGUARD -> Icons.DESCRIPTION;
            case SettingsSchema.WATCHDOG -> Icons.REFRESH;
            default -> Icons.BLUETOOTH;
        };
    }

    private Control controlFor(SettingsField field) {
        return switch (field.type()) {
            case BOOLEAN -> {
                CheckBox box = new CheckBox();
                box.selectedProperty().addListener((o, was, is) -> markDirty());
                yield new Control(box, () -> box.isSelected(),
                        value -> box.setSelected(Boolean.parseBoolean(String.valueOf(value))));
            }
            case CHOICE -> {
                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().setAll(field.choices());
                combo.setEditable(false);
                combo.setMaxWidth(Double.MAX_VALUE);
                combo.valueProperty().addListener((o, was, is) -> markDirty());
                yield new Control(combo, combo::getValue,
                        value -> combo.setValue(String.valueOf(value)));
            }
            case INTEGER -> {
                TextField text = numericField(field);
                text.textProperty().addListener((o, was, is) -> markDirty());
                yield new Control(text, () -> {
                    try {
                        return Integer.valueOf(text.getText().trim());
                    } catch (NumberFormatException e) {
                        return text.getText().trim(); // validation reports it
                    }
                }, value -> text.setText(String.valueOf(value)));
            }
            default -> {
                TextField text = new TextField();
                text.setMaxWidth(Double.MAX_VALUE);
                if (field.maxLength() > 0) {
                    text.setTextFormatter(new javafx.scene.control.TextFormatter<>(change ->
                            change.getControlNewText().length() <= field.maxLength()
                                    ? change : null));
                }
                text.textProperty().addListener((o, was, is) -> markDirty());
                yield new Control(text, () -> text.getText().trim(),
                        value -> text.setText(String.valueOf(value)));
            }
        };
    }

    /** A text field that will not accept anything but digits. */
    private static TextField numericField(SettingsField field) {
        TextField text = new TextField();
        text.setMaxWidth(Double.MAX_VALUE);
        text.setTextFormatter(new javafx.scene.control.TextFormatter<>(change ->
                change.getControlNewText().matches("\\d{0,9}") ? change : null));
        return text;
    }

    private void populate() {
        if (document == null) return;
        loading = true;
        for (Map.Entry<SettingsField, Control> entry : controls.entrySet()) {
            SettingsField field = entry.getKey();
            Control control = entry.getValue();
            switch (field.type()) {
                case BOOLEAN -> control.set(document.getBoolean(field.key()));
                case INTEGER -> control.set(document.getInt(field.key(), field.min()));
                default -> control.set(document.get(field.key()));
            }
        }
        loading = false;

        String serial = document.get("bluetoothSettings.identification");
        unitLabel.setText("Editing " + (settingsFile == null ? "settings" : settingsFile.path())
                + (serial.isBlank() ? "" : "   •   unit " + serial));
        saveButton.setDisable(true);
        revertButton.setDisable(true);
        updateSpace();
    }

    private void markDirty() {
        if (loading || contents == null) return;
        updateSpace();
        if (!contents.getSource().canWrite()) return;
        saveButton.setDisable(false);
        revertButton.setDisable(false);
    }

    /**
     * Shows how much of the file's space the current settings need.
     *
     * <p>On an unmounted card a file cannot be given more space, so the budget is
     * real and worth watching while long paths are being typed.
     */
    private void updateSpace() {
        if (document == null || settingsFile == null || contents == null) {
            spaceLabel.setText("");
            return;
        }
        long capacity = contents.getSource().writeCapacity(settingsFile);
        if (capacity < 0) {
            spaceLabel.setText("");
            return;
        }
        SettingsDocument preview = snapshot();
        int needed = preview == null ? 0 : preview.smallestLength();

        spaceLabel.setText(needed + " of " + capacity + " bytes");
        spaceLabel.getStyleClass().removeAll("status-error", "hint-warning");
        if (needed > capacity) {
            spaceLabel.getStyleClass().add("status-error");
        } else if (needed > capacity * 0.9) {
            spaceLabel.getStyleClass().add("hint-warning");
        }
    }

    /** The document as the form currently stands, without disturbing the real one. */
    private SettingsDocument snapshot() {
        try {
            SettingsDocument copy = SettingsDocument.parse(document.toJsonText());
            for (Map.Entry<SettingsField, Control> entry : controls.entrySet()) {
                copy.set(entry.getKey().key(), entry.getValue().get());
            }
            return copy;
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------- saving

    private void save() {
        if (document == null || settingsFile == null || contents == null) return;

        for (Map.Entry<SettingsField, Control> entry : controls.entrySet()) {
            document.set(entry.getKey().key(), entry.getValue().get());
        }

        List<String> problems = document.validate();
        if (!problems.isEmpty()) {
            explainer.explain("These settings cannot be saved yet",
                    String.join("\n", problems));
            return;
        }

        CardSource source = contents.getSource();
        try {
            long capacity = source.writeCapacity(settingsFile);
            byte[] bytes = capacity < 0
                    ? document.toBytes()
                    : document.toBytes((int) settingsFile.size(), (int) capacity);
            source.write(settingsFile, bytes);

            verify();
            onSaved.accept("Settings saved to the card.");
            saveButton.setDisable(true);
            revertButton.setDisable(true);
        } catch (IOException e) {
            explainer.explain("Could not save the settings", e.getMessage());
            onError.accept("Settings were not saved.");
        }
    }

    /**
     * Reads the file back and re-parses it.
     *
     * <p>Writing to a card is the one thing here that can leave a deployment broken,
     * so the result is checked rather than assumed.
     */
    private void verify() throws IOException {
        CardFile fresh = contents.getSource().find(settingsFile.path());
        CardFile toRead = fresh == null ? settingsFile : fresh;
        try (InputStream in = toRead.open()) {
            String written = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SettingsDocument reread = SettingsDocument.parse(written);
            List<String> differences = new ArrayList<>();
            for (SettingsField field : controls.keySet()) {
                String wanted = String.valueOf(document.get(field.key()));
                String actual = String.valueOf(reread.get(field.key()));
                if (!wanted.equals(actual)) {
                    differences.add(field.label() + ": expected " + wanted + ", found " + actual);
                }
            }
            if (!differences.isEmpty()) {
                throw new IOException("The card was written but reads back differently:\n"
                        + String.join("\n", differences));
            }
            document = reread;
        }
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** A form control, with the two operations the form needs from it. */
    private record Control(Node node, java.util.function.Supplier<Object> getter,
                           Consumer<Object> setter) {
        Object get() { return getter.get(); }
        void set(Object value) { setter.accept(value); }
    }
}
