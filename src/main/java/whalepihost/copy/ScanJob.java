package whalepihost.copy;

import javafx.concurrent.Task;
import whalepihost.model.CardContents;
import whalepihost.model.CardScanner;
import whalepihost.model.CardSource;
import whalepihost.model.Volume;

/**
 * Opens a card and scans it in the background, forwarding the scanner's progress
 * as task messages.
 */
public class ScanJob extends Task<CardContents> implements CardScanner.Progress {

    private final Volume volume;

    public ScanJob(Volume volume) {
        this.volume = volume;
    }

    @Override
    protected CardContents call() throws Exception {
        // The source stays open: the contents tree reads from it, and so does the
        // copy that follows. CardContents owns it from here.
        CardSource source = CardSource.open(volume);
        try {
            return CardScanner.scan(source, this);
        } catch (Exception e) {
            source.close();
            throw e;
        }
    }

    @Override
    public void message(String text) {
        updateMessage(text);
    }
}
