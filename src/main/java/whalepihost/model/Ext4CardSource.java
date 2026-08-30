package whalepihost.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
     * An unmounted card can only have a file rewritten at exactly its current length,
     * so that is the capacity.
     */
    @Override
    public long writeCapacity(CardFile file) {
        return file.size();
    }

    @Override
    public void write(CardFile file, byte[] content) throws IOException {
        if (!(file instanceof Ext4CardFile ext4File)) {
            throw new IOException("That file is not on this card");
        }
        fs.overwriteInPlace(ext4File.inode(), content);
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
