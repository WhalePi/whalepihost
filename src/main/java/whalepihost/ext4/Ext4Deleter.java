package whalepihost.ext4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes a file or a whole folder from an ext4 filesystem, in place.
 *
 * <p>Deleting is the largest change WhalePi Host makes to a card, so the order it works
 * in is chosen so that an interrupted delete is always recoverable:
 *
 * <ol>
 *   <li>the directory entry goes first, which makes everything below it unreachable;</li>
 *   <li>then the inodes and blocks underneath are handed back.</li>
 * </ol>
 *
 * <p>Stopping between the two leaves inodes nobody points at — exactly what
 * {@code e2fsck} reclaims without being asked twice — rather than a live file whose
 * blocks the filesystem believes are free, which is the failure that loses other
 * people's data.
 *
 * <p>Because the folder's own blocks are freed wholesale, the entries inside it are
 * never individually unhooked: only the one name in the parent is rewritten, however
 * many thousands of recordings are below it.
 */
public final class Ext4Deleter {

    /** Told about each file as it goes, so a long delete can say what it is doing. */
    public interface Progress {
        void deleted(String name);

        default boolean isCancelled() { return false; }
    }

    /**
     * What one delete achieved.
     *
     * @param files     regular files removed
     * @param folders   directories removed
     * @param bytes     space handed back to the filesystem
     * @param problems  anything that had to be left behind, for the user
     */
    public record Result(long files, long folders, long bytes, List<String> problems) { }

    /** The magic at the start of an ext4 extended-attribute block. */
    private static final long XATTR_MAGIC = 0xEA020000L;

    /** What is said about a name whose inode cannot be believed. */
    private static final String UNFINISHED =
            "the card has no finished record of it, which happens when the card is taken out "
            + "while the WhalePi is still recording. Its name is gone, but the space it was "
            + "using was left alone rather than risk handing back another file's; e2fsck on a "
            + "Linux machine will reclaim it.";

    private final Ext4FileSystem fs;
    private final Ext4FreeSpace free;
    private final Progress progress;
    private final List<String> problems = new ArrayList<>();

    private long files;
    private long folders;

    Ext4Deleter(Ext4FileSystem fs, Progress progress) {
        this.fs = fs;
        this.free = new Ext4FreeSpace(fs);
        this.progress = progress == null ? name -> { } : progress;
    }

    /** Removes {@code name} from {@code parent}, and everything beneath it. */
    Result delete(Ext4Inode parent, String name) throws IOException {
        if (!parent.isDirectory()) {
            throw new Ext4Exception("Inode " + parent.number() + " is not a directory");
        }
        Ext4DirEntry entry = lookup(parent, name);
        if (entry == null) {
            problems.add(name + ": not on the card any more");
            return result();
        }
        Ext4Inode target = fs.inode(entry.inode());

        try {
            if (!removeEntry(parent, name)) {
                problems.add(name + ": its directory entry could not be rewritten, so nothing "
                        + "was removed");
                return result();
            }
            updateParent(parent, isFolder(entry, target));
            if (describes(entry, target)) {
                freeTree(target, name);
            } else {
                problems.add(name + ": " + UNFINISHED);
            }
            if (progress.isCancelled()) {
                problems.add(name + ": stopped part way through. What is left of it is no "
                        + "longer reachable; e2fsck on a Linux machine will reclaim it.");
            }
        } finally {
            // Whatever happened, what was freed has to be recorded, or the filesystem
            // is left believing blocks are in use that nothing points at.
            free.flush();
        }
        return result();
    }

    private Result result() {
        return new Result(files, folders, free.freedBlocks() * (long) fs.blockSize(),
                List.copyOf(problems));
    }

    /**
     * Whether the inode a directory entry points at really is the file that entry names.
     *
     * <p>An inode that never reached the card — the recording in progress when the card
     * came out — leaves its slot holding whatever was there before, which may be a
     * folder, or a file deleted long ago whose blocks something else is now using.
     * Freeing on the strength of that record would hand back live files' space, so a
     * name whose inode disagrees with it is unhooked and otherwise left alone.
     */
    private static boolean describes(Ext4DirEntry entry, Ext4Inode inode) {
        if (!inode.isAllocated()) return false;
        if (entry.typeUnknown()) return true;
        return entry.isDirectory() == inode.isDirectory()
                && entry.isRegularFile() == inode.isRegularFile();
    }

