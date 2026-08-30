package whalepihost.model;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import whalepihost.ext4.Ext4FileSystem;

/**
 * A card the user can choose to read.
 *
 * <p>Three kinds turn up. A <em>mounted</em> volume is read through the operating
 * system. An <em>ext4 partition</em> is one the system refused to mount because it
 * does not understand ext4 — WhalePi Host reads those itself, straight off the device.
 * An <em>unreadable</em> entry is one it can see but cannot open, which in practice
 * means the account lacks permission to read raw disks; {@link #detail()} then says
 * how to fix that.
 */
public record Volume(Path path, Path device, String label, String detail,
                     String remedy, long totalBytes, boolean readable) {

    /** Steps the user can take to make an unreadable card readable, or "" if none. */
    public boolean hasRemedy() {
        return remedy != null && !remedy.isBlank();
    }

    /** Ready to read, one way or the other. */
    public boolean isReadable() {
        return readable && (path != null || device != null);
    }

    /** True if reading this means parsing ext4 ourselves rather than using a mount. */
    public boolean isRawExt4() {
        return device != null;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(label);
        if (totalBytes > 0) text.append("  (").append(CardScanner.formatBytes(totalBytes)).append(')');
        if (!readable) text.append("  —  needs permission");
        return text.toString();
    }

    /** Mounted volumes plus any ext4 partition the system left alone. */
    public static List<Volume> list() {
        List<Volume> volumes = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("mac")) {
            addChildren(volumes, Paths.get("/Volumes"));
            addMacExt4Partitions(volumes);
        } else if (os.contains("linux")) {
            String user = System.getProperty("user.name", "");
            addChildren(volumes, Paths.get("/media", user));
            addChildren(volumes, Paths.get("/media"));
            addChildren(volumes, Paths.get("/mnt"));
            addChildren(volumes, Paths.get("/run/media", user));
        } else {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) addMounted(volumes, root.toPath());
            }
        }
        // Cards we can actually read come first; within that, ext4 partitions lead,
        // since a WhalePi card's data always lives on one.
        volumes.sort(Comparator.comparing(Volume::readable).reversed()
                .thenComparing(Comparator.comparing(Volume::isRawExt4).reversed())
                .thenComparing(v -> v.label().toLowerCase(Locale.ROOT)));
        return volumes;
    }

    private static void addChildren(List<Volume> out, Path parent) {
        if (!Files.isDirectory(parent)) return;
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !p.getFileName().toString().startsWith("."))
                  .forEach(p -> addMounted(out, p));
        } catch (IOException ignored) {
            // an unreadable mount point simply doesn't get listed
        }
    }

    private static void addMounted(List<Volume> out, Path path) {
        if (out.stream().anyMatch(v -> path.equals(v.path()))) return;
        Path fileName = path.getFileName();
        String name = fileName == null ? path.toString() : fileName.toString();
        long size = 0;
        try {
            size = Files.getFileStore(path).getTotalSpace();
        } catch (IOException ignored) {
            // size is cosmetic; carry on without it
        }
        out.add(new Volume(path, null, name, path.toString(), "", size, true));
    }

    // ------------------------------------------------------- macOS ext4 partitions

    /**
     * Finds the ext4 partitions macOS has not mounted.
     *
     * <p>A WhalePi card is a small FAT32 {@code bootfs} partition plus a large ext4
     * one. macOS mounts the first and ignores the second, so without this the card
     * looks like a 500 MB stick with nothing on it.
     */
    private static void addMacExt4Partitions(List<Volume> out) {
        String listing = run("diskutil", "list");
        if (listing.isEmpty()) return;
        String mounts = run("/sbin/mount");

        boolean external = false;
        for (String line : listing.split("\n")) {
            if (line.startsWith("/dev/disk")) {
                external = line.contains("(external, physical)");
                continue;
            }
            if (!external) continue;

            Partition partition = Partition.parse(line);
            if (partition == null || !partition.isLinux()) continue;
            if (mounts.contains("/dev/" + partition.identifier() + " ")) continue;

            out.add(probe(partition));
        }
    }

    /**
     * Works out whether a partition can actually be opened and read.
     *
     * <p>Raw disk devices are {@code root:operator}, so an ordinary account is refused.
     * Saying so here — with the one command that fixes it — beats letting the user pick
     * the card and hit the failure afterwards.
     */
    private static Volume probe(Partition partition) {
        Path raw = Paths.get("/dev/r" + partition.identifier());
        Path block = Paths.get("/dev/" + partition.identifier());
        Path device = Files.exists(raw) ? raw : block;

        String label = "WhalePi data partition on " + partition.identifier();

        if (!canRead(device)) {
            String user = System.getProperty("user.name", "$USER");
            return new Volume(null, null, label,
                    "Found, but this account is not allowed to read raw disks yet.",
                    "WhalePi Host can read this card without any driver, but macOS only lets "
                    + "members of the \"operator\" group read a disk directly.\n\n"
                    + "Grant that once by running this in Terminal:\n\n"
                    + "    sudo dseditgroup -o edit -a " + user + " -t user operator\n\n"
                    + "Then log out, log back in, and press Refresh.\n\n"
                    + "This installs no software and makes no kernel or system changes — it only "
                    + "adds your account to a group macOS already uses for disk access.",
                    partition.sizeBytes(), false);
        }
        if (!Ext4FileSystem.looksLikeExt4(device)) {
            return new Volume(null, null, label,
                    "Not an ext4 filesystem, so it holds no WhalePi data.", "",
                    partition.sizeBytes(), false);
        }
        return new Volume(null, device, label,
                "ext4 — read directly by WhalePi Host, no driver needed  (" + device + ")", "",
                partition.sizeBytes(), true);
    }

    private static boolean canRead(Path device) {
        try (RandomAccessFile file = new RandomAccessFile(device.toFile(), "r")) {
            return file.getChannel().read(java.nio.ByteBuffer.allocate(4096), 0) > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** One partition row of {@code diskutil list} output. */
    private record Partition(String identifier, String typeAndName, long sizeBytes) {

        /**
         * Rows look like:
         * <pre>
         *    2:                      Linux                         1.0 TB     disk6s2
         * </pre>
         * The identifier is the last field and the size the two before it; whatever
         * lies between the index and the size is the type, and maybe a name.
         */
        static Partition parse(String line) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5 || !parts[0].matches("\\d+:")) return null;

            String identifier = parts[parts.length - 1];
            if (!identifier.matches("disk\\d+s\\d+")) return null; // whole-disk row

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
