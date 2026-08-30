package whalepihost.model;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds a WhalePi deployment on a mounted SD card (or any folder holding a copy
 * of one) and works out where its data actually lives.
 *
 * <p>The settings file records Pi paths such as {@code /home/whalepi/PAMRecordings}.
 * On a card mounted at, say, {@code /Volumes/rootfs} the same data is at
 * {@code /Volumes/rootfs/home/whalepi/PAMRecordings}, so every Pi path has to be
 * re-anchored before it can be used.
 */
public final class CardScanner {

    /** Directory names never worth descending into when hunting for the settings file. */
    private static final Set<String> SKIP_DIRS = Set.of(
            "PAMRecordings", "PAMBinary", "proc", "sys", "dev", "run", "tmp",
            "usr", "lib", "lib32", "lib64", "bin", "sbin", "snap",
            "System", "Windows", "Program Files", "Program Files (x86)",
            "$RECYCLE.BIN", ".Trashes", ".Spotlight-V100", ".fseventsd", "node_modules");

    private static final int MAX_SEARCH_DEPTH = 8;

    private CardScanner() { }

    /** Progress messages during a scan, so the UI can say what it is doing. */
    public interface Progress {
        void message(String text);
        default boolean isCancelled() { return false; }
    }

    /**
     * Scans {@code root} for a WhalePi deployment.
     *
     * @throws IOException if no settings file could be found, or it could not be read
     */
    public static CardContents scan(Path root, Progress progress) throws IOException {
        note(progress, "Looking for " + SettingsReader.SETTINGS_FILE_NAME + "…");
        Path settingsFile = findSettingsFile(root, progress);
        if (settingsFile == null) {
            throw new IOException(noSettingsMessage(root));
        }

        note(progress, "Reading settings…");
        WhalePiSettings settings = SettingsReader.read(settingsFile);

        Path piFsRoot = deducePiFilesystemRoot(root, settingsFile, settings);

        CardContents contents = new CardContents(root, settings);
        DataNode tree = DataNode.group("WhalePi card", DataNode.Kind.FOLDER);

        addRecordings(tree, root, piFsRoot, settings, progress);
        addDatabase(tree, root, piFsRoot, settings, progress);
        addConfig(tree, root, piFsRoot, settings, progress);
        addBinaries(tree, root, piFsRoot, settings, progress);

        tree.rollUpCounts();
        contents.setRoot(tree);
        return contents;
    }

    /**
     * Explains a failed scan. Reading the Pi's little FAT boot partition is the
     * overwhelmingly common mistake, because on macOS and Windows it is the only
     * part of the card that mounts – so name it rather than saying "not found".
     */
    private static String noSettingsMessage(Path root) {
        if (looksLikeBootPartition(root)) {
            return "This is the WhalePi's boot partition, which holds the Raspberry Pi's "
                    + "start-up files rather than any recordings.\n\n"
                    + "The recordings, database and binary files live on the card's other "
                    + "partition, which uses the Linux ext4 filesystem. Windows and macOS cannot "
                    + "read that on their own — install an ext4 driver, then read the card again.";
        }
        return "No " + SettingsReader.SETTINGS_FILE_NAME + " found on " + root + ".\n\n"
                + "This does not look like a WhalePi card. Choose the drive holding the card's "
                + "Linux partition and try again.";
    }

    /** A Raspberry Pi boot partition always carries these two files. */
    private static boolean looksLikeBootPartition(Path root) {
        return Files.isRegularFile(root.resolve("config.txt"))
                && Files.isRegularFile(root.resolve("cmdline.txt"));
    }

    // ------------------------------------------------------------------ groups

    private static void addRecordings(DataNode tree, Path root, Path piFsRoot,
                                      WhalePiSettings settings, Progress progress) {
        DataNode group = DataNode.group("Sound recordings", DataNode.Kind.RECORDINGS);
        tree.addChild(group);

        Path folder = resolve(root, piFsRoot, settings.getRecordingFolder(), true);
        if (folder == null) {
            group.setNote("not found on card");
            return;
        }
        note(progress, "Measuring recordings…");
        // Day folders (yyyymmdd) first, then any loose files sitting at the top level.
        List<Path> dayFolders = new ArrayList<>();
        List<Path> looseFiles = new ArrayList<>();
        try (var stream = Files.list(folder)) {
            stream.forEach(p -> {
                if (Files.isDirectory(p)) dayFolders.add(p);
                else if (!FolderStats.isHidden(p)) looseFiles.add(p);
            });
        } catch (IOException e) {
            group.setNote("could not be read");
            return;
        }
        dayFolders.sort(Comparator.comparing(p -> p.getFileName().toString()));
        looseFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        String base = folder.getFileName().toString();
        for (Path day : dayFolders) {
            if (cancelled(progress)) return;
            note(progress, "Measuring " + day.getFileName() + "…");
            DataNode node = new DataNode(day.getFileName().toString(), day,
                    base + "/" + day.getFileName(), DataNode.Kind.FOLDER, true);
            FolderStats stats = FolderStats.measure(day, null);
            node.setCounts(stats.fileCount(), stats.totalBytes());
            group.addChild(node);
        }
        for (Path file : looseFiles) {
            DataNode node = new DataNode(file.getFileName().toString(), file,
                    base + "/" + file.getFileName(), DataNode.Kind.FILE, true);
            FolderStats stats = FolderStats.measure(file, null);
            node.setCounts(stats.fileCount(), stats.totalBytes());
            group.addChild(node);
        }
        if (group.getChildren().isEmpty()) {
            group.setNote("folder is empty");
        }
    }

