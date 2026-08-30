package whalepihost.model;

import java.util.ArrayList;
import java.util.List;

/** Everything a scan found on one card: its settings and the selectable data tree. */
public class CardContents {

    private final CardSource source;
    private final WhalePiSettings settings;
    private DataNode root;

    public CardContents(CardSource source, WhalePiSettings settings) {
        this.source = source;
        this.settings = settings;
    }

    public CardSource getSource() { return source; }
    public WhalePiSettings getSettings() { return settings; }

    public DataNode getRoot() { return root; }
    public void setRoot(DataNode root) { this.root = root; }

    /** The copy units the user currently has ticked. */
    public List<DataNode> selectedItems() {
        List<DataNode> out = new ArrayList<>();
        if (root != null) root.collectSelected(out);
        return out;
    }

    public FolderStats selectedTotals() {
        FolderStats total = FolderStats.EMPTY;
        for (DataNode node : selectedItems()) {
            total = total.plus(new FolderStats(node.getFileCount(), node.getTotalBytes()));
        }
        return total;
    }

    /** Folder name used for this card's download, e.g. {@code WhalePi_1A_20260830_142530}. */
    public String suggestedFolderName(String timestamp) {
        String serial = settings.getDisplaySerial().replaceAll("[^A-Za-z0-9_-]", "");
        if (serial.isBlank()) serial = "UNKNOWN";
        return "WhalePi_" + serial + "_" + timestamp;
    }
}
