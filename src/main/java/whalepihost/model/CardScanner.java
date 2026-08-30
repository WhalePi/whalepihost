package whalepihost.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds a WhalePi deployment on an opened card and works out what is on it.
 *
 * <p>The settings file records the paths as they are on the Pi, for example
 * {@code /home/whalepi/PAMRecordings}. Reading the ext4 partition directly, those are
 * literally correct; under a mounted folder they have to be re-anchored, which
 * {@link LocalCardSource#find} handles. Either way this class asks the source for a
 * path and gets back a {@link CardFile} or nothing.
 */
public final class CardScanner {

    /** Where the settings file sits on a standard WhalePi image. */
    private static final List<String> LIKELY_SETTINGS_PATHS = List.of(
            "/home/whalepi/pamguard_pizero/" + SettingsReader.SETTINGS_FILE_NAME,
            "/home/pi/pamguard_pizero/" + SettingsReader.SETTINGS_FILE_NAME,
            "/pamguard_pizero/" + SettingsReader.SETTINGS_FILE_NAME,
            "/" + SettingsReader.SETTINGS_FILE_NAME);

    /** Folders never worth descending into when hunting for the settings file. */
    private static final List<String> SKIP_DIRS = List.of(
            "PAMRecordings", "PAMBinary", "proc", "sys", "dev", "run", "tmp",
            "usr", "lib", "lib32", "lib64", "bin", "sbin", "snap", "var", "boot",
            "System", "Windows", "Program Files", "Program Files (x86)",
            "$RECYCLE.BIN", "lost+found", "node_modules");

    private static final int MAX_SEARCH_DEPTH = 6;

    private CardScanner() { }

    /** Progress messages during a scan, so the UI can say what it is doing. */
    public interface Progress {
        void message(String text);
        default boolean isCancelled() { return false; }
    }

    /**
     * Scans an opened card.
     *
     * @throws IOException if it holds no WhalePi deployment
     */
    public static CardContents scan(CardSource source, Progress progress) throws IOException {
        note(progress, "Looking for " + SettingsReader.SETTINGS_FILE_NAME + "…");
        CardFile settingsFile = findSettingsFile(source, progress);
        if (settingsFile == null) {
            throw new IOException(noSettingsMessage(source));
        }

        note(progress, "Reading settings…");
        WhalePiSettings settings = readSettings(settingsFile);

        CardContents contents = new CardContents(source, settings);
        DataNode tree = DataNode.group("WhalePi card", DataNode.Kind.FOLDER);

        addFolderGroup(tree, source, settings.getRecordingFolder(), "Sound recordings",
                DataNode.Kind.RECORDINGS, progress);
        addSingleFile(tree, source, settings.getDatabase(), "Database", DataNode.Kind.DATABASE);
        addConfig(tree, source, settings, settingsFile);
        addBinaries(tree, source, settings, progress);

        tree.rollUpCounts();
        contents.setRoot(tree);
        return contents;
    }

    private static WhalePiSettings readSettings(CardFile file) throws IOException {
        String json;
        try (InputStream in = file.open()) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WhalePiSettings settings = SettingsReader.parse(json, file.path());
        settings.setSettingsFileHandle(file);
        return settings;
    }

    // ------------------------------------------------------------------ groups

    /** A folder of dated sub-folders: recordings, or binaries. */
    private static void addFolderGroup(DataNode tree, CardSource source, String configuredPath,
                                       String title, DataNode.Kind kind, Progress progress)
            throws IOException {
        DataNode group = DataNode.group(title, kind);
        tree.addChild(group);

        CardFile folder = configuredPath.isBlank() ? null : source.find(configuredPath);
        if (folder == null || !folder.isDirectory()) {
            group.setNote(configuredPath.isBlank() ? "not configured" : "not found on card");
            return;
        }
        note(progress, "Measuring " + folder.name() + "…");
        fillGroup(group, folder, progress);
    }

    private static void fillGroup(DataNode group, CardFile folder, Progress progress)
            throws IOException {
        List<CardFile> children;
        try {
            children = folder.list();
        } catch (IOException e) {
            group.setNote("could not be read");
            return;
        }
        List<CardFile> folders = new ArrayList<>();
        List<CardFile> files = new ArrayList<>();
        for (CardFile child : children) {
            if (child.isDirectory()) folders.add(child);
            else if (child.isRegularFile() && !child.isNoise()) files.add(child);
        }
        folders.sort(Comparator.comparing(CardFile::name));
        files.sort(Comparator.comparing(CardFile::name));

        String base = folder.name();
        for (CardFile child : folders) {
            if (cancelled(progress)) return;
            note(progress, "Measuring " + child.name() + "…");
            group.addChild(measured(child, base + "/" + child.name(), DataNode.Kind.FOLDER));
        }
        for (CardFile child : files) {
            group.addChild(measured(child, base + "/" + child.name(), DataNode.Kind.FILE));
        }
        if (group.getChildren().isEmpty()) group.setNote("folder is empty");
    }

    private static DataNode measured(CardFile file, String destination, DataNode.Kind kind) {
        DataNode node = new DataNode(file.name(), file, destination, kind, true);
        FolderStats stats = FolderStats.measure(file);
        node.setCounts(stats.fileCount(), stats.totalBytes());
        return node;
    }

    private static void addSingleFile(DataNode tree, CardSource source, String configuredPath,
                                      String title, DataNode.Kind kind) throws IOException {
        DataNode group = DataNode.group(title, kind);
        tree.addChild(group);

        CardFile file = configuredPath.isBlank() ? null : source.find(configuredPath);
        if (file == null || !file.isRegularFile()) {
            group.setNote(configuredPath.isBlank() ? "not configured" : "not found on card");
            return;
        }
        group.addChild(measured(file, file.name(), kind));
    }

    private static void addConfig(DataNode tree, CardSource source, WhalePiSettings settings,
                                  CardFile settingsFile) throws IOException {
        DataNode group = DataNode.group("PAMGuard configuration", DataNode.Kind.CONFIG);
        tree.addChild(group);

        CardFile psfx = settings.getPsfxFile().isBlank() ? null
                : source.find(settings.getPsfxFile());
        if (psfx != null && psfx.isRegularFile()) {
            group.addChild(measured(psfx, psfx.name(), DataNode.Kind.CONFIG));
        }
        // The settings file is tiny and worth keeping alongside the data.
        group.addChild(measured(settingsFile, settingsFile.name(), DataNode.Kind.CONFIG));
    }

    private static void addBinaries(DataNode tree, CardSource source, WhalePiSettings settings,
                                    Progress progress) throws IOException {
        String configured = settings.getPamBinaryFolder();
        if (!configured.isBlank()) {
            addFolderGroup(tree, source, configured, "Binary files", DataNode.Kind.BINARY, progress);
            return;
        }
        // The field is optional, so fall back to the conventional folder name.
        DataNode group = DataNode.group("Binary files", DataNode.Kind.BINARY);
        tree.addChild(group);

        CardFile folder = null;
        String recordings = settings.getRecordingFolder();
        int slash = recordings.lastIndexOf('/');
        if (slash > 0) {
            folder = source.find(recordings.substring(0, slash) + "/PAMBinary");
        }
        if (folder == null || !folder.isDirectory()) {
            group.setNote("none on this card");
            return;
        }
        note(progress, "Measuring binary files…");
        fillGroup(group, folder, progress);
    }

    // ------------------------------------------------------- finding the settings

    /**
     * Looks for {@code whalepidog_settings.json}, trying the standard locations before
     * falling back to a bounded search.
     */
    public static CardFile findSettingsFile(CardSource source, Progress progress)
            throws IOException {
        for (String path : LIKELY_SETTINGS_PATHS) {
            CardFile file = source.find(path);
            if (file != null && file.isRegularFile()) return file;
        }
        note(progress, "Searching the card…");
        return search(source.root(), 0, progress);
    }

    private static CardFile search(CardFile folder, int depth, Progress progress)
            throws IOException {
        if (depth > MAX_SEARCH_DEPTH || cancelled(progress)) return null;

        List<CardFile> children;
        try {
            children = folder.list();
        } catch (IOException e) {
            return null;
        }
        for (CardFile child : children) {
            if (child.isRegularFile()
                    && child.name().equalsIgnoreCase(SettingsReader.SETTINGS_FILE_NAME)) {
                return child;
            }
        }
        for (CardFile child : children) {
            if (!child.isDirectory() || shouldSkip(child.name())) continue;
            note(progress, "Searching " + child.name() + "…");
            CardFile found = search(child, depth + 1, progress);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean shouldSkip(String name) {
        if (name.startsWith(".")) return true;
        for (String skip : SKIP_DIRS) {
            if (skip.equalsIgnoreCase(name)) return true;
        }
        // A dated folder full of recordings is never worth searching.
        return name.length() == 8 && name.chars().allMatch(Character::isDigit);
    }

    /**
     * Explains a failed scan. Reading the Pi's little FAT boot partition is the
     * commonest mistake, because on macOS and Windows it is the only part of the card
     * that mounts — so name it rather than saying "not found".
     */
    private static String noSettingsMessage(CardSource source) throws IOException {
        if (looksLikeBootPartition(source)) {
            return "This is the WhalePi's boot partition, which holds the Raspberry Pi's "
                    + "start-up files rather than any recordings.\n\n"
                    + "The recordings, database and binary files are on the card's other "
                    + "partition. Go back to the card list and choose the WhalePi data "
                    + "partition instead — WhalePi Host can read it directly.";
        }
        return "No " + SettingsReader.SETTINGS_FILE_NAME + " found on " + source.description()
                + ".\n\nThis does not look like a WhalePi card.";
    }

    /** A Raspberry Pi boot partition always carries these two files. */
    private static boolean looksLikeBootPartition(CardSource source) throws IOException {
        CardFile root = source.root();
        return root.child("config.txt") != null && root.child("cmdline.txt") != null;
    }

    private static void note(Progress progress, String text) {
        if (progress != null) progress.message(text);
    }

    private static boolean cancelled(Progress progress) {
        return (progress != null && progress.isCancelled()) || Thread.currentThread().isInterrupted();
    }

    /** Human-readable byte count, e.g. "1.4 GB". */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.UK, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }
}
