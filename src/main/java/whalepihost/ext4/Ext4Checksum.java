package whalepihost.ext4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The CRC32C checksums a modern ext4 keeps over its metadata.
 *
 * <p>With the {@code metadata_csum} feature every inode carries a checksum, and the
 * kernel reports an inode whose checksum does not match as corrupt. So an inode may
 * only be modified if its checksum is recomputed exactly the way Linux does it.
 *
 * <p>The inode checksum follows {@code ext4_inode_csum} in {@code fs/ext4/inode.c}: a
 * running CRC32C seeded from the filesystem, then the inode number, its generation, and
 * the inode itself with the checksum fields taken as zero. Deleting a file touches four
 * more kinds of metadata — the block and inode bitmaps, the group descriptor, the
 * directory block the entry lived in, and the superblock — each with its own recipe,
 * all of them here.
 *
 * <p>Older filesystems checksum their group descriptors with CRC-16 instead
 * ({@code uninit_bg}), so that one is here too.
 */
final class Ext4Checksum {

    /** Castagnoli polynomial, reflected — the one ext4 uses. */
    private static final int POLYNOMIAL = 0x82F63B78;

    private static final int[] TABLE = buildTable();

    /** Byte offset of {@code i_checksum_lo} within an inode. */
    private static final int CHECKSUM_LO = 0x7C;
    /** Byte offset of {@code i_checksum_hi}, in the extended part of a large inode. */
    private static final int CHECKSUM_HI = 0x82;
    /** Size of the original ext2 inode; anything beyond this is the extended part. */
    private static final int GOOD_OLD_INODE_SIZE = 128;
    /** Byte offset of {@code bg_checksum} within a group descriptor. */
    private static final int GROUP_CHECKSUM = 0x1E;
    /** Byte offset of {@code s_checksum} within the superblock. */
    private static final int SUPERBLOCK_CHECKSUM = 0x3FC;

    /** CRC-16/ARC, reflected — what an older {@code uninit_bg} filesystem uses instead. */
    private static final int[] CRC16_TABLE = buildCrc16Table();

    private Ext4Checksum() { }

