package whalepihost.model;

import java.io.IOException;

/** Recursive file count and byte total for a file or folder on a card. */
public record FolderStats(long fileCount, long totalBytes) {

    public static final FolderStats EMPTY = new FolderStats(0, 0);

    /** Anything unreadable is skipped rather than failing the whole measurement. */
    public static FolderStats measure(CardFile file) {
        if (file == null) return EMPTY;
        if (file.isRegularFile()) {
            return file.isNoise() ? EMPTY : new FolderStats(1, file.size());
        }
        if (!file.isDirectory()) return EMPTY;

        long files = 0;
        long bytes = 0;
        try {
            for (CardFile child : file.list()) {
                if (Thread.currentThread().isInterrupted()) break;
                FolderStats stats = measure(child);
                files += stats.fileCount();
                bytes += stats.totalBytes();
            }
        } catch (IOException ignored) {
            // partial totals are still worth showing
        }
        return new FolderStats(files, bytes);
    }

    public FolderStats plus(FolderStats other) {
        return new FolderStats(fileCount + other.fileCount, totalBytes + other.totalBytes);
    }
}
