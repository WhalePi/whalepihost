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

import whalepihost.ext4.DeviceRegion;
import whalepihost.ext4.Ext4FileSystem;
import whalepihost.ext4.PartitionTable;

/**
 * A card the user can choose to read.
 *
 * <p>Three kinds turn up. A <em>mounted</em> volume is read through the operating
 * system. An <em>ext4 partition</em> is one the system refused to mount because it
 * does not understand ext4 — WhalePi Host reads those itself, straight off the device.
 * An <em>unreadable</em> entry is one it can see but cannot open, which in practice
 * means the account lacks permission to read raw disks; {@link #detail()} then says
 * how to fix that.
 *
 * <p>Finding the ext4 partition is the one genuinely per-platform part of this program.
 * Linux mounts it, so there is nothing to find. macOS publishes a device node for every
 * partition and {@code diskutil} lists them. Windows publishes neither: see
 * {@link #addWindowsExt4Partitions}.
 */
public record Volume(Path path, DeviceRegion device, String label, String detail,
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
            if (os.contains("win")) addWindowsExt4Partitions(volumes);
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
        // A macOS partition device starts at the filesystem, so the whole of it is ours.
        DeviceRegion region = DeviceRegion.whole(device);

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
        if (!Ext4FileSystem.looksLikeExt4(region)) {
            return new Volume(null, null, label,
                    "Not an ext4 filesystem, so it holds no WhalePi data.", "",
                    partition.sizeBytes(), false);
        }
        return new Volume(null, region, label,
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

    // ----------------------------------------------------- Windows ext4 partitions

    /**
     * Finds the ext4 partition on Windows.
     *
     * <p>Windows hands out drive letters rather than partition devices, and it gives no
     * letter at all to a partition whose filesystem it does not recognise. A WhalePi card
     * therefore turns up as the small FAT32 {@code bootfs} drive and nothing else: there
     * is no name for the data partition to open. The way in is {@code \\.\PhysicalDriveN},
     * the whole disk, with the partition somewhere inside it — so the partition table is
     * read here to find out where, and the filesystem is then read through that region of
     * the disk.
     *
     * <p>Windows opens a raw disk for administrators alone. An ordinary account gets a
     * "needs permission" entry naming what to do, rather than an empty card list, which
     * is the same bargain {@link #probe} offers on macOS.
     */
    private static void addWindowsExt4Partitions(List<Volume> out) {
        WindowsDisks disks = WindowsDisks.query();
        for (WindowsDisk disk : disks.candidates()) {
            try {
                addWindowsDisk(out, disk, disks.partitionsOf(disk.index()), disks.answered());
            } catch (RuntimeException e) {
                // One disk that will not be read must not cost the user the whole card
                // list, which also holds the mounted drives.
            }
        }
    }

    private static void addWindowsDisk(List<Volume> out, WindowsDisk disk,
                                       List<WindowsPartition> known, boolean described) {
        Path drive = Paths.get("\\\\.\\PhysicalDrive" + disk.index());
        List<PartitionTable.Entry> partitions;
        try {
            partitions = PartitionTable.read(drive);
        } catch (IOException | RuntimeException e) {
            // Almost always "access is denied": the disk is there but closed to us.
            addPermissionHint(out, disk, known, described);
            return;
        }
        for (PartitionTable.Entry partition : partitions) {
            // The type byte is a hint, not proof, so every partition is asked directly
            // whether it begins with an ext4 superblock.
            DeviceRegion region = partition.region(drive);
            if (!Ext4FileSystem.looksLikeExt4(region)) continue;
            out.add(found(disk, partition, drive, region));
        }
    }

    private static Volume found(WindowsDisk disk, PartitionTable.Entry partition,
                                Path drive, DeviceRegion region) {
        StringBuilder detail = new StringBuilder("ext4 — read directly by WhalePi Host, no "
                + "driver needed  (").append(drive).append(", partition ")
                .append(partition.index());
        if (!partition.name().isBlank()) detail.append(" \"").append(partition.name()).append('"');
        if (!disk.model().isBlank()) detail.append(", ").append(disk.model());
        detail.append(')');

        return new Volume(null, region,
                "WhalePi data partition on disk " + disk.index()
                        + ", partition " + partition.index(),
                detail.toString(), "", partition.length(), true);
    }

    /**
     * Says that a card is probably there but cannot be opened.
     *
     * <p>{@code Win32_DiskPartition} is readable without elevation, so even an account
     * that cannot touch the disk can see that one of its partitions is of a type Windows
     * does not know — which on a WhalePi card is the ext4 one. Where that is all we can
     * establish, saying so beats saying nothing. A removable disk made entirely of
     * partitions Windows does understand is some other stick, and is left alone.
     *
     * @param described whether Windows named this disk at all. When it did not —
     *                  {@link WindowsDisks#candidates} was guessing, because the query
     *                  itself failed — there is no evidence of a card to report, and
     *                  saying "needs permission" about every disk in the machine would
     *                  be worse than silence.
     */
    private static void addPermissionHint(List<Volume> out, WindowsDisk disk,
                                          List<WindowsPartition> partitions,
                                          boolean described) {
        if (!described) return;
        WindowsPartition unrecognised = firstUnrecognised(partitions);
        if (!partitions.isEmpty() && unrecognised == null) return;

        long size = unrecognised != null && unrecognised.size() > 0
                ? unrecognised.size() : disk.sizeBytes();
        String where = disk.model().isBlank() ? "" : "  (" + disk.model() + ")";

        out.add(new Volume(null, null,
                "WhalePi data partition on disk " + disk.index(),
                "Found, but this account is not allowed to read the disk directly yet."
                        + where,
                "WhalePi Host can read this card without any driver, but Windows lets only an "
                + "administrator read a disk directly, and the WhalePi data partition can be "
                + "read no other way — Windows gives it no drive letter.\n\n"
                + "Close WhalePi Host, right-click it and choose \"Run as administrator\", "
                + "then press Refresh.\n\n"
                + "This installs no software and changes no system or security settings — it "
                + "only lets the program open the card itself.",
                size, false));
    }

    /**
     * Words in a {@code Win32_DiskPartition} type that mean Windows knows the
     * filesystem — so the partition is not the card's ext4 one. Everything else,
     * "Unknown" above all, is a candidate.
     */
    private static final List<String> KNOWN_WINDOWS_TYPES = List.of(
            "fat", "ntfs", "hpfs", "installable", "basic data", "extended",
            "system", "reserved", "logical disk manager", "hibernation");

    /** The first partition of a type Windows does not recognise, or null. */
    static WindowsPartition firstUnrecognised(List<WindowsPartition> partitions) {
        for (WindowsPartition partition : partitions) {
            String type = partition.type().toLowerCase(Locale.ROOT);
            if (KNOWN_WINDOWS_TYPES.stream().noneMatch(type::contains)) return partition;
        }
        return null;
    }

    /** One row of {@code Win32_DiskDrive}: a physical disk Windows can see. */
    record WindowsDisk(int index, String model, long sizeBytes, boolean removable) { }

    /** One row of {@code Win32_DiskPartition}, which needs no elevation to read. */
    record WindowsPartition(int disk, int index, long size, String type) { }

    /**
     * What Windows will say about its disks without being asked for elevation.
     *
     * <p>Both tables come back from one PowerShell run, because starting PowerShell
     * costs far more than either query does.
     */
    record WindowsDisks(List<WindowsDisk> disks, List<WindowsPartition> partitions,
                                boolean answered) {

        private static final String SCRIPT =
                "Get-CimInstance Win32_DiskDrive | ForEach-Object { "
                + "@('D',$_.Index,$_.InterfaceType,$_.MediaType,$_.Size,$_.Model) -join '|' }; "
                + "Get-CimInstance Win32_DiskPartition | ForEach-Object { "
                + "@('P',$_.DiskIndex,$_.Index,$_.Size,$_.Type) -join '|' }";

        static WindowsDisks query() {
            return parse(run("powershell", "-NoProfile", "-NonInteractive", "-Command", SCRIPT));
        }

        /** Reads what the script printed. Anything that is not a row is ignored. */
        static WindowsDisks parse(String output) {
            List<WindowsDisk> disks = new ArrayList<>();
            List<WindowsPartition> partitions = new ArrayList<>();
            boolean answered = false;

            for (String line : output.split("\\R")) {
                String[] parts = line.trim().split("\\|", -1);
                if (parts.length >= 6 && parts[0].equals("D")) {
                    WindowsDisk disk = parseDisk(parts);
                    if (disk != null) {
                        disks.add(disk);
                        answered = true;
                    }
                } else if (parts.length >= 5 && parts[0].equals("P")) {
                    WindowsPartition partition = parsePartition(parts);
                    if (partition != null) partitions.add(partition);
                }
            }
            return new WindowsDisks(disks, partitions, answered);
        }

        private static WindowsDisk parseDisk(String[] parts) {
            Integer index = number(parts[1]);
            if (index == null) return null;
            String media = parts[3].toLowerCase(Locale.ROOT);
            boolean removable = parts[2].equalsIgnoreCase("USB")
                    || media.contains("removable") || media.contains("external");
            return new WindowsDisk(index, parts[5].trim(), bytes(parts[4]), removable);
        }

        private static WindowsPartition parsePartition(String[] parts) {
            Integer disk = number(parts[1]);
            Integer index = number(parts[2]);
            if (disk == null || index == null) return null;
            return new WindowsPartition(disk, index, bytes(parts[3]), parts[4].trim());
        }

        private static Integer number(String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** A size as WMI reports it; 0 when it reports none, since sizes are cosmetic. */
        private static long bytes(String text) {
            try {
                return text.isBlank() ? 0 : Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * The disks worth looking inside: the removable ones.
         *
         * <p>If PowerShell answered nothing at all — it is missing, or policy blocks it —
         * there is no way to tell a card reader from the system disk, so every disk is
         * looked at. Nothing is written and nothing is listed unless a partition really
         * does begin with an ext4 superblock, so the worst that follows is a few wasted
         * reads of the front of a disk.
         */
        List<WindowsDisk> candidates() {
            if (!answered) {
                List<WindowsDisk> all = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    all.add(new WindowsDisk(index, "", 0, true));
                }
                return all;
            }
            return disks.stream().filter(WindowsDisk::removable).toList();
        }

        List<WindowsPartition> partitionsOf(int disk) {
            return partitions.stream().filter(p -> p.disk() == disk).toList();
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
