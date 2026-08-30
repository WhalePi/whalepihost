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
 * <p>This follows {@code ext4_inode_csum} in {@code fs/ext4/inode.c}: a running CRC32C
 * seeded from the filesystem, then the inode number, its generation, and the inode
 * itself with the checksum fields taken as zero.
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

    private Ext4Checksum() { }

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
}