    /** Whether a name is a folder, believing the directory entry ahead of the inode. */
    private static boolean isFolder(Ext4DirEntry entry, Ext4Inode inode) {
        return entry.typeUnknown() ? inode.isDirectory() : entry.isDirectory();
    }

    private Ext4DirEntry lookup(Ext4Inode directory, String name) throws IOException {
        for (Ext4DirEntry entry : fs.list(directory)) {
            if (entry.name().equals(name)) return entry;
        }
        return null;
    }

    // ------------------------------------------------------------ the tree below

    /**
     * Frees an inode and everything under it. No directory entry is touched: the
     * caller has already detached the top of the tree, and each directory's own blocks
     * go back whole.
     */
    private void freeTree(Ext4Inode inode, String path) throws IOException {
        if (inode.isDirectory()) {
            List<Ext4DirEntry> entries;
            try {
                entries = fs.list(inode);
            } catch (IOException e) {
                problems.add(path + ": could not be listed (" + describe(e) + "), so what is "
                        + "inside it stays on the card");
                return;
            }
            for (Ext4DirEntry entry : entries) {
                if (progress.isCancelled()) return;
                String childPath = path + "/" + entry.name();
                try {
                    Ext4Inode child = fs.inode(entry.inode());
                    if (describes(entry, child)) {
                        freeTree(child, childPath);
                    } else {
                        problems.add(childPath + ": " + UNFINISHED);
                    }
                } catch (IOException e) {
                    problems.add(childPath + ": " + describe(e));
                }
            }
            if (progress.isCancelled()) return;
        }
        freeInode(inode, path);
    }

    private void freeInode(Ext4Inode inode, String path) throws IOException {
        byte[] raw = fs.readRawInode(inode.number());
        ByteBuffer fields = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int links = fields.getShort(0x1A) & 0xFFFF;

        if (!inode.isDirectory() && links > 1) {
            // Another name elsewhere on the card still points at these blocks, so only
            // this link goes; the file itself stays.
            fields.putShort(0x1A, (short) (links - 1));
            setTime(fields, 0x0C, 0x84);
            fs.writeRawInode(inode.number(), raw);
            problems.add(path + ": kept, because another name on the card still points at it");
            return;
        }

        long attributes = (fields.getInt(0x68) & 0xFFFFFFFFL)
                | ((long) (fields.getShort(0x76) & 0xFFFF) << 32);
        if (attributes != 0) freeAttributeBlock(attributes, path);

        if (hasBlockMap(inode)) {
            for (long[] run : allocatedBlocks(inode)) {
                free.freeBlocks(run[0], run[1]);
            }
        }

        // The inode is marked dead before its bit is cleared, so a stop in between
        // leaves something e2fsck recognises rather than a free inode that still
        // claims to be a file.
        fields.putShort(0x1A, (short) 0);
        fields.putInt(0x14, seconds());  // i_dtime: when it was deleted
        fields.putInt(0x04, 0);          // i_size_lo
        fields.putInt(0x6C, 0);          // i_size_high
        fields.putInt(0x1C, 0);          // i_blocks_lo
        fs.writeRawInode(inode.number(), raw);

        free.freeInode(inode.number(), inode.isDirectory());

        if (inode.isDirectory()) {
            folders++;
        } else {
            files++;
            progress.deleted(path);
        }
    }

    /**
     * Hands back a file's extended-attribute block, if it has one to itself.
     *
     * <p>ext4 lets identical attributes be shared between files with a reference count.
     * Sharing is vanishingly rare on a WhalePi card, and unpicking it correctly means
     * rewriting a block this code otherwise never touches, so a shared block is left
     * allocated and reported instead — a block nothing uses, which {@code e2fsck}
     * reclaims, rather than one two files still share.
     */
    private void freeAttributeBlock(long block, String path) throws IOException {
        if (block < fs.firstDataBlock() || block >= fs.blockCount()) {
            problems.add(path + ": its extended attributes point outside the filesystem");
            return;
        }
        ByteBuffer header = fs.readBlock(block);
        if ((header.getInt(0) & 0xFFFFFFFFL) != XATTR_MAGIC) {
            problems.add(path + ": its extended attributes could not be read, so one block "
                    + "was left in use");
            return;
        }
        if ((header.getInt(4) & 0xFFFFFFFFL) > 1) {
            problems.add(path + ": it shares extended attributes with another file, so one "
                    + "block was left in use");
            return;
        }
        free.freeBlocks(block, 1);
    }

