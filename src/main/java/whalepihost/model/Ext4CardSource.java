package whalepihost.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import whalepihost.ext4.Ext4Deleter;
import whalepihost.ext4.Ext4DirEntry;
import whalepihost.ext4.Ext4FileSystem;
import whalepihost.ext4.Ext4Inode;

/**
 * A card read straight off its ext4 partition, with nothing mounted.
 *
 * <p>This is what makes WhalePi Host work on a computer where the user cannot install
 * an ext4 driver. The paths in the settings file are the Pi's own absolute paths, and
 * here they are exactly right: this <em>is</em> the Pi's filesystem.
 */
public class Ext4CardSource implements CardSource {

    private final Ext4FileSystem fs;
    private final String label;

    public Ext4CardSource(Path device, String label) throws IOException {
        this.label = label;
        // Try read-write first so the Settings tab can save. Raw devices are writable
        // by root alone, so this normally falls back and the card opens read-only.
        Ext4FileSystem opened;
        try {
            opened = Ext4FileSystem.openForWriting(device);
        } catch (IOException e) {
            opened = Ext4FileSystem.open(device);
        }
        this.fs = opened;
    }

    @Override
    public String description() {
        return label;
    }

    @Override
    public CardFile root() throws IOException {
        return new Ext4CardFile("", "/", null, fs.rootInode());
    }

    @Override
    public CardFile find(String absolutePath) throws IOException {
        if (absolutePath == null || absolutePath.isBlank()) return null;
        Ext4Inode inode = fs.resolve(absolutePath);
        if (inode == null) return null;

        int slash = absolutePath.lastIndexOf('/');
        String name = slash < 0 ? absolutePath : absolutePath.substring(slash + 1);
        return new Ext4CardFile(name, absolutePath, null, inode);
    }

    @Override
    public boolean canWrite() {
        return fs.isWritable() && fs.journalBlockedReason().isEmpty();
    }

    @Override
    public String writeBlockedReason() {
        if (fs.isWritable()) return fs.journalBlockedReason();
        String elevate = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")
                ? "Close WhalePi Host and start it again with \"Run as administrator\"."
                : "Close WhalePi Host and start it again with administrator rights, for example:\n\n"
                  + "    sudo \"/Applications/WhalePi Host.app/Contents/MacOS/WhalePi Host\"";
        return "The card was opened for reading only. Reading a disk directly needs permission "
                + "to read it; changing one needs permission to write it, which the system "
                + "grants only to an administrator.\n\n" + elevate;
    }

    /**
     * An unmounted card cannot have space allocated to a file, so the ceiling is
     * whatever the file's existing blocks hold — a small file in a 4 KiB block has
     * far more room than its current length suggests.
     */
    @Override
    public long writeCapacity(CardFile file) {
        if (!(file instanceof Ext4CardFile ext4File)) return file.size();
        try {
            return fs.allocatedBytes(ext4File.inode());
        } catch (IOException e) {
            return file.size();
        }
    }

    @Override
    public void write(CardFile file, byte[] content) throws IOException {
        if (!(file instanceof Ext4CardFile ext4File)) {
            throw new IOException("That file is not on this card");
        }
        fs.overwriteInPlace(ext4File.inode(), content);
    }

    @Override
    public boolean canDelete() {
        return fs.deleteBlockedReason().isEmpty();
    }

    @Override
    public String deleteBlockedReason() {
        String reason = fs.deleteBlockedReason();
        // The commonest reason by far is permission, and the writer's own explanation
        // of how to get it is the useful one.
        return reason.isEmpty() || fs.isWritable() ? reason : writeBlockedReason();
    }