    private static void addDatabase(DataNode tree, Path root, Path piFsRoot,
                                    WhalePiSettings settings, Progress progress) {
        DataNode group = DataNode.group("Database", DataNode.Kind.DATABASE);
        tree.addChild(group);
        addFile(group, resolve(root, piFsRoot, settings.getDatabase(), false),
                settings.getDatabase(), DataNode.Kind.DATABASE, "");
    }

    private static void addConfig(DataNode tree, Path root, Path piFsRoot,
                                  WhalePiSettings settings, Progress progress) {
        DataNode group = DataNode.group("PAMGuard configuration", DataNode.Kind.CONFIG);
        tree.addChild(group);
        addFile(group, resolve(root, piFsRoot, settings.getPsfxFile(), false),
                settings.getPsfxFile(), DataNode.Kind.CONFIG, "");
        // The settings file itself is small and worth keeping with the data.
        Path sf = settings.getSettingsFile();
        if (sf != null && Files.isRegularFile(sf)) {
            addFile(group, sf, sf.toString(), DataNode.Kind.CONFIG, "");
        }
    }

    private static void addBinaries(DataNode tree, Path root, Path piFsRoot,
                                    WhalePiSettings settings, Progress progress) {
        DataNode group = DataNode.group("Binary files", DataNode.Kind.BINARY);
        tree.addChild(group);

        String configured = settings.getPamBinaryFolder();
        Path folder = resolve(root, piFsRoot, configured, true);
        if (folder == null && configured.isBlank()) {
            // The field is optional; fall back to the conventional folder name.
            folder = findByName(root, "PAMBinary", progress);
        }
        if (folder == null) {
            group.setNote(configured.isBlank() ? "none on this card" : "not found on card");
            return;
        }
        note(progress, "Measuring binary files…");
        String base = folder.getFileName().toString();
        List<Path> subFolders = new ArrayList<>();
        List<Path> looseFiles = new ArrayList<>();
        try (var stream = Files.list(folder)) {
            stream.forEach(p -> {
                if (Files.isDirectory(p)) subFolders.add(p);
                else if (!FolderStats.isHidden(p)) looseFiles.add(p);
            });
        } catch (IOException e) {
            group.setNote("could not be read");
            return;
        }
        subFolders.sort(Comparator.comparing(p -> p.getFileName().toString()));
        looseFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path sub : subFolders) {
            if (cancelled(progress)) return;
            DataNode node = new DataNode(sub.getFileName().toString(), sub,
                    base + "/" + sub.getFileName(), DataNode.Kind.FOLDER, true);
            FolderStats stats = FolderStats.measure(sub, null);
            node.setCounts(stats.fileCount(), stats.totalBytes());
            group.addChild(node);
        }
        for (Path file : looseFiles) {
            DataNode node = new DataNode(file.getFileName().toString(), file,
                    base + "/" + file.getFileName(), DataNode.Kind.FILE, true);
            FolderStats stats = FolderStats.measure(file, null);
            node.setCounts(stats.fileCount(), stats.totalBytes());
            group.addChild(node);
        }
        if (group.getChildren().isEmpty()) {
            group.setNote("folder is empty");
        }
    }

    private static void addFile(DataNode group, Path file, String configuredPath,
                                DataNode.Kind kind, String destFolder) {
        if (file == null || !Files.isRegularFile(file)) {
            if (group.getNote().isEmpty()) {
                group.setNote(configuredPath.isBlank() ? "not configured" : "not found on card");
            }
            return;
        }
        String name = file.getFileName().toString();
        String dest = destFolder.isBlank() ? name : destFolder + "/" + name;
        DataNode node = new DataNode(name, file, dest, kind, true);
        FolderStats stats = FolderStats.measure(file, null);
        node.setCounts(stats.fileCount(), stats.totalBytes());
        group.addChild(node);
        group.setNote("");
    }

    // ------------------------------------------------------------- path mapping

    /**
     * Works out which folder on the card corresponds to {@code /} on the Pi, by
     * lining the settings file's location up against the Pi path of the .psfx
     * file that sits beside it.
     *
     * @return the card folder standing in for the Pi filesystem root, or null if
     *         it could not be established
     */
    static Path deducePiFilesystemRoot(Path cardRoot, Path settingsFile, WhalePiSettings settings) {
        Path cardCfgDir = settingsFile.getParent();
        if (cardCfgDir == null) return null;

        // The .psfx normally lives in the same folder as the settings file, so its
        // Pi directory tells us how deep that folder sits below the Pi's root.
        for (String piPath : List.of(settings.getPsfxFile(), settings.getDatabase())) {
            if (piPath == null || piPath.isBlank()) continue;
            Path piDir = Paths.get(piPath).getParent();
            if (piDir == null) continue;

            Path candidate = stripTail(cardCfgDir, piDir);
            if (candidate != null) return candidate;
        }
        // Nothing matched – assume the selected folder is itself the Pi root.
        return cardRoot;
    }

    /**
     * If {@code cardDir} ends with the same name sequence as {@code piDir}, returns
     * {@code cardDir} with that many trailing segments removed; otherwise null.
     */
    private static Path stripTail(Path cardDir, Path piDir) {
        int piCount = piDir.getNameCount();
        int cardCount = cardDir.getNameCount();
        // Match as many trailing segments as both paths have, longest first.
        for (int len = Math.min(piCount, cardCount); len >= 1; len--) {
            boolean match = true;
            for (int i = 1; i <= len; i++) {
                String a = cardDir.getName(cardCount - i).toString();
                String b = piDir.getName(piCount - i).toString();
                if (!a.equalsIgnoreCase(b)) { match = false; break; }
            }
            if (match && len == piCount) {
                Path result = cardDir;
                for (int i = 0; i < len; i++) {
                    result = result.getParent();
                    if (result == null) return null;
                }
                return result;
            }
        }
        return null;
    }

    /**
     * Turns a Pi path from the settings file into a real path on the card.
     *
     * <p>Tries, in order: the deduced Pi root; the selected folder with the Pi path
     * appended; progressively shorter tails of the Pi path; and finally a bounded
     * search for a matching name.
     *
     * @return an existing path, or null if the item is not on this card
     */
    public static Path resolve(Path cardRoot, Path piFsRoot, String piPath, boolean wantDirectory) {
        if (piPath == null || piPath.isBlank()) return null;

        Path pi = Paths.get(piPath);
        List<Path> candidates = new ArrayList<>();

        // Already a real local path (e.g. the user picked a folder copied off the card).
        candidates.add(pi);

        Path relative = pi.isAbsolute() ? pi.getRoot().relativize(pi) : pi;
        if (piFsRoot != null) candidates.add(piFsRoot.resolve(relative.toString()));
        candidates.add(cardRoot.resolve(relative.toString()));

        // /home/whalepi/PAMRecordings -> whalepi/PAMRecordings -> PAMRecordings
        for (int i = 1; i < relative.getNameCount(); i++) {
            candidates.add(cardRoot.resolve(relative.subpath(i, relative.getNameCount()).toString()));
            if (piFsRoot != null) {
                candidates.add(piFsRoot.resolve(relative.subpath(i, relative.getNameCount()).toString()));
            }
        }

        for (Path c : candidates) {
            if (matches(c, wantDirectory)) return c;
        }
        return findByName(cardRoot, pi.getFileName().toString(), null, wantDirectory);
    }

    private static boolean matches(Path p, boolean wantDirectory) {
        try {
            return wantDirectory ? Files.isDirectory(p) : Files.isRegularFile(p);
        } catch (SecurityException e) {
            return false;
        }
    }

    private static Path findByName(Path root, String name, Progress progress) {
        return findByName(root, name, progress, true);
    }

    /** Bounded breadth-limited hunt for a file or folder called {@code name}. */
    private static Path findByName(Path root, String name, Progress progress, boolean wantDirectory) {
        if (name == null || name.isBlank()) return null;
        final Path[] found = { null };
        try {
            Files.walkFileTree(root, Set.of(), MAX_SEARCH_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (cancelled(progress)) return FileVisitResult.TERMINATE;
                    String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && shouldSkip(dirName) && !dirName.equalsIgnoreCase(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (wantDirectory && dirName.equalsIgnoreCase(name)) {
                        found[0] = dir;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!wantDirectory && file.getFileName().toString().equalsIgnoreCase(name)) {
                        found[0] = file;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // fall through – treated as "not found"
        }
        return found[0];
    }

    /**
     * Finds {@code whalepidog_settings.json} under {@code root}, preferring the
     * shallowest match so a stray copy deep in the tree cannot win.
     */
    public static Path findSettingsFile(Path root, Progress progress) {
        List<Path> matches = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), MAX_SEARCH_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (cancelled(progress)) return FileVisitResult.TERMINATE;
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && shouldSkip(name)) return FileVisitResult.SKIP_SUBTREE;
                    note(progress, "Searching " + dir.getFileName() + "…");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString()
                            .equalsIgnoreCase(SettingsReader.SETTINGS_FILE_NAME)) {
                        matches.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // partial results are fine
        }
        return matches.stream().min(Comparator.comparingInt(Path::getNameCount)).orElse(null);
    }

    private static boolean shouldSkip(String dirName) {
        if (dirName.startsWith(".")) return true;
        for (String skip : SKIP_DIRS) {
            if (skip.equalsIgnoreCase(dirName)) return true;
        }
        // Day folders full of recordings are never worth searching.
        return dirName.length() == 8 && dirName.chars().allMatch(Character::isDigit);
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
