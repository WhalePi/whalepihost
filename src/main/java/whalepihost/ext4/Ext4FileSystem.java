package whalepihost.ext4;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A read-only ext2/3/4 reader, working straight off a partition or disk image.
 *
 * <p>This exists so WhalePi Host can read a WhalePi card on a machine where the user
 * cannot install an ext4 driver — a managed Mac, or a Windows PC without admin rights
 * to install one. Nothing is mounted and nothing is written: the filesystem is parsed
 * in user space.
 *
 * <p>Supported: extent-mapped and indirect-mapped files, 64-bit filesystems, flex_bg,
 * sparse files, and directories of any size. Metadata checksums are not verified,
 * which is harmless for reading, and the journal is not replayed — see
 * {@link #isClean()}.
 */
public class Ext4FileSystem implements Closeable {

    private static final long SUPERBLOCK_OFFSET = 1024;
    private static final int  SUPERBLOCK_MAGIC  = 0xEF53;
    private static final int  EXTENT_MAGIC      = 0xF30A;

    /** s_feature_incompat: group descriptors are 64 bytes and block numbers 64-bit. */
    private static final int INCOMPAT_64BIT = 0x0080;
    /** s_feature_incompat: the superblock carries the checksum seed directly. */
    private static final int INCOMPAT_CSUM_SEED = 0x2000;
    /** s_feature_incompat: the journal holds changes that have not reached the disk. */
    private static final int INCOMPAT_NEEDS_RECOVERY = 0x0004;
    /** s_feature_incompat: group descriptors are scattered rather than in one table. */
    private static final int INCOMPAT_META_BG = 0x0010;
    /** s_feature_ro_compat: metadata carries CRC32C checksums. */
    private static final int RO_COMPAT_METADATA_CSUM = 0x0400;
    /** s_feature_ro_compat: group descriptors carry CRC-16 checksums (the older form). */
    private static final int RO_COMPAT_GDT_CSUM = 0x0010;
    /** s_feature_ro_compat: the filesystem keeps quota accounting of its own. */
    private static final int RO_COMPAT_QUOTA = 0x0100;
    /** s_feature_ro_compat: space is tracked in multi-block clusters, not blocks. */
    private static final int RO_COMPAT_BIGALLOC = 0x0200;
    /** s_feature_compat: the filesystem carries a journal. */
    private static final int COMPAT_HAS_JOURNAL = 0x0004;
    /** s_feature_incompat: the journal lives on a separate device. */
    private static final int INCOMPAT_JOURNAL_DEV = 0x0008;

    /** bg_flags: the group's block bitmap has never been written. */
    static final int BG_BLOCK_UNINIT = 0x0002;
    /** bg_flags: the group's inode bitmap has never been written. */
    static final int BG_INODE_UNINIT = 0x0001;

    /** The filesystem root always lives in inode 2. */
    public static final long ROOT_INODE = 2;

    private final BlockDevice device;

    private final int blockSize;
    private final long blocksPerGroup;
    private final long inodesPerGroup;
    private final int inodeSize;
    private final long inodeCount;
    private final long blockCount;
    private final long firstDataBlock;
    private final int descriptorSize;
    private final int state;
    private final String volumeName;
    private final boolean metadataChecksums;
    private final boolean groupDescriptorChecksums;
    private final int checksumSeed;
    private final byte[] uuid = new byte[16];
    private final int compatibleFeatures;
    private final int incompatibleFeatures;
    private final int readOnlyFeatures;
    private final long groupCount;

    /**
     * The journal's view of the card, when it holds writes that never reached their
     * proper places. Null until the constructor has finished working it out, and null
     * afterwards on a filesystem that was shut down cleanly.
     */
    private Ext4Journal journal;

    private Ext4FileSystem(BlockDevice device) throws IOException {
        this.device = device;

        ByteBuffer sb = device.read(SUPERBLOCK_OFFSET, 1024);
        int magic = sb.getShort(0x38) & 0xFFFF;
        if (magic != SUPERBLOCK_MAGIC) {
            throw new Ext4Exception("Not an ext2/3/4 filesystem (superblock magic 0x"
                    + Integer.toHexString(magic) + ")");
        }

        this.inodeCount     = sb.getInt(0x00) & 0xFFFFFFFFL;
        this.firstDataBlock = sb.getInt(0x14) & 0xFFFFFFFFL;
        this.blockSize      = 1024 << sb.getInt(0x18);
        this.blocksPerGroup = sb.getInt(0x20) & 0xFFFFFFFFL;
        this.inodesPerGroup = sb.getInt(0x28) & 0xFFFFFFFFL;
        this.state          = sb.getShort(0x3A) & 0xFFFF;

        int revision = sb.getInt(0x4C);
        // Revision 0 filesystems predate a configurable inode size.
        this.inodeSize = revision == 0 ? 128 : (sb.getShort(0x58) & 0xFFFF);

        int incompatible = sb.getInt(0x60);
        this.compatibleFeatures = sb.getInt(0x5C);
        this.incompatibleFeatures = incompatible;
        this.readOnlyFeatures = sb.getInt(0x64);
        boolean sixtyFourBit = (incompatible & INCOMPAT_64BIT) != 0;
        int declaredDescSize = sb.getShort(0xFE) & 0xFFFF;
        this.descriptorSize = sixtyFourBit && declaredDescSize >= 64 ? declaredDescSize : 32;

        this.blockCount = (sb.getInt(0x04) & 0xFFFFFFFFL)
                | (sixtyFourBit ? ((sb.getInt(0x150) & 0xFFFFFFFFL) << 32) : 0);

        this.volumeName = readName(sb, 0x78, 16);

        for (int i = 0; i < 16; i++) uuid[i] = sb.get(0x68 + i);

        this.metadataChecksums = (readOnlyFeatures & RO_COMPAT_METADATA_CSUM) != 0;
        this.groupDescriptorChecksums = (readOnlyFeatures & RO_COMPAT_GDT_CSUM) != 0;
        this.checksumSeed = (incompatible & INCOMPAT_CSUM_SEED) != 0
                ? sb.getInt(0x270)
                : Ext4Checksum.crc32c(~0, uuid);

        if (blockSize < 1024 || blockSize > 65536) {
            throw new Ext4Exception("Unsupported ext4 block size: " + blockSize);
        }
        if (inodesPerGroup == 0 || blocksPerGroup == 0) {
            throw new Ext4Exception("Corrupt ext4 superblock: empty block or inode groups");
        }
        this.groupCount = (blockCount - firstDataBlock + blocksPerGroup - 1) / blocksPerGroup;

        this.journal = readJournal(sb);
    }

    /**
     * Works out what the journal supersedes, on a card that was not shut down cleanly.
     *
     * <p>This runs before anything else reads the filesystem, and reads only through the
     * device: the journal describes the card, so it cannot be read through itself. A
     * cleanly unmounted card has an empty journal and this does nothing.
     */
    private Ext4Journal readJournal(ByteBuffer sb) {
        if (isClean() && (incompatibleFeatures & INCOMPAT_NEEDS_RECOVERY) == 0) return null;
        if ((compatibleFeatures & COMPAT_HAS_JOURNAL) == 0) return null;
        if ((incompatibleFeatures & INCOMPAT_JOURNAL_DEV) != 0) return null;

        long journalInode = sb.getInt(0xE0) & 0xFFFFFFFFL;
        if (journalInode < 1 || journalInode > inodeCount) return null;
        try {
            return Ext4Journal.read(this, inode(journalInode));
        } catch (IOException | RuntimeException e) {
            // A card that will not give up its journal still has to open.
            return null;
        }
    }

    /** Opens the filesystem on a partition device or image file. */
    public static Ext4FileSystem open(Path deviceOrImage) throws IOException {
        return open(DeviceRegion.whole(deviceOrImage));
    }

    /**
     * Opens the filesystem in one region of a device: on Windows a partition inside a
     * whole disk, elsewhere the whole of a partition device.
     */
    public static Ext4FileSystem open(DeviceRegion region) throws IOException {
        BlockDevice device = new BlockDevice(region, false);
        try {
            return new Ext4FileSystem(device);
        } catch (IOException | RuntimeException e) {
            device.close();
            throw e;
        }
    }

    /** Quick check for an ext4 superblock, used to tell a card from any other disk. */
    public static boolean looksLikeExt4(Path deviceOrImage) {
        return looksLikeExt4(DeviceRegion.whole(deviceOrImage));
    }

    /** Quick check for an ext4 superblock in one region of a device. */
    public static boolean looksLikeExt4(DeviceRegion region) {
        try (BlockDevice device = new BlockDevice(region, false)) {
            ByteBuffer sb = device.read(SUPERBLOCK_OFFSET, 64);
            return (sb.getShort(0x38) & 0xFFFF) == SUPERBLOCK_MAGIC;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public int blockSize() { return blockSize; }

    public long totalBytes() { return blockCount * (long) blockSize; }

    /** Volume label, or "" if the filesystem has none. */
    public String volumeName() { return volumeName; }

    /**
     * True if the filesystem was cleanly unmounted. A card pulled out of a running Pi
     * may have changes sitting in the journal that this reader does not replay, so the
     * newest few files could be missing or short.
     */
    public boolean isClean() { return (state & 0x0001) != 0; }

    // ------------------------------------------------------------------- inodes

    /** Reads an inode by number. Inode numbering starts at 1. */
    public Ext4Inode inode(long number) throws IOException {
        if (number < 1 || number > inodeCount) {
            throw new Ext4Exception("Inode " + number + " is outside the filesystem");
        }
        long group = (number - 1) / inodesPerGroup;
        long indexInGroup = (number - 1) % inodesPerGroup;

        long tableBlock = inodeTableBlock(group);
        long offset = tableBlock * (long) blockSize + indexInGroup * inodeSize;
        return new Ext4Inode(number, read(offset, Math.min(inodeSize, 256)));
    }

    /** Byte offset of an inode's record within the filesystem. */
    private long inodeOffset(long number) throws IOException {
        long group = (number - 1) / inodesPerGroup;
        long indexInGroup = (number - 1) % inodesPerGroup;
        return inodeTableBlock(group) * (long) blockSize + indexInGroup * inodeSize;
    }

    private long inodeTableBlock(long group) throws IOException {
        // The group descriptor table follows the superblock's block.
        long tableStart = (firstDataBlock + 1) * (long) blockSize;
        ByteBuffer descriptor = read(tableStart + group * descriptorSize, descriptorSize);

        long low = descriptor.getInt(0x08) & 0xFFFFFFFFL;
        long high = descriptorSize >= 64 ? (descriptor.getInt(0x28) & 0xFFFFFFFFL) : 0;
        return low | (high << 32);
    }

    public Ext4Inode rootInode() throws IOException {
        return inode(ROOT_INODE);
    }

    // -------------------------------------------------------------- directories

    /**
     * Lists a directory, skipping {@code .} and {@code ..}.
     *
     * <p>Entries are read linearly. That is correct even for hash-indexed (htree)
     * directories, because their interior nodes are disguised as entries with inode 0,
     * which are skipped here along with the padding at the end of each block.
     */
    public List<Ext4DirEntry> list(Ext4Inode directory) throws IOException {
        if (!directory.isDirectory()) {
            throw new Ext4Exception("Inode " + directory.number() + " is not a directory");
        }
        if (directory.hasInlineData()) {
            // Tiny directories can live inside the inode; not produced by mke2fs on a Pi.
            throw new Ext4Exception("Directories with inline data are not supported");
        }

        List<Ext4DirEntry> entries = new ArrayList<>();
        byte[] block = new byte[blockSize];
        long blocks = (directory.size() + blockSize - 1) / blockSize;

        for (long index = 0; index < blocks; index++) {
            if (!readFileBlock(directory, index, block)) continue; // sparse: nothing there

            int offset = 0;
            while (offset + 8 <= blockSize) {
                ByteBuffer buffer = ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                long inodeNumber = buffer.getInt(offset) & 0xFFFFFFFFL;
                int recordLength = buffer.getShort(offset + 4) & 0xFFFF;
                int nameLength = block[offset + 6] & 0xFF;
                int fileType = block[offset + 7] & 0xFF;

                // A zero or absurd record length means the block is not a dirent block.
                if (recordLength < 8 || recordLength % 4 != 0 || offset + recordLength > blockSize) {
                    break;
                }
                if (inodeNumber != 0 && nameLength > 0 && nameLength + 8 <= recordLength) {
                    String name = new String(block, offset + 8, nameLength, StandardCharsets.UTF_8);
                    if (!name.equals(".") && !name.equals("..") && inodeNumber <= inodeCount) {
                        entries.add(new Ext4DirEntry(name, inodeNumber, fileType));
                    }
                }
                offset += recordLength;
            }
        }
        return entries;
    }

    /**
     * Finds an absolute path such as {@code /home/whalepi/PAMRecordings}.
     *
     * @return the inode, or null if any component is missing
     */
    public Ext4Inode resolve(String absolutePath) throws IOException {
        Ext4Inode current = rootInode();
        for (String part : absolutePath.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (!current.isDirectory()) return null;

            Ext4Inode next = null;
            for (Ext4DirEntry entry : list(current)) {
                if (entry.name().equals(part)) {
                    next = inode(entry.inode());
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    // -------------------------------------------------------------- file content

    /** Streams a regular file's contents. */
    public InputStream open(Ext4Inode inode) throws IOException {
        if (inode.hasInlineData()) {
            throw new Ext4Exception("Files with inline data are not supported");
        }
        return new Ext4InputStream(this, inode);
    }

    /**
     * Reads one block of a file into {@code target}.
     *
     * @return false if the block is a hole, in which case {@code target} is zeroed
     */
    boolean readFileBlock(Ext4Inode inode, long logicalBlock, byte[] target) throws IOException {
        long physical = physicalBlock(inode, logicalBlock);
        if (physical == 0) {
            java.util.Arrays.fill(target, 0, blockSize, (byte) 0);
            return false;
        }
        readInto(physical * (long) blockSize, target, 0, blockSize);
        return true;
    }

    /** Device block holding a given file block, or 0 for a hole. */
    private long physicalBlock(Ext4Inode inode, long logicalBlock) throws IOException {
        List<Extent> extents = extents(inode);
        int low = 0;
        int high = extents.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Extent extent = extents.get(mid);
            if (extent.contains(logicalBlock)) return extent.physicalFor(logicalBlock);
            if (logicalBlock < extent.logicalBlock()) high = mid - 1;
            else low = mid + 1;
        }
        return 0;
    }

    /** The block map of a file, however the filesystem happens to store it. */
    private List<Extent> extents(Ext4Inode inode) throws IOException {
        List<Extent> cached = inode.cachedExtents();
        if (cached != null) return cached;

        List<Extent> extents = inode.usesExtents()
                ? readExtentTree(ByteBuffer.wrap(inode.blockData())
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN), 0)
                : readIndirectBlocks(inode);
        inode.cacheExtents(extents);
        return extents;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Opens the filesystem for writing as well as reading.
     *
     * <p>Raw devices are owned by root with write permission for nobody else, so on
     * macOS and Windows this needs the program to be running elevated.
     */
    public static Ext4FileSystem openForWriting(Path deviceOrImage) throws IOException {
        return openForWriting(DeviceRegion.whole(deviceOrImage));
    }

    /** Opens one region of a device for writing as well as reading. */
    public static Ext4FileSystem openForWriting(DeviceRegion region) throws IOException {
        BlockDevice device = new BlockDevice(region, true);
        try {
            return new Ext4FileSystem(device);
        } catch (IOException | RuntimeException e) {
            device.close();
            throw e;
        }
    }

    public boolean isWritable() {
        return device.isWritable();
    }

    /**
     * How many bytes a file could hold without allocating another block.
     *
     * <p>A file always occupies whole blocks, so a 911-byte settings file in a 4 KiB
     * block has room for 4096. That spare space is what lets settings grow.
     */
    public long allocatedBytes(Ext4Inode inode) throws IOException {
        long blocks = 0;
        for (Extent extent : extents(inode)) {
            blocks = Math.max(blocks, extent.logicalBlock() + extent.length());
        }
        return blocks * (long) blockSize;
    }

    /**
     * Changes a file's recorded length, within the blocks it already owns.
     *
     * <p>Only {@code i_size} moves. The file's blocks, the block bitmaps, the group
     * free counts and the directory entry are all unchanged, because no space is
     * being allocated or released — the bytes were already the file's, just beyond
     * its end. The inode's checksum is recomputed, since a modern ext4 would
     * otherwise treat the inode as corrupt.
     */
    private void setSize(Ext4Inode inode, long newSize) throws IOException {
        long capacity = allocatedBytes(inode);
        if (newSize > capacity) {
            throw new Ext4Exception("A file can only be resized within the blocks it already "
                    + "has: " + capacity + " bytes here, but " + newSize + " were needed");
        }
        long offset = inodeOffset(inode.number());
        byte[] raw = new byte[inodeSize];
        device.readInto(offset, raw, 0, inodeSize);

        ByteBuffer buffer = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04, (int) (newSize & 0xFFFFFFFFL));
        buffer.putInt(0x6C, (int) (newSize >>> 32));

        if (metadataChecksums) {
            Ext4Checksum.applyInodeChecksum(raw, inodeSize, inode.number(), checksumSeed);
        }
        device.write(offset, raw, 0, inodeSize);
    }

    /**
     * Replaces a file's contents with exactly the same number of bytes.
     *
     * <p>This is deliberately the only write there is. Because the length does not
     * change, the file keeps its existing blocks and its inode is untouched: no block
     * allocation, no bitmap or free-count updates, no directory change, and — since
     * ext4 checksums metadata but never file data — no checksum to recompute. The
     * write lands purely in data blocks the file already owns, so a filesystem that
     * was consistent before stays consistent after.
     *
     * <p>Anything that would change the file's length is refused rather than attempted;
     * growing a file needs allocation and checksummed metadata updates, which is not
     * worth the risk to a card holding a deployment's only copy of its data.
     *
     * @throws Ext4Exception if the length differs, or the file is sparse
     */
    public void overwriteInPlace(Ext4Inode inode, byte[] content) throws IOException {
        if (!isWritable()) {
            throw new Ext4Exception("This filesystem was opened read-only");
        }
        String blocked = journalBlockedReason();
        if (!blocked.isEmpty()) throw new Ext4Exception(blocked);
        if (!inode.isRegularFile()) {
            throw new Ext4Exception("Inode " + inode.number() + " is not a regular file");
        }
        if (inode.hasInlineData()) {
            throw new Ext4Exception("Files with inline data cannot be rewritten");
        }
        long capacity = allocatedBytes(inode);
        if (content.length > capacity) {
            throw new Ext4Exception("The file has " + capacity + " bytes of space on the card "
                    + "but " + content.length + " were supplied. Growing beyond a file's own "
                    + "blocks needs space to be allocated, which is not done here.");
        }

        long blocks = (content.length + blockSize - 1) / blockSize;
        // Check every block is really allocated before writing any of them, so a
        // sparse file cannot be left half-updated.
        for (long index = 0; index < blocks; index++) {
            if (physicalBlock(inode, index) == 0) {
                throw new Ext4Exception("The file has a hole at block " + index
                        + " and cannot be rewritten in place");
            }
        }

        byte[] block = new byte[blockSize];
        for (long index = 0; index < blocks; index++) {
            int from = (int) (index * blockSize);
            int length = Math.min(blockSize, content.length - from);

            if (length < blockSize) {
                // Keep whatever already follows the file inside its last block.
                readFileBlock(inode, index, block);
            }
            System.arraycopy(content, from, block, 0, length);
            device.write(physicalBlock(inode, index) * (long) blockSize, block, 0, blockSize);
        }

        // The data is on the card before the inode says how much of it counts, so a
        // failure between the two leaves the file readable at its old length rather
        // than claiming bytes that were never written.
        if (content.length != inode.size()) {
            setSize(inode, content.length);
        }
        device.flush();
    }

    // ---------------------------------------------------------------- deleting

    /**
     * Why files cannot be deleted from this card, for the user, or "" when they can.
     *
     * <p>Deleting is a far bigger change than {@link #overwriteInPlace}: directory
     * entries, block and inode bitmaps, group free counts and the superblock all move.
     * Each of the features refused here would need that bookkeeping done differently,
     * and none of them appears on a Raspberry Pi card, so the honest answer is to say
     * no rather than to guess.
     */
    public String deleteBlockedReason() {
        if (!isWritable()) return "This card was opened for reading only.";
        if (!isClean() || (incompatibleFeatures & INCOMPAT_NEEDS_RECOVERY) != 0) {
            return "This card was not shut down cleanly, so changes to it are still sitting in "
                    + "its journal rather than in the places they belong. WhalePi Host reads "
                    + "through the journal so that the newest recordings download correctly, "
                    + "but removing files from underneath one would corrupt the card.\n\n"
                    + "Put the card back in the WhalePi and shut it down properly, or mount it "
                    + "once on a Linux machine, then try again. Reading and downloading still "
                    + "work in the meantime.";
        }
        if ((readOnlyFeatures & RO_COMPAT_BIGALLOC) != 0) {
            return "This filesystem tracks space in clusters (bigalloc), which WhalePi Host "
                    + "cannot free correctly.";
        }
        if ((incompatibleFeatures & INCOMPAT_META_BG) != 0) {
            return "This filesystem scatters its group descriptors (meta_bg), which WhalePi "
                    + "Host cannot update.";
        }
        if ((readOnlyFeatures & RO_COMPAT_QUOTA) != 0) {
            return "This filesystem keeps quota accounting, which WhalePi Host cannot keep "
                    + "up to date when files are removed.";
        }
        return "";
    }

    /**
     * Removes {@code name} from {@code parent}, together with everything beneath it.
     *
     * @return what was freed, and anything that had to be skipped
     */
    public Ext4Deleter.Result delete(Ext4Inode parent, String name, Ext4Deleter.Progress progress)
            throws IOException {
        String blocked = deleteBlockedReason();
        if (!blocked.isEmpty()) throw new Ext4Exception(blocked);
        return new Ext4Deleter(this, progress).delete(parent, name);
    }

    // -------------------------------------------------------------- reading blocks

    /**
     * Reads bytes of the filesystem, taking any block from the journal when the journal
     * holds a newer committed copy of it.
     *
     * <p>Every read that is trying to find out what is <em>on</em> the card goes through
     * here. The write paths deliberately do not: they read a block in order to hand it
     * back changed, and while the journal has anything outstanding writing is refused
     * anyway.
     */
    private void readInto(long offset, byte[] target, int targetOffset, int length)
            throws IOException {
        if (journal == null || journal.blockCount() == 0) {
            device.readInto(offset, target, targetOffset, length);
            return;
        }
        int done = 0;
        while (done < length) {
            long position = offset + done;
            long block = Math.floorDiv(position, blockSize);
            int within = (int) (position - block * (long) blockSize);
            int take = Math.min(blockSize - within, length - done);

            if (journal.holds(block)) {
                System.arraycopy(journal.block(block), within, target, targetOffset + done, take);
            } else {
                device.readInto(position, target, targetOffset + done, take);
            }
            done += take;
        }
    }

    /** As {@link #readInto}, into a fresh little-endian buffer. */
    private ByteBuffer read(long offset, int length) throws IOException {
        byte[] out = new byte[length];
        readInto(offset, out, 0, length);
        return ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    }

    /** How many blocks are being read from the journal rather than from the card. */
    public int journalledBlocks() {
        return journal == null ? 0 : journal.blockCount();
    }

    /** How many finished transactions the journal held. */
    public int journalledTransactions() {
        return journal == null ? 0 : journal.transactionCount();
    }

    /**
     * Why the journal of an unclean card could not be used, or "" when there was no
     * journal to read or nothing stood in the way of reading it.
     */
    public String journalDeclinedReason() {
        return journal == null ? "" : journal.declinedReason();
    }

    /**
     * Why a card being read through its journal cannot be written to, or "" when it can.
     *
     * <p>Reading such a card is exactly right: the journal holds the newest version of
     * every block it names, and that is what gets served. Writing to it is not. The
     * blocks being read are not the blocks that would be written, so a change would land
     * underneath the journal and be undone the moment a Linux machine replayed it —
     * or worse, land in a block the journal is about to overwrite with something else.
     */
    public String journalBlockedReason() {
        if (journalledBlocks() == 0) return "";
        return "This card was not shut down cleanly, and WhalePi Host is reading it through the "
                + "changes still waiting in its journal. Those changes have not reached the "
                + "places they belong, so writing to the card now would write underneath them.\n\n"
                + "Downloading is not affected. To change the card, put it back in the WhalePi "
                + "and shut it down properly, or mount it once on a Linux machine, then try "
                + "again.";
    }

    // -------------------------------------------------- internals for the deleter

    BlockDevice device() { return device; }

    long blocksPerGroup() { return blocksPerGroup; }

    long inodesPerGroup() { return inodesPerGroup; }

    long firstDataBlock() { return firstDataBlock; }

    long blockCount() { return blockCount; }

    long inodeCount() { return inodeCount; }

    long groupCount() { return groupCount; }

    int inodeSize() { return inodeSize; }

    boolean metadataChecksums() { return metadataChecksums; }

    boolean groupDescriptorChecksums() { return groupDescriptorChecksums; }

    int checksumSeed() { return checksumSeed; }

    byte[] uuid() { return uuid; }

    /** Byte offset of a group's descriptor within the group descriptor table. */
    long groupDescriptorOffset(long group) {
        return (firstDataBlock + 1) * (long) blockSize + group * descriptorSize;
    }

    int descriptorSize() { return descriptorSize; }

    /** The whole superblock, ready to be modified and handed back to {@link #writeSuperblock}. */
    byte[] readSuperblock() throws IOException {
        byte[] raw = new byte[1024];
        device.readInto(SUPERBLOCK_OFFSET, raw, 0, 1024);
        return raw;
    }

    void writeSuperblock(byte[] raw) throws IOException {
        if (metadataChecksums) Ext4Checksum.applySuperblockChecksum(raw);
        device.write(SUPERBLOCK_OFFSET, raw, 0, 1024);
    }

    byte[] readRawInode(long number) throws IOException {
        byte[] raw = new byte[inodeSize];
        device.readInto(inodeOffset(number), raw, 0, inodeSize);
        return raw;
    }

    /** Writes an inode back, recomputing the checksum a modern ext4 checks it against. */
    void writeRawInode(long number, byte[] raw) throws IOException {
        if (metadataChecksums) {
            Ext4Checksum.applyInodeChecksum(raw, inodeSize, number, checksumSeed);
        }
        device.write(inodeOffset(number), raw, 0, inodeSize);
    }

    /** Device block holding a given block of a file, or 0 for a hole. */
    long blockOf(Ext4Inode inode, long logicalBlock) throws IOException {
        return physicalBlock(inode, logicalBlock);
    }

    /** Reads one node of an extent tree from the device. */
    ByteBuffer readBlock(long block) throws IOException {
        return device.read(block * (long) blockSize, blockSize);
    }

    static int extentMagic() { return EXTENT_MAGIC; }

    // ------------------------------------------------------------ extent mapping

    /**
     * Walks an extent tree, depth first.
     *
     * <p>A node is a 12-byte header followed by entries: leaf extents at depth 0, and
     * index entries above that, each pointing at a block holding another node.
     */
    private List<Extent> readExtentTree(ByteBuffer node, int recursionDepth) throws IOException {
        if (recursionDepth > 8) {
            throw new Ext4Exception("Extent tree is unreasonably deep — corrupt filesystem");
        }
        int magic = node.getShort(0) & 0xFFFF;
        if (magic != EXTENT_MAGIC) {
            throw new Ext4Exception("Bad extent header magic 0x" + Integer.toHexString(magic));
        }
        int entries = node.getShort(2) & 0xFFFF;
        int depth = node.getShort(6) & 0xFFFF;

        List<Extent> result = new ArrayList<>();
        for (int i = 0; i < entries; i++) {
            int at = 12 + i * 12;
            if (at + 12 > node.limit()) break;

            if (depth == 0) {
                long logical = node.getInt(at) & 0xFFFFFFFFL;
                int length = node.getShort(at + 4) & 0xFFFF;
                long physical = (node.getInt(at + 8) & 0xFFFFFFFFL)
                        | ((long) (node.getShort(at + 6) & 0xFFFF) << 32);
                // Lengths above 32768 mark an uninitialised extent: allocated but
                // never written, so it reads as zeros.
                if (length > 32768) {
                    result.add(new Extent(logical, 0, length - 32768));
                } else if (length > 0) {
                    result.add(new Extent(logical, physical, length));
                }
            } else {
                long child = (node.getInt(at + 4) & 0xFFFFFFFFL)
                        | ((long) (node.getShort(at + 8) & 0xFFFF) << 32);
                ByteBuffer childNode = read(child * (long) blockSize, blockSize);
                result.addAll(readExtentTree(childNode, recursionDepth + 1));
            }
        }
        return result;
    }

    // ---------------------------------------------------------- indirect mapping

    /**
     * Builds the block map of an older ext2/ext3 file from its indirect block chain:
     * twelve direct pointers, then single, double and triple indirect blocks.
     *
     * <p>Raspberry Pi OS has used extents for many years, so this exists only so that
     * an unusual card still reads rather than failing.
     */
    private List<Extent> readIndirectBlocks(Ext4Inode inode) throws IOException {
        int pointersPerBlock = blockSize / 4;
        long totalBlocks = (inode.size() + blockSize - 1) / blockSize;
        if (totalBlocks == 0) return Collections.emptyList();

        ByteBuffer direct = ByteBuffer.wrap(inode.blockData())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        List<Long> blocks = new ArrayList<>();

        for (int i = 0; i < 12 && blocks.size() < totalBlocks; i++) {
            blocks.add(direct.getInt(i * 4) & 0xFFFFFFFFL);
        }
        if (blocks.size() < totalBlocks) {
            collectIndirect(direct.getInt(12 * 4) & 0xFFFFFFFFL, 1, blocks, totalBlocks,
                    pointersPerBlock);
        }
        if (blocks.size() < totalBlocks) {
            collectIndirect(direct.getInt(13 * 4) & 0xFFFFFFFFL, 2, blocks, totalBlocks,
                    pointersPerBlock);
        }
        if (blocks.size() < totalBlocks) {
            collectIndirect(direct.getInt(14 * 4) & 0xFFFFFFFFL, 3, blocks, totalBlocks,
                    pointersPerBlock);
        }
        return coalesce(blocks);
    }

    private void collectIndirect(long block, int level, List<Long> out, long wanted,
                                 int pointersPerBlock) throws IOException {
        if (out.size() >= wanted) return;
        if (block == 0) {
            // A hole: the whole subtree is unwritten.
            long span = 1;
            for (int i = 0; i < level; i++) span *= pointersPerBlock;
            for (long i = 0; i < span && out.size() < wanted; i++) out.add(0L);
            return;
        }
        ByteBuffer table = read(block * (long) blockSize, blockSize);
        for (int i = 0; i < pointersPerBlock && out.size() < wanted; i++) {
            long pointer = table.getInt(i * 4) & 0xFFFFFFFFL;
            if (level == 1) {
                out.add(pointer);
            } else {
                collectIndirect(pointer, level - 1, out, wanted, pointersPerBlock);
            }
        }
    }

    /** Turns a flat list of block pointers into runs. */
    private static List<Extent> coalesce(List<Long> blocks) {
        List<Extent> extents = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            long physical = blocks.get(i);
            if (physical == 0) { // hole
                i++;
                continue;
            }
            int run = 1;
            while (i + run < blocks.size() && blocks.get(i + run) == physical + run) run++;
            extents.add(new Extent(i, physical, run));
            i += run;
        }
        return extents;
    }

    // ------------------------------------------------------------------- helpers

    private static String readName(ByteBuffer buffer, int offset, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = buffer.get(offset + i);
        int end = 0;
        while (end < length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        device.close();
    }
}
