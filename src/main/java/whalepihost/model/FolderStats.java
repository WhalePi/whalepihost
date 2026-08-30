package whalepihost.model;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/** Recursive file count and byte total for a file or folder. */
public record FolderStats(long fileCount, long totalBytes) {

    public static final FolderStats EMPTY = new FolderStats(0, 0);

    /**
     * Walks {@code path}, ignoring anything unreadable rather than failing the scan.
     *
     * @param progress called with each file as it is counted; may be null
     */
    public static FolderStats measure(Path path, Consumer<Path> progress) {
        if (path == null || !Files.exists(path)) return EMPTY;

        if (Files.isRegularFile(path)) {
            long size = 0;
            try {
                size = Files.size(path);
            } catch (IOException ignored) {
                // unreadable file – count it, but with unknown size
            }
            if (progress != null) progress.accept(path);
            return new FolderStats(1, size);
        }

        Counter counter = new Counter();
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                    if (attrs.isRegularFile() && !isHidden(file)) {
                        counter.files++;
                        counter.bytes += attrs.size();
                        if (progress != null) progress.accept(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // partial totals are still worth showing
        }
        return new FolderStats(counter.files, counter.bytes);
    }

    /** Skips the junk macOS and Windows leave behind on removable media. */
    public static boolean isHidden(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("._") || name.equals(".DS_Store") || name.equals("Thumbs.db");
    }

    public FolderStats plus(FolderStats other) {
        return new FolderStats(fileCount + other.fileCount, totalBytes + other.totalBytes);
    }

    private static final class Counter {
        long files;
        long bytes;
    }
}
