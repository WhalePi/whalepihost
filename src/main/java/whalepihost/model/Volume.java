package whalepihost.model;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A drive or mount point the user can pick as the source card. */
public record Volume(Path path, String label, long totalBytes) {

    @Override
    public String toString() {
        return totalBytes > 0 ? label + "  (" + CardScanner.formatBytes(totalBytes) + ")" : label;
    }

    /**
     * Lists mounted volumes that could plausibly hold a WhalePi card: the entries
     * in {@code /Volumes} on macOS, the usual mount points on Linux, and the drive
     * roots on Windows.
     */
    public static List<Volume> list() {
        List<Volume> volumes = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            addChildren(volumes, Paths.get("/Volumes"));
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
        volumes.sort(Comparator.comparing(v -> v.label().toLowerCase()));
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
        if (out.stream().anyMatch(v -> v.path().equals(path))) return;
        Path fileName = path.getFileName();
        String name = fileName == null ? path.toString() : fileName.toString();
        long size = 0;
        try {
            FileStore store = Files.getFileStore(path);
            size = store.getTotalSpace();
        } catch (IOException ignored) {
            // size is cosmetic; carry on without it
        }
        out.add(new Volume(path, name, size));
    }
}
