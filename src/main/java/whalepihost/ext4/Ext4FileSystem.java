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
    /** s_feature_ro_compat: metadata carries CRC32C checksums. */
    private static final int RO_COMPAT_METADATA_CSUM = 0x0400;

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
    private final int checksumSeed;

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
        boolean sixtyFourBit = (incompatible & INCOMPAT_64BIT) != 0;
        int declaredDescSize = sb.getShort(0xFE) & 0xFFFF;
        this.descriptorSize = sixtyFourBit && declaredDescSize >= 64 ? declaredDescSize : 32;

        this.blockCount = (sb.getInt(0x04) & 0xFFFFFFFFL)
                | (sixtyFourBit ? ((sb.getInt(0x150) & 0xFFFFFFFFL) << 32) : 0);

        this.volumeName = readName(sb, 0x78, 16);

        this.metadataChecksums = (sb.getInt(0x64) & RO_COMPAT_METADATA_CSUM) != 0;
        if ((incompatible & INCOMPAT_CSUM_SEED) != 0) {
            this.checksumSeed = sb.getInt(0x270);
        } else {
            byte[] uuid = new byte[16];
            for (int i = 0; i < 16; i++) uuid[i] = sb.get(0x68 + i);
            this.checksumSeed = Ext4Checksum.crc32c(~0, uuid);
        }

        if (blockSize < 1024 || blockSize > 65536) {
            throw new Ext4Exception("Unsupported ext4 block size: " + blockSize);
        }
        if (inodesPerGroup == 0 || blocksPerGroup == 0) {
            throw new Ext4Exception("Corrupt ext4 superblock: empty block or inode groups");
        }
    }

    /** Opens the filesystem on a partition device or image file. */
    public static Ext4FileSystem open(Path deviceOrImage) throws IOException {
        BlockDevice device = new BlockDevice(deviceOrImage);
        try {
            return new Ext4FileSystem(device);
        } catch (IOException | RuntimeException e) {
            device.close();
            throw e;
        }
    }

    /** Quick check for an ext4 superblock, used to tell a card from any other disk. */
    public static boolean looksLikeExt4(Path deviceOrImage) {
        try (BlockDevice device = new BlockDevice(deviceOrImage)) {
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
        return new Ext4Inode(number, device.read(offset, Math.min(inodeSize, 256)));
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
        ByteBuffer descriptor = device.read(tableStart + group * descriptorSize, descriptorSize);

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
        device.readInto(physical * (long) blockSize, target, 0, blockSize);
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
        BlockDevice device = new BlockDevice(deviceOrImage, true);
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
                ByteBuffer childNode = device.read(child * (long) blockSize, blockSize);
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
        ByteBuffer table = device.read(block * (long) blockSize, blockSize);
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