    private static int[] buildCrc16Table() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
            table[i] = crc;
        }
        return table;
    }

    private static int[] buildTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLYNOMIAL : crc >>> 1;
            }
            table[i] = crc;
        }
        return table;
    }

    /**
     * CRC32C in the form the kernel uses: {@code crc} is the running value and is
     * returned as-is, with no inversion at either end.
     */
    static int crc32c(int crc, byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            crc = (crc >>> 8) ^ TABLE[(crc ^ data[offset + i]) & 0xFF];
        }
        return crc;
    }

    static int crc32c(int crc, byte[] data) {
        return crc32c(crc, data, 0, data.length);
    }

    private static byte[] le32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    /**
     * Writes the correct checksum into an inode's raw bytes.
     *
     * @param raw          the whole inode, {@code inodeSize} bytes, already carrying the
     *                     new field values
     * @param inodeSize    bytes per inode for this filesystem
     * @param inodeNumber  which inode this is
     * @param seed         the filesystem's checksum seed
     */
    static void applyInodeChecksum(byte[] raw, int inodeSize, long inodeNumber, int seed) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        int extraSize = inodeSize > GOOD_OLD_INODE_SIZE ? buffer.getShort(0x80) & 0xFFFF : 0;
        // i_checksum_hi only exists when the extended part reaches far enough to hold it.
        boolean hasHigh = inodeSize > GOOD_OLD_INODE_SIZE
                && extraSize >= (CHECKSUM_HI + 2 - GOOD_OLD_INODE_SIZE);

        // The checksum is computed as though its own fields were zero. Hashing
        // straight through with them zeroed is equivalent to the kernel's version,
        // which hashes the surrounding segments and substitutes zeros in between.
        buffer.putShort(CHECKSUM_LO, (short) 0);
        if (hasHigh) buffer.putShort(CHECKSUM_HI, (short) 0);

        int generation = buffer.getInt(0x64);

        int crc = crc32c(seed, le32((int) inodeNumber));
        crc = crc32c(crc, le32(generation));
        crc = crc32c(crc, raw, 0, GOOD_OLD_INODE_SIZE);
        if (inodeSize > GOOD_OLD_INODE_SIZE) {
            crc = crc32c(crc, raw, GOOD_OLD_INODE_SIZE, inodeSize - GOOD_OLD_INODE_SIZE);
        }

        buffer.putShort(CHECKSUM_LO, (short) (crc & 0xFFFF));
        if (hasHigh) buffer.putShort(CHECKSUM_HI, (short) ((crc >>> 16) & 0xFFFF));
    }


    static int crc16(int crc, byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            crc = ((crc >>> 8) ^ CRC16_TABLE[(crc ^ data[offset + i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * The per-inode seed everything belonging to one inode is checksummed from:
     * the filesystem seed, then the inode number and its generation.
     *
     * <p>This is {@code ei->i_csum_seed}, and it is what a directory block's checksum
     * is computed over — which is why a directory block cannot be checksummed without
     * knowing which inode it belongs to.
     */
    static int inodeSeed(int filesystemSeed, long inodeNumber, int generation) {
        int crc = crc32c(filesystemSeed, le32((int) inodeNumber));
        return crc32c(crc, le32(generation));
    }

    /**
     * Checksums a directory block, following {@code ext4_dirblock_csum_set}.
     *
     * <p>A checksummed directory block ends in a 12-byte fake entry — inode 0, record
     * length 12, file type 0xDE — whose last four bytes hold the CRC32C of everything
     * before it. Blocks without that tail (an older filesystem, or an htree interior
     * node) are left alone.
     */
    static void applyDirectoryBlockChecksum(byte[] block, int blockSize, int inodeSeed) {
        int tail = blockSize - 12;
        if (tail < 0) return;

        ByteBuffer buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        boolean looksLikeTail = buffer.getInt(tail) == 0
                && (buffer.getShort(tail + 4) & 0xFFFF) == 12
                && block[tail + 6] == 0
                && (block[tail + 7] & 0xFF) == 0xDE;
        if (!looksLikeTail) return;

        buffer.putInt(tail + 8, crc32c(inodeSeed, block, 0, tail));
    }

    /**
     * Checksums a block or inode bitmap, following {@code ext4_block_bitmap_csum_set}:
     * a CRC32C over the bits that are actually in use, split across the descriptor's
     * low and high halves.
     *
     * @param bitmap    the whole bitmap block
     * @param usedBytes bytes of it the group actually uses
     */
    static int bitmapChecksum(byte[] bitmap, int usedBytes, int filesystemSeed) {
        return crc32c(filesystemSeed, bitmap, 0, usedBytes);
    }

    /**
     * Writes the correct checksum into a group descriptor's raw bytes, following
     * {@code ext4_group_desc_csum}.
     *
     * @param metadataChecksums true for a {@code metadata_csum} filesystem (CRC32C),
     *                          false for an older {@code uninit_bg} one (CRC-16)
     * @param uuid              the filesystem UUID, needed only by the CRC-16 form
     */
    static void applyGroupDescriptorChecksum(byte[] descriptor, int descriptorSize, long group,
                                             boolean metadataChecksums, int filesystemSeed,
                                             byte[] uuid) {
        ByteBuffer buffer = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(GROUP_CHECKSUM, (short) 0);

        byte[] groupNumber = le32((int) group);
        int after = GROUP_CHECKSUM + 2;
        int checksum;

        if (metadataChecksums) {
            int crc = crc32c(filesystemSeed, groupNumber);
            crc = crc32c(crc, descriptor, 0, GROUP_CHECKSUM);
            crc = crc32c(crc, new byte[2]); // the checksum field itself, as zeros
            if (after < descriptorSize) {
                crc = crc32c(crc, descriptor, after, descriptorSize - after);
            }
            checksum = crc & 0xFFFF;
        } else {
            int crc = crc16(0xFFFF, uuid, 0, uuid.length);
            crc = crc16(crc, groupNumber, 0, groupNumber.length);
            crc = crc16(crc, descriptor, 0, GROUP_CHECKSUM);
            if (after < descriptorSize) {
                crc = crc16(crc, descriptor, after, descriptorSize - after);
            }
            checksum = crc & 0xFFFF;
        }
        buffer.putShort(GROUP_CHECKSUM, (short) checksum);
    }

    /**
     * Writes the correct checksum into the superblock's raw bytes, following
     * {@code ext4_superblock_csum}: a CRC32C over everything up to the checksum field,
     * seeded from all-ones rather than from the filesystem's own seed.
     */
    static void applySuperblockChecksum(byte[] superblock) {
        ByteBuffer buffer = ByteBuffer.wrap(superblock).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(SUPERBLOCK_CHECKSUM, crc32c(~0, superblock, 0, SUPERBLOCK_CHECKSUM));
    }
}
