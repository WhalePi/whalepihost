package whalepihost.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A card the operating system has already mounted, read through the normal filesystem. */
public class LocalCardSource implements CardSource {

    private final Path root;

    public LocalCardSource(Path root) {
        this.root = root;
    }

    @Override
    public String description() {
        return root.toString();
    }

    @Override
    public CardFile root() {
        return new LocalCardFile(root);
    }

    /**
     * Resolves a Pi path onto the mounted folder.
     *
     * <p>The path recorded in the settings file is absolute on the Pi
     * ({@code /home/whalepi/PAMRecordings}); under a mount point the same data sits at
     * {@code <mount>/home/whalepi/PAMRecordings}, so both forms are tried, along with
     * progressively shorter tails for cards that have been partly copied about.
     */
    @Override
    public CardFile find(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return null;

        Path pi = Path.of(absolutePath);
        List<Path> candidates = new ArrayList<>();
        candidates.add(pi);

        Path relative = pi.isAbsolute() && pi.getRoot() != null ? pi.getRoot().relativize(pi) : pi;
        candidates.add(root.resolve(relative.toString()));
        for (int i = 1; i < relative.getNameCount(); i++) {
            candidates.add(root.resolve(relative.subpath(i, relative.getNameCount()).toString()));
        }
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return new LocalCardFile(candidate);
        }
        return null;
    }

    @Override
    public void close() {
        // nothing to release: the OS owns the mount
    }

    /** A file or folder under the mount point. */
    private static final class LocalCardFile implements CardFile {

        private final Path path;

        private LocalCardFile(Path path) {
            this.path = path;
        }

        @Override
        public String name() {
            Path fileName = path.getFileName();
            return fileName == null ? path.toString() : fileName.toString();
        }

        @Override
        public String path() {
            return path.toString();
        }

        @Override
        public boolean isDirectory() {
            return Files.isDirectory(path);
        }

        @Override
        public boolean isRegularFile() {
            return Files.isRegularFile(path);
        }

        @Override
        public long size() {
            try {
                return Files.isRegularFile(path) ? Files.size(path) : 0;
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public long modifiedEpochSeconds() {
            try {
                return Files.getLastModifiedTime(path).toInstant().getEpochSecond();
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public List<CardFile> list() throws IOException {
            if (!Files.isDirectory(path)) return List.of();
            List<CardFile> children = new ArrayList<>();
            try (var stream = Files.list(path)) {
                stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                      .forEach(p -> children.add(new LocalCardFile(p)));
            }
            return children;
        }

        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public CardFile child(String name) {
            Path child = path.resolve(name);
            return Files.exists(child) ? new LocalCardFile(child) : null;
        }
    }
}