    /** True if the inode's {@code i_block} really is a block map rather than data. */
    private static boolean hasBlockMap(Ext4Inode inode) {
        if (inode.hasInlineData()) return false;
        if (inode.isSymlink()) return !inode.isFastSymlink();
        return inode.isRegularFile() || inode.isDirectory();
    }

    // ------------------------------------------------------------- block mapping

    /**
     * Every block the inode owns, as {@code {first, count}} runs.
     *
     * <p>This is deliberately not the reader's extent list. Freeing needs two things
     * reading does not: the blocks of an extent that was allocated but never written,
     * which read as a hole but are still the file's, and the interior blocks of the
     * extent tree itself, which hold no file data but are just as allocated.
     */
    private List<long[]> allocatedBlocks(Ext4Inode inode) throws IOException {
        List<long[]> runs = new ArrayList<>();
        if (inode.usesExtents()) {
            collectExtents(ByteBuffer.wrap(inode.blockData()).order(ByteOrder.LITTLE_ENDIAN),
                    0, runs);
        } else {
            collectIndirect(inode, runs);
        }
        return runs;
    }

    private void collectExtents(ByteBuffer node, int depth, List<long[]> runs)
            throws IOException {
        if (depth > 8) {
            throw new Ext4Exception("Extent tree is unreasonably deep — corrupt filesystem");
        }
        int magic = node.getShort(0) & 0xFFFF;
        if (magic != Ext4FileSystem.extentMagic()) {
            throw new Ext4Exception("Bad extent header magic 0x" + Integer.toHexString(magic));
        }
        int entries = node.getShort(2) & 0xFFFF;
        int treeDepth = node.getShort(6) & 0xFFFF;

        for (int i = 0; i < entries; i++) {
            int at = 12 + i * 12;
            if (at + 12 > node.limit()) break;

            if (treeDepth == 0) {
                int length = node.getShort(at + 4) & 0xFFFF;
                long physical = (node.getInt(at + 8) & 0xFFFFFFFFL)
                        | ((long) (node.getShort(at + 6) & 0xFFFF) << 32);
                // A length above 32768 marks an extent that was allocated but never
                // written. It reads as zeros, but the space is the file's all the same.
                long count = length > 32768 ? length - 32768 : length;
                if (count > 0 && physical != 0) runs.add(new long[] { physical, count });
            } else {
                long child = (node.getInt(at + 4) & 0xFFFFFFFFL)
                        | ((long) (node.getShort(at + 8) & 0xFFFF) << 32);
                if (child == 0) continue;
                collectExtents(fs.readBlock(child), depth + 1, runs);
                runs.add(new long[] { child, 1 });
            }
        }
    }

    /** The older ext2/ext3 layout: direct pointers, then one, two and three levels of them. */
    private void collectIndirect(Ext4Inode inode, List<long[]> runs) throws IOException {
        ByteBuffer direct = ByteBuffer.wrap(inode.blockData()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 12; i++) {
            long block = direct.getInt(i * 4) & 0xFFFFFFFFL;
            if (block != 0) runs.add(new long[] { block, 1 });
        }
        for (int level = 1; level <= 3; level++) {
            long block = direct.getInt((11 + level) * 4) & 0xFFFFFFFFL;
            if (block == 0) continue;
            collectIndirectBlock(block, level, runs);
            runs.add(new long[] { block, 1 });
        }
    }

    private void collectIndirectBlock(long block, int level, List<long[]> runs)
            throws IOException {
        ByteBuffer table = fs.readBlock(block);
        for (int i = 0; i < fs.blockSize() / 4; i++) {
            long pointer = table.getInt(i * 4) & 0xFFFFFFFFL;
            if (pointer == 0) continue;
            if (level > 1) collectIndirectBlock(pointer, level - 1, runs);
            runs.add(new long[] { pointer, 1 });
        }
    }

    // --------------------------------------------------------- directory entries

