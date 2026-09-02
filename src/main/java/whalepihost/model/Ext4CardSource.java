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
        return new Ext4CardFile("", "/", fs.rootInode());
    }

    @Override
    public CardFile find(String absolutePath) throws IOException {
        if (absolutePath == null || absolutePath.isBlank()) return null;
        Ext4Inode inode = fs.resolve(absolutePath);
        if (inode == null) return null;

        int slash = absolutePath.lastIndexOf('/');
        String name = slash < 0 ? absolutePath : absolutePath.substring(slash + 1);
        return new Ext4CardFile(name, absolutePath, inode);
    }

    @Override
    public boolean canWrite() {
        return fs.isWritable();
    }

    @Override
    public String writeBlockedReason() {
        if (fs.isWritable()) return "";
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

    @Override
    public String warning() {
        if (!fs.isClean()) {
            return "This card was not shut down cleanly, so the very newest recordings may be "
                    + "incomplete. Everything already written is readable.";
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
        private final Ext4Inode inode;

        private Ext4CardFile(String name, String path, Ext4Inode inode) {
            this.name = name;
            this.path = path;
            this.inode = inode;
        }

        Ext4Inode inode() { return inode; }

        @Override
        public String name() { return name; }

        @Override
        public String path() { return path; }

        @Override
        public boolean isDirectory() { return inode.isDirectory(); }

        @Override
        public boolean isRegularFile() { return inode.isRegularFile(); }

        @Override
        public long size() { return inode.isRegularFile() ? inode.size() : 0; }

        @Override
        public long modifiedEpochSeconds() { return inode.modifiedEpochSeconds(); }

        @Override
        public List<CardFile> list() throws IOException {
            if (!inode.isDirectory()) return List.of();
            List<CardFile> children = new ArrayList<>();
            for (Ext4DirEntry entry : fs.list(inode)) {
                children.add(new Ext4CardFile(entry.name(), join(path, entry.name()),
                        fs.inode(entry.inode())));
            }
            children.sort(Comparator.comparing(CardFile::name));
            return children;
        }

        @Override
        public InputStream open() throws IOException {
            return fs.open(inode);
        }

        @Override
        public CardFile child(String childName) throws IOException {
            if (!inode.isDirectory()) return null;
            for (Ext4DirEntry entry : fs.list(inode)) {
                if (entry.name().equals(childName)) {
                    return new Ext4CardFile(childName, join(path, childName),
                            fs.inode(entry.inode()));
                }
            }
            return null;
        }

        private String join(String parent, String child) {
            return parent.endsWith("/") ? parent + child : parent + "/" + child;
        }
    }
}