    /**
     * Removes a file or folder from the card itself.
     *
     * <p>The ext4 layer works from a parent directory and a name, because that is what
     * a directory entry is. The card's paths are the Pi's own absolute paths, so the
     * parent is simply the path with its last element taken off.
     */
    @Override
    public DeleteResult delete(CardFile file, DeleteProgress progress) throws IOException {
        if (!(file instanceof Ext4CardFile ext4File)) {
            throw new IOException("That file is not on this card");
        }
        String path = ext4File.path();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0 || path.equals("/")) {
            throw new IOException("The root of the card cannot be deleted");
        }
        String parentPath = slash == 0 ? "/" : path.substring(0, slash);
        Ext4Inode parent = fs.resolve(parentPath);
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("The folder holding " + ext4File.name() + " could not be found");
        }

        String name = path.substring(slash + 1);
        Ext4Deleter.Result result = fs.delete(parent, name, new Ext4Deleter.Progress() {
            @Override
            public void deleted(String deletedPath) {
                progress.deleted(deletedPath);
            }

            @Override
            public boolean isCancelled() {
                return progress.isCancelled();
            }
        });
        return new DeleteResult(result.files(), result.folders(), result.bytes(),
                result.problems());
    }

    /**
     * What the user should know about the state of this card before trusting what they
     * see, or "" when there is nothing to say.
     */
    @Override
    public String warning() {
        if (fs.journalledBlocks() > 0) {
            return "This card was not shut down cleanly, so its newest writes had not reached "
                    + "the places they belong. WhalePi Host has read them out of the card's "
                    + "journal — the same recovery Linux does when it mounts a card — so the "
                    + "newest recordings download correctly. The card itself has not been "
                    + "changed, and settings cannot be edited until it has been shut down "
                    + "cleanly.";
        }
        String declined = fs.journalDeclinedReason();
        if (!declined.isEmpty()) {
            return "This card was not shut down cleanly and " + declined + ". The last "
                    + "recording of the deployment may not be readable; everything written "
                    + "before it is unaffected.\n\nPutting the card back in the WhalePi and "
                    + "shutting it down properly, or mounting it once on a Linux machine, "
                    + "will finish that write.";
        }
        if (!fs.isClean()) {
            return "This card was not shut down cleanly. Nothing was left outstanding in its "
                    + "journal, so everything on it is readable.";
        }
        return "";
    }

    @Override
    public void close() throws IOException {
        fs.close();
    }

    /** A file or folder inside the ext4 filesystem. */
    private final class Ext4CardFile implements CardFile {

        private final String name;
        private final String path;
        /** The entry that named this file, or null for the root and for direct lookups. */
        private final Ext4DirEntry entry;
        private final Ext4Inode inode;

        private Ext4CardFile(String name, String path, Ext4DirEntry entry, Ext4Inode inode) {
            this.name = name;
            this.path = path;
            this.entry = entry;
            this.inode = inode;
        }

        Ext4Inode inode() { return inode; }

        @Override
        public String name() { return name; }

        @Override
        public String path() { return path; }

        /**
         * Whether this is a folder, according to the directory that names it.
         *
         * <p>The entry's own {@code file_type} is asked first, not the inode. On a card
         * taken out of a running WhalePi the newest recording's inode may not have
         * reached the card, leaving its slot holding whatever was there before — often a
         * folder deleted earlier, which would otherwise turn that recording into an empty
         * folder in the download. Filesystems old enough not to record the type in the
         * entry leave the inode as the only answer.
         */
        @Override
        public boolean isDirectory() {
            return entry == null || entry.typeUnknown() ? inode.isDirectory()
                    : entry.isDirectory();
        }

        @Override
        public boolean isRegularFile() {
            return entry == null || entry.typeUnknown() ? inode.isRegularFile()
                    : entry.isRegularFile();
        }

        /**
         * True when the inode really is the file its directory entry describes, so its
         * size, timestamp and blocks are this file's own.
         */
        private boolean inodeUsable() {
            return inode.isAllocated()
                    && inode.isDirectory() == isDirectory()
                    && inode.isRegularFile() == isRegularFile();
        }

        @Override
        public long size() { return isRegularFile() && inodeUsable() ? inode.size() : 0; }

        @Override
        public long modifiedEpochSeconds() {
            return inodeUsable() ? inode.modifiedEpochSeconds() : 0;
        }

        @Override
        public List<CardFile> list() throws IOException {
            if (!isDirectory() || !inodeUsable()) return List.of();
            List<CardFile> children = new ArrayList<>();
            for (Ext4DirEntry child : fs.list(inode)) {
                children.add(new Ext4CardFile(child.name(), join(path, child.name()), child,
                        fs.inode(child.inode())));
            }
            children.sort(Comparator.comparing(CardFile::name));
            return children;
        }

        @Override
        public InputStream open() throws IOException {
            if (!isRegularFile() || !inodeUsable()) throw new IOException(unreadable());
            return fs.open(inode);
        }

        @Override
        public CardFile child(String childName) throws IOException {
            if (!isDirectory() || !inodeUsable()) return null;
            for (Ext4DirEntry child : fs.list(inode)) {
                if (child.name().equals(childName)) {
                    return new Ext4CardFile(childName, join(path, childName), child,
                            fs.inode(child.inode()));
                }
            }
            return null;
        }

        /**
         * Why a file the card names cannot be read. It is nearly always the last
         * recording of a deployment, so this names the cause rather than only saying
         * that the copy failed.
         */
        private String unreadable() {
            return "the card names this file but has no finished record of it, which happens "
                    + "when the card is taken out while the WhalePi is still recording. "
                    + "Everything written before it is unaffected.";
        }

        private String join(String parent, String child) {
            return parent.endsWith("/") ? parent + child : parent + "/" + child;
        }
    }
}