    /**
     * Takes one name out of a directory, the way the kernel's {@code ext4_delete_entry}
     * does: the entry's record length is absorbed by whichever entry precedes it in the
     * same block, so the name simply stops being reachable. Nothing moves, and the
     * block stays exactly the size it was.
     *
     * @return false if the name was not found, in which case nothing was written
     */
    private boolean removeEntry(Ext4Inode directory, String name) throws IOException {
        byte[] wanted = name.getBytes(StandardCharsets.UTF_8);
        int blockSize = fs.blockSize();
        byte[] block = new byte[blockSize];
        long blocks = (directory.size() + blockSize - 1) / blockSize;
        int seed = directorySeed(directory);

        for (long index = 0; index < blocks; index++) {
            long physical = fs.blockOf(directory, index);
            if (physical == 0) continue; // a hole: no entries live there
            fs.device().readInto(physical * (long) blockSize, block, 0, blockSize);
            ByteBuffer entries = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);

            int offset = 0;
            int previous = -1;
            while (offset + 8 <= blockSize) {
                int recordLength = entries.getShort(offset + 4) & 0xFFFF;
                if (recordLength < 8 || recordLength % 4 != 0
                        || offset + recordLength > blockSize) {
                    break; // not an entry block after all — an htree interior node
                }
                long inodeNumber = entries.getInt(offset) & 0xFFFFFFFFL;
                int nameLength = block[offset + 6] & 0xFF;

                if (inodeNumber != 0 && nameLength == wanted.length
                        && nameLength + 8 <= recordLength
                        && matches(block, offset + 8, wanted)) {
                    if (previous >= 0) {
                        int grown = (entries.getShort(previous + 4) & 0xFFFF) + recordLength;
                        entries.putShort(previous + 4, (short) grown);
                    } else {
                        // First in the block, so there is nothing to absorb it; it
                        // becomes an empty slot for the next name written here.
                        entries.putInt(offset, 0);
                        block[offset + 6] = 0;
                        block[offset + 7] = 0;
                    }
                    if (fs.metadataChecksums()) {
                        Ext4Checksum.applyDirectoryBlockChecksum(block, blockSize, seed);
                    }
                    fs.device().write(physical * (long) blockSize, block, 0, blockSize);
                    fs.device().flush();
                    return true;
                }
                previous = offset;
                offset += recordLength;
            }
        }
        return false;
    }

    private static boolean matches(byte[] block, int offset, byte[] name) {
        for (int i = 0; i < name.length; i++) {
            if (block[offset + i] != name[i]) return false;
        }
        return true;
    }

    /** The seed a directory's own blocks are checksummed from. */
    private int directorySeed(Ext4Inode directory) throws IOException {
        ByteBuffer raw = ByteBuffer.wrap(fs.readRawInode(directory.number()))
                .order(ByteOrder.LITTLE_ENDIAN);
        return Ext4Checksum.inodeSeed(fs.checksumSeed(), directory.number(), raw.getInt(0x64));
    }

    /**
     * Records that a directory has lost an entry: its modification time moves, and if
     * what went was a subdirectory, so does its link count — the {@code ..} that
     * pointed back at it has gone with it.
     */
    private void updateParent(Ext4Inode parent, boolean lostSubdirectory) throws IOException {
        byte[] raw = fs.readRawInode(parent.number());
        ByteBuffer fields = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        if (lostSubdirectory) {
            int links = fields.getShort(0x1A) & 0xFFFF;
            // 1 is ext4's "more subdirectories than this can count" marker, and 2 is
            // the floor — the directory's own "." and its name in its parent.
            if (links > 2) fields.putShort(0x1A, (short) (links - 1));
        }
        setTime(fields, 0x0C, 0x84);  // i_ctime
        setTime(fields, 0x10, 0x88);  // i_mtime
        fs.writeRawInode(parent.number(), raw);
    }

    /**
     * Sets a timestamp to now.
     *
     * <p>A large inode splits each time in two: seconds here, and a companion field
     * holding nanoseconds plus the two bits that carry it past 2038. Writing the
     * seconds alone would leave those bits saying something about a different moment,
     * so the companion is cleared — which is what a date in this century wants anyway.
     */
    private void setTime(ByteBuffer fields, int offset, int extraOffset) {
        fields.putInt(offset, seconds());
        if (fs.inodeSize() > 128 && extraOffset + 4 <= 128 + (fields.getShort(0x80) & 0xFFFF)) {
            fields.putInt(extraOffset, 0);
        }
    }

    private static int seconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
