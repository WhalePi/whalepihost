package whalepihost.copy;

import java.nio.file.Path;

import javafx.concurrent.Task;
import whalepihost.model.CardContents;
import whalepihost.model.CardScanner;

/** Background scan of a card, forwarding the scanner's progress as task messages. */
public class ScanJob extends Task<CardContents> implements CardScanner.Progress {

    private final Path root;

    public ScanJob(Path root) {
        this.root = root;
    }

    @Override
    protected CardContents call() throws Exception {
        return CardScanner.scan(root, this);
    }

    @Override
    public void message(String text) {
        updateMessage(text);
    }
}
