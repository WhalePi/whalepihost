package whalepihost.copy;

import java.io.IOException;
import java.util.List;

import javafx.concurrent.Task;
import whalepihost.model.CardFile;
import whalepihost.model.CardSource;
import whalepihost.model.DataNode;
import whalepihost.model.DeleteResult;

/**
 * Removes the chosen items from the card, reporting progress by file count.
 *
 * <p>Unlike a copy there is nothing to undo and nothing to fall back on, so the job is
 * deliberately not cancellable from the window: stopping half way through would leave
 * the user with no clear idea of what is still on the card. It still watches for
 * cancellation so that shutting the program down does not tear a delete in half.
 */
public class DeleteJob extends Task<DeleteResult> implements CardSource.DeleteProgress {

    /** How often to say something. Naming every file would flood the status bar. */
    private static final int REPORT_EVERY = 25;

    private final CardSource source;
    private final List<DataNode> items;
    private final long totalFiles;

    private long deletedFiles;

    public DeleteJob(CardSource source, List<DataNode> items) {
        this.source = source;
        this.items = List.copyOf(items);

        long files = 0;
        for (DataNode node : items) files += node.getFileCount();
        this.totalFiles = files;
    }

    public long getTotalFiles() { return totalFiles; }

    @Override
    protected DeleteResult call() {
        updateProgress(0, Math.max(totalFiles, 1));

        DeleteResult total = DeleteResult.NOTHING;
        for (DataNode node : items) {
            if (isCancelled()) break;
            CardFile file = node.getSource();
            if (file == null) continue;

            updateMessage("Deleting " + node.getName() + "…");
            try {
                total = total.plus(source.delete(file, this));
            } catch (IOException e) {
                total = total.plus(new DeleteResult(0, 0, 0,
                        List.of(node.getName() + ": " + describe(e))));
            }
        }
        updateMessage(isCancelled() ? "Stopped" : "Finished");
        return total;
    }

    @Override
    public void deleted(String path) {
        deletedFiles++;
        updateProgress(Math.min(deletedFiles, totalFiles), Math.max(totalFiles, 1));
        if (deletedFiles % REPORT_EVERY == 0) {
            updateMessage("Deleted " + deletedFiles + " of " + totalFiles + " files…");
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
