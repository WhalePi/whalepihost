package whalepihost.model;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * One row in the card contents tree.
 *
 * <p>A node is either a <em>group</em> (Sound recordings, Database, …) that only
 * exists to hold children, or a <em>copy unit</em> – a single file or a whole
 * folder that is copied recursively when the node is selected.
 */
public class DataNode {

    public enum Kind {
        RECORDINGS("Sound recordings"),
        DATABASE("Database"),
        CONFIG("PAMGuard configuration"),
        BINARY("Binary files"),
        FOLDER("Folder"),
        FILE("File");

        private final String label;
        Kind(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final String name;
    private final CardFile source;
    private final String destRelative;
    private final Kind kind;
    private final boolean copyUnit;

    private final List<DataNode> children = new ArrayList<>();
    private DataNode parent;

    private long fileCount;
    private long totalBytes;
    private String note = "";

    private final BooleanProperty selected = new SimpleBooleanProperty(true);
    private final BooleanProperty indeterminate = new SimpleBooleanProperty(false);

    /** Guards the parent/child cascade against re-entrancy. */
    private boolean updating;

    /**
     * @param name         text shown in the Item column
     * @param source       where the data lives on the card (null for a pure group)
     * @param destRelative path, relative to the chosen download folder, to copy into
     * @param kind         icon / category
     * @param copyUnit     true if selecting this node copies {@code source} (file or folder)
     */
    public DataNode(String name, CardFile source, String destRelative, Kind kind,
                    boolean copyUnit) {
        this.name = name;
        this.source = source;
        this.destRelative = destRelative;
        this.kind = kind;
        this.copyUnit = copyUnit;
    }

    public static DataNode group(String name, Kind kind) {
        return new DataNode(name, null, null, kind, false);
    }

    public String getName() { return name; }
    public CardFile getSource() { return source; }
    public String getDestRelative() { return destRelative; }
    public Kind getKind() { return kind; }
    public boolean isCopyUnit() { return copyUnit; }
    public DataNode getParent() { return parent; }
    public List<DataNode> getChildren() { return children; }

    public long getFileCount() { return fileCount; }
    public long getTotalBytes() { return totalBytes; }

    public void setCounts(long fileCount, long totalBytes) {
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
    }

    /** Extra text shown beside the name, e.g. "not found on card". */
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null ? "" : note; }

    public BooleanProperty selectedProperty() { return selected; }
    public BooleanProperty indeterminateProperty() { return indeterminate; }
    public boolean isSelected() { return selected.get(); }

    public void addChild(DataNode child) {
        child.parent = this;
        children.add(child);
    }

    /** Sums child counts into this node, bottom-up. Call once the tree is built. */
    public void rollUpCounts() {
        if (children.isEmpty()) return;
        long files = 0;
        long bytes = 0;
        for (DataNode c : children) {
            c.rollUpCounts();
            files += c.getFileCount();
            bytes += c.getTotalBytes();
        }
        setCounts(files, bytes);
    }

    /**
     * Applies a tick/untick made by the user: the whole subtree follows, then every
     * ancestor is recomputed so it shows ticked / unticked / partially ticked.
     */
    public void setSelectedByUser(boolean value) {
        applyDown(value);
        DataNode p = parent;
        while (p != null) {
            p.refreshFromChildren();
            p = p.parent;
        }
    }

    private void applyDown(boolean value) {
        updating = true;
        selected.set(value);
        indeterminate.set(false);
        updating = false;
        for (DataNode c : children) {
            c.applyDown(value);
        }
    }

    private void refreshFromChildren() {
        if (children.isEmpty() || updating) return;
        boolean all = true;
        boolean none = true;
        for (DataNode c : children) {
            if (c.selected.get() || c.indeterminate.get()) none = false;
            if (!c.selected.get() || c.indeterminate.get()) all = false;
        }
        updating = true;
        selected.set(all);
        indeterminate.set(!all && !none);
        updating = false;
    }

    /**
     * The card-level group this node belongs to — Sound recordings, Database, and so
     * on — which is what the delete dialog offers as a thing to keep or lose.
     */
    public DataNode group() {
        DataNode node = this;
        while (node.parent != null && node.parent.parent != null) {
            node = node.parent;
        }
        return node;
    }

    /** Every selected copy unit in this subtree, in tree order. */
    public void collectSelected(List<DataNode> out) {
        if (copyUnit && selected.get()) {
            out.add(this);
            return; // a selected folder is copied whole; no need to descend
        }
        for (DataNode c : children) {
            c.collectSelected(out);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
