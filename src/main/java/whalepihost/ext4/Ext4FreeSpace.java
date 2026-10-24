package whalepihost.ext4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Gives blocks and inodes back to the filesystem.
 *
 * <p>Every free touches three levels of bookkeeping: a bit in the group's bitmap, the
 * group descriptor's free counts, and the superblock's totals — each with a checksum of
 * its own on a modern ext4. Doing that one block at a time would be unbearably slow,
 * because the card underneath only accepts whole aligned chunks: deleting a day's
 * recordings would mean tens of thousands of rewrites of the same few bitmap blocks.
 *
 * <p>So changes are gathered in memory and written once per group by {@link #flush()}.
 * Nothing reaches the card until then, and the caller is expected to have removed the
 * directory entry first — so the worst an interrupted delete can leave behind is an
 * unreferenced inode, which {@code e2fsck} reclaims, rather than a live file whose
 * blocks are marked free.
 */
final class Ext4FreeSpace {

    /** Dirty groups held before writing out. Each costs two blocks of memory. */
    private static final int MAX_DIRTY_GROUPS = 128;

    private final Ext4FileSystem fs;

    private final Map<Long, byte[]> blockBitmaps = new LinkedHashMap<>();
    private final Map<Long, byte[]> inodeBitmaps = new LinkedHashMap<>();

    /** Per group: blocks freed, inodes freed, directories freed. */
    private final Map<Long, long[]> pending = new LinkedHashMap<>();

    /** Totals for the whole delete, for reporting. */
    private long freedBlocks;
    private long freedInodes;
    /** The part of those totals the superblock has not been told about yet. */
    private long unrecordedBlocks;
    private long unrecordedInodes;

    Ext4FreeSpace(Ext4FileSystem fs) {
        this.fs = fs;
    }

    long freedBlocks() { return freedBlocks; }

    long freedInodes() { return freedInodes; }

    // -------------------------------------------------------------------- freeing

    /** Frees a run of consecutive blocks, which may span more than one group. */
    void freeBlocks(long start, long count) throws IOException {
        if (count <= 0) return;
        long end = start + count;
        if (start < fs.firstDataBlock() || end > fs.blockCount()) {
            throw new Ext4Exception("Blocks " + start + "-" + (end - 1)
                    + " are outside the filesystem");
        }

        long block = start;
        while (block < end) {
            long group = (block - fs.firstDataBlock()) / fs.blocksPerGroup();
            long groupStart = fs.firstDataBlock() + group * fs.blocksPerGroup();
            byte[] bitmap = blockBitmap(group);

            int index = (int) (block - groupStart);
            int last = (int) Math.min(fs.blocksPerGroup(), end - groupStart);
            long[] counts = pending(group);
            for (; index < last; index++, block++) {
                if (clearBit(bitmap, index)) {
                    counts[0]++;
                    freedBlocks++;
                    unrecordedBlocks++;
                }
            }
        }
    }

    /** Frees one inode, which the caller has already unhooked from its directory. */
    void freeInode(long number, boolean directory) throws IOException {
        if (number < 1 || number > fs.inodeCount()) {
            throw new Ext4Exception("Inode " + number + " is outside the filesystem");
        }
        long group = (number - 1) / fs.inodesPerGroup();
        int index = (int) ((number - 1) % fs.inodesPerGroup());

        if (clearBit(inodeBitmap(group), index)) {
            long[] counts = pending(group);
            counts[1]++;
            if (directory) counts[2]++;
            freedInodes++;
            unrecordedInodes++;
        }
    }

    /** @return true if the bit was set, so this call is what freed it */
    private static boolean clearBit(byte[] bitmap, int index) {
        int mask = 1 << (index & 7);
        if ((bitmap[index >> 3] & mask) == 0) return false;
        bitmap[index >> 3] &= (byte) ~mask;
        return true;
    }

    // -------------------------------------------------------------------- bitmaps

    private byte[] blockBitmap(long group) throws IOException {
        byte[] cached = blockBitmaps.get(group);
        if (cached != null) return cached;

        spillIfCrowded();
        byte[] descriptor = descriptor(group);
        if ((flags(descriptor) & Ext4FileSystem.BG_BLOCK_UNINIT) != 0) {
            throw new Ext4Exception("Group " + group + " claims to have no blocks in use, "
                    + "but a file says otherwise — the filesystem is inconsistent");
        }
        byte[] bitmap = readBlock(blockBitmapBlock(descriptor));
        blockBitmaps.put(group, bitmap);
        return bitmap;
    }

    private byte[] inodeBitmap(long group) throws IOException {
        byte[] cached = inodeBitmaps.get(group);
        if (cached != null) return cached;

        spillIfCrowded();
        byte[] descriptor = descriptor(group);
        if ((flags(descriptor) & Ext4FileSystem.BG_INODE_UNINIT) != 0) {
            throw new Ext4Exception("Group " + group + " claims to have no inodes in use, "
                    + "but a file says otherwise — the filesystem is inconsistent");
        }
        byte[] bitmap = readBlock(inodeBitmapBlock(descriptor));
        inodeBitmaps.put(group, bitmap);
        return bitmap;
    }

    private void spillIfCrowded() throws IOException {
        if (blockBitmaps.size() + inodeBitmaps.size() >= MAX_DIRTY_GROUPS) flush();
    }

    private byte[] readBlock(long block) throws IOException {
        byte[] data = new byte[fs.blockSize()];
        fs.device().readInto(block * (long) fs.blockSize(), data, 0, data.length);
        return data;
    }

    // -------------------------------------------------------------------- writing

    /**
     * Writes every pending change out: bitmaps first, then the group descriptors that
     * count what is in them, then the superblock's totals.
     */
    void flush() throws IOException {
        if (blockBitmaps.isEmpty() && inodeBitmaps.isEmpty() && pending.isEmpty()) return;

        // A group may be dirty in any of the three maps; do each one exactly once.
        for (long group : new TreeSet<>(union(blockBitmaps.keySet(), inodeBitmaps.keySet(),
                pending.keySet()))) {
            writeGroup(group);
        }
        blockBitmaps.clear();
        inodeBitmaps.clear();
        pending.clear();

        updateSuperblock();
        fs.device().flush();
    }

    private void writeGroup(long group) throws IOException {
        byte[] descriptor = descriptor(group);
        ByteBuffer fields = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);

        byte[] blockBits = blockBitmaps.get(group);
        if (blockBits != null) {
            long block = blockBitmapBlock(descriptor);
            fs.device().write(block * (long) fs.blockSize(), blockBits, 0, blockBits.length);
            if (fs.metadataChecksums()) {
                int checksum = Ext4Checksum.bitmapChecksum(blockBits,
                        (int) (fs.blocksPerGroup() / 8), fs.checksumSeed());
                fields.putShort(0x18, (short) checksum);
                if (fs.descriptorSize() >= 0x3A) {
                    fields.putShort(0x38, (short) (checksum >>> 16));
                }
            }
        }

        byte[] inodeBits = inodeBitmaps.get(group);
        if (inodeBits != null) {
            long block = inodeBitmapBlock(descriptor);
            fs.device().write(block * (long) fs.blockSize(), inodeBits, 0, inodeBits.length);
            if (fs.metadataChecksums()) {
                int checksum = Ext4Checksum.bitmapChecksum(inodeBits,
                        (int) (fs.inodesPerGroup() / 8), fs.checksumSeed());
                fields.putShort(0x1A, (short) checksum);
                if (fs.descriptorSize() >= 0x3C) {
                    fields.putShort(0x3A, (short) (checksum >>> 16));
                }
            }
        }

        long[] counts = pending.get(group);
        if (counts != null) {
            addCount(fields, 0x0C, 0x2C, counts[0]);   // free blocks
            addCount(fields, 0x0E, 0x2E, counts[1]);   // free inodes
            addCount(fields, 0x10, 0x30, -counts[2]);  // directories
        }

        if (fs.metadataChecksums() || fs.groupDescriptorChecksums()) {
            Ext4Checksum.applyGroupDescriptorChecksum(descriptor, fs.descriptorSize(), group,
                    fs.metadataChecksums(), fs.checksumSeed(), fs.uuid());
        }
        fs.device().write(fs.groupDescriptorOffset(group), descriptor, 0, descriptor.length);
    }

    /** Adds to one of the descriptor's counters, which is split across two 16-bit halves. */
    private void addCount(ByteBuffer fields, int low, int high, long delta) throws IOException {
        if (delta == 0) return;
        boolean wide = fs.descriptorSize() >= 64;
        long value = (fields.getShort(low) & 0xFFFFL)
                | (wide ? ((fields.getShort(high) & 0xFFFFL) << 16) : 0);
        long updated = value + delta;
        if (updated < 0 || (!wide && updated > 0xFFFF)) {
            throw new Ext4Exception("A group's free count would go out of range ("
                    + value + " + " + delta + ") — refusing to write it");
        }
        fields.putShort(low, (short) updated);
        if (wide) fields.putShort(high, (short) (updated >>> 16));
    }

    private void updateSuperblock() throws IOException {
        if (unrecordedBlocks == 0 && unrecordedInodes == 0) return;

        byte[] raw = fs.readSuperblock();
        ByteBuffer sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        long free = (sb.getInt(0x0C) & 0xFFFFFFFFL) | ((sb.getInt(0x158) & 0xFFFFFFFFL) << 32);
        free += unrecordedBlocks;
        sb.putInt(0x0C, (int) free);
        sb.putInt(0x158, (int) (free >>> 32));

        long freeInodes = (sb.getInt(0x10) & 0xFFFFFFFFL) + unrecordedInodes;
        sb.putInt(0x10, (int) freeInodes);

        fs.writeSuperblock(raw);

        // The totals are now on the card, so a second flush must not add them again.
        unrecordedBlocks = 0;
        unrecordedInodes = 0;
    }

    // -------------------------------------------------------- group descriptor bits

    private byte[] descriptor(long group) throws IOException {
        if (group < 0 || group >= fs.groupCount()) {
            throw new Ext4Exception("Block group " + group + " is outside the filesystem");
        }
        byte[] raw = new byte[fs.descriptorSize()];
        fs.device().readInto(fs.groupDescriptorOffset(group), raw, 0, raw.length);
        return raw;
    }

    private long blockBitmapBlock(byte[] descriptor) {
        return blockNumber(descriptor, 0x00, 0x20);
    }

    private long inodeBitmapBlock(byte[] descriptor) {
        return blockNumber(descriptor, 0x04, 0x24);
    }

    private long blockNumber(byte[] descriptor, int low, int high) {
        ByteBuffer fields = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
        long value = fields.getInt(low) & 0xFFFFFFFFL;
        if (fs.descriptorSize() >= 64) value |= (fields.getInt(high) & 0xFFFFFFFFL) << 32;
        return value;
    }

    private static int flags(byte[] descriptor) {
        return ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN).getShort(0x12) & 0xFFFF;
    }

    @SafeVarargs
    private static List<Long> union(Iterable<Long>... sets) {
        List<Long> all = new ArrayList<>();
        for (Iterable<Long> set : sets) {
            for (Long value : set) all.add(value);
        }
        return all;
    }

    private long[] pending(long group) {
        return pending.computeIfAbsent(group, g -> new long[3]);
    }
}
