package whalepihost.copy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import javafx.concurrent.Task;
import whalepihost.model.CardFile;
import whalepihost.model.DataNode;

/**
 * Copies the selected items off the card into the download folder, reporting
 * byte-level progress so the bar moves smoothly through large recordings.
 *
 * <p>The value produced is the list of problems hit along the way; an empty list
 * means everything copied cleanly.
 */
public class CopyJob extends Task<List<String>> {

    /** Big enough that card reads stay sequential, small enough to stay responsive. */
    private static final int BUFFER_SIZE = 1 << 20;

    private final List<DataNode> items;
    private final Path destination;
    private final long totalBytes;
    private final long totalFiles;

    private final List<String> problems = new ArrayList<>();
    private long copiedBytes;
    private long copiedFiles;

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
    public long getTotalFiles() { return totalFiles; }
    public long getCopiedFiles() { return copiedFiles; }
    public long getCopiedBytes() { return copiedBytes; }

    @Override
    protected List<String> call() throws Exception {
        Files.createDirectories(destination);
        updateProgress(0, Math.max(totalBytes, 1));

        for (DataNode node : items) {
            if (isCancelled()) break;
            CardFile source = node.getSource();
            if (source == null) continue;

            Path target = destination.resolve(node.getDestRelative());
            try {
                copy(source, target);
            } catch (IOException e) {
                problems.add(node.getName() + ": " + describe(e));
            }
        }
        updateMessage(isCancelled() ? "Cancelled" : "Finished");
        return problems;
    }

    /** Copies a file, or a folder and everything beneath it. */
    private void copy(CardFile source, Path target) throws IOException {
        if (isCancelled()) return;

        if (source.isDirectory()) {
            Files.createDirectories(target);
            for (CardFile child : source.list()) {
                if (isCancelled()) return;
                try {
                    copy(child, target.resolve(child.name()));
                } catch (IOException e) {
                    problems.add(child.name() + ": " + describe(e));
                }
            }
        } else if (source.isRegularFile() && !source.isNoise()) {
            copyFile(source, target);
        }
    }

    /**
     * Streams one file across, updating progress as it goes rather than only on
     * completion – a single .sud file can run to hundreds of megabytes.
     */
    private void copyFile(CardFile source, Path target) throws IOException {
        updateMessage("Copying " + source.name());

        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = source.open();
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
        long modified = source.modifiedEpochSeconds();
        if (modified > 0) {
            try {
                Files.setLastModifiedTime(target, FileTime.fromMillis(modified * 1000L));
            } catch (IOException ignored) {
                // timestamps are a nicety, not a reason to fail the copy
            }
        }
        copiedFiles++;
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
