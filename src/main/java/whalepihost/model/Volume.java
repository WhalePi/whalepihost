package whalepihost.model;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A drive the user can pick as the source card.
 *
 * <p>Two kinds appear in the list. A <em>readable</em> volume is mounted and can be
 * read straight away. An <em>unreadable</em> one is a partition the computer can see
 * but has not mounted – almost always the WhalePi's ext4 data partition on a Mac or
 * Windows machine with no ext4 driver. Listing those matters: without them a user
 * with a 1 TB card sees only its 537 MB boot partition and no hint as to why.
 */
public record Volume(Path path, String label, String detail, long totalBytes, boolean readable) {

    /** Ready to read: mounted, with a real path behind it. */
    public boolean isReadable() {
        return readable && path != null;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(label);
        if (totalBytes > 0) text.append("  (").append(CardScanner.formatBytes(totalBytes)).append(')');
        if (!readable) text.append("  —  cannot be opened");
        return text.toString();
    }

    /**
     * Every drive worth offering: the mounted volumes, plus any partition the
     * machine can see but has not mounted.
     */
    public static List<Volume> list() {
        List<Volume> volumes = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("mac")) {
            addChildren(volumes, Paths.get("/Volumes"));
            addUnmountedMacPartitions(volumes);
        } else if (os.contains("linux")) {
            String user = System.getProperty("user.name", "");
            addChildren(volumes, Paths.get("/media", user));
            addChildren(volumes, Paths.get("/media"));
            addChildren(volumes, Paths.get("/mnt"));
            addChildren(volumes, Paths.get("/run/media", user));
        } else {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) add(volumes, root.toPath());
            }
        }
        // Readable drives first, then alphabetically.
        volumes.sort(Comparator.comparing(Volume::readable).reversed()
                .thenComparing(v -> v.label().toLowerCase(Locale.ROOT)));
        return volumes;
    }

    private static void addChildren(List<Volume> out, Path parent) {
        if (!Files.isDirectory(parent)) return;
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !p.getFileName().toString().startsWith("."))
                  .forEach(p -> add(out, p));
        } catch (IOException ignored) {
            // an unreadable mount point simply doesn't get listed
        }
    }

    private static void add(List<Volume> out, Path path) {
        if (out.stream().anyMatch(v -> path.equals(v.path()))) return;
        Path fileName = path.getFileName();
        String name = fileName == null ? path.toString() : fileName.toString();
        long size = 0;
        try {
            size = Files.getFileStore(path).getTotalSpace();
        } catch (IOException ignored) {
            // size is cosmetic; carry on without it
        }
        out.add(new Volume(path, name, path.toString(), size, true));
    }

    // ------------------------------------------------- unmounted macOS partitions

    /**
     * Adds the partitions macOS can see but has not mounted.
     *
     * <p>A WhalePi card is an MS-DOS {@code bootfs} partition plus a large ext4 one.
     * macOS mounts the first and ignores the second, so the data partition has to be
     * reported here or the card looks like a 500 MB stick.
     */
    private static void addUnmountedMacPartitions(List<Volume> out) {
        String listing = run("diskutil", "list");
        if (listing.isEmpty()) return;
        String mounts = run("/sbin/mount");

        boolean externalDisk = false;
        for (String line : listing.split("\n")) {
            if (line.startsWith("/dev/disk")) {
                // Only removable media; internal containers are never the card.
                externalDisk = line.contains("(external, physical)");
                continue;
            }
            if (!externalDisk) continue;

            Partition partition = Partition.parse(line);
            // Only the Linux partition is worth reporting; an unmounted EFI or
            // recovery slice is just noise in the drive list.
            if (partition == null || !partition.isLinux()) continue;
            if (mounts.contains("/dev/" + partition.identifier() + " ")) continue;

            out.add(new Volume(null, partition.describe(), partition.advice(),
                    partition.sizeBytes(), false));
        }
    }

    /** One row of {@code diskutil list} output. */
    private record Partition(String identifier, String typeAndName, long sizeBytes) {

        /**
         * Rows look like:
         * <pre>
         *    2:                      Linux                         1.0 TB     disk10s2
         *    1:             Windows_FAT_32 bootfs                  536.9 MB   disk10s1
         * </pre>
         * The identifier is the last field and the size the two before it; everything
         * between the index and the size is the type, optionally followed by a name.
         */
        static Partition parse(String line) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5 || !parts[0].matches("\\d+:")) return null;

            String identifier = parts[parts.length - 1];
            // Whole-disk rows (identifier "disk10") are not partitions.
            if (!identifier.matches("disk\\d+s\\d+")) return null;

            long bytes = toBytes(parts[parts.length - 3], parts[parts.length - 2]);
            StringBuilder type = new StringBuilder();
            for (int i = 1; i < parts.length - 3; i++) {
                if (type.length() > 0) type.append(' ');
                type.append(parts[i]);
            }
            return new Partition(identifier, type.toString(), bytes);
        }

        boolean isLinux() {
            return typeAndName.contains("Linux");
        }

        String describe() {
            return "Linux data partition on " + identifier;
        }

        String advice() {
            return "This is where WhalePi stores its recordings, but macOS cannot read the Linux "
                    + "(ext4) filesystem it uses, so it has not been mounted. Install an ext4 "
                    + "driver such as extFS for Mac and read the card again, or copy the data off "
                    + "using a Linux computer.";
        }

        private static long toBytes(String value, String unit) {
            try {
                double number = Double.parseDouble(value);
                long factor = switch (unit.toUpperCase(Locale.ROOT)) {
                    case "KB" -> 1024L;
                    case "MB" -> 1024L * 1024;
                    case "GB" -> 1024L * 1024 * 1024;
                    case "TB" -> 1024L * 1024 * 1024 * 1024;
                    default -> 1L;
                };
                return (long) (number * factor);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** Runs a command, returning its output, or "" if anything at all goes wrong. */
    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return output;
        } catch (IOException | RuntimeException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
