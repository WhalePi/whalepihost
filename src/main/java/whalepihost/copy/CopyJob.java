package whalepihost.copy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import javafx.concurrent.Task;
import whalepihost.model.DataNode;
import whalepihost.model.FolderStats;

/**
 * Copies the selected items from the card into the download folder, reporting
 * byte-level progress so the bar moves smoothly on large recordings.
 *
 * <p>The value produced is the list of problems hit along the way; an empty list
 * means everything copied cleanly.
 */
public class CopyJob extends Task<List<String>> {

    /** Big enough that SD card reads stay sequential, small enough to stay responsive. */
    private static final int BUFFER_SIZE = 1 << 20;

    private final List<DataNode> items;
    private final Path destination;
    private final long totalBytes;

    private final List<String> problems = new ArrayList<>();
    private long copiedBytes;
    private long copiedFiles;
    private final long totalFiles;

    public CopyJob(List<DataNode> items, Path destination) {
        this.items = List.copyOf(items);
        this.destination = destination;

        long bytes = 0;
        long files = 0;
        for (DataNode node : items) {
            bytes += node.getTotalBytes();
            files += node.getFileCount();
        }
        this.totalBytes = bytes;
        this.totalFiles = files;
    }

    public long getTotalBytes() { return totalBytes; }

    @Override
    protected List<String> call() throws Exception {
        Files.createDirectories(destination);
        updateProgress(0, Math.max(totalBytes, 1));

        for (DataNode node : items) {
            if (isCancelled()) break;
            Path source = node.getSource();
            Path target = destination.resolve(node.getDestRelative());
            try {
                if (Files.isDirectory(source)) {
                    copyFolder(source, target);
                } else {
                    Files.createDirectories(target.getParent());
                    copyFile(source, target);
                }
            } catch (IOException e) {
                problems.add(node.getName() + ": " + describe(e));
            }
        }
        updateMessage(isCancelled() ? "Cancelled" : "Finished");
        return problems;
    }

    private void copyFolder(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (isCancelled()) return FileVisitResult.TERMINATE;
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isCancelled()) return FileVisitResult.TERMINATE;
                if (!attrs.isRegularFile() || FolderStats.isHidden(file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path dest = target.resolve(source.relativize(file).toString());
                try {
                    copyFile(file, dest);
                } catch (IOException e) {
                    problems.add(file.getFileName() + ": " + describe(e));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                problems.add(file.getFileName() + ": could not be read");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Streams one file across, updating progress as it goes rather than only on
     * completion – a single .sud file can be hundreds of megabytes.
     */
    private void copyFile(Path source, Path target) throws IOException {
        updateMessage("Copying " + source.getFileName());

        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (isCancelled()) {
                    // Leave no half-written file behind.
                    out.close();
                    Files.deleteIfExists(target);
                    return;
                }
                out.write(buffer, 0, read);
                copiedBytes += read;
                updateProgress(Math.min(copiedBytes, totalBytes), Math.max(totalBytes, 1));
            }
        }
        try {
            Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
        } catch (IOException ignored) {
            // timestamps are a nicety, not a reason to fail the copy
        }
        copiedFiles++;
    }

    public long getCopiedFiles() { return copiedFiles; }
    public long getTotalFiles() { return totalFiles; }
    public long getCopiedBytes() { return copiedBytes; }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
