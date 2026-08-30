package whalepihost.ext4;

import java.nio.ByteBuffer;
import java.util.List;

/** One ext4 inode: the fields a read-only reader needs, plus its raw {@code i_block}. */
public class Ext4Inode {

    private static final int MODE_MASK   = 0xF000;
    private static final int MODE_DIR    = 0x4000;
    private static final int MODE_FILE   = 0x8000;
    private static final int MODE_LINK   = 0xA000;

    /** i_flags: the file's data is mapped by an extent tree, not indirect blocks. */
    static final int FLAG_EXTENTS = 0x00080000;
    /** i_flags: the data lives inside the inode itself. */
    static final int FLAG_INLINE_DATA = 0x10000000;

    private final long number;
    private final int mode;
    private final int flags;
    private final long size;
    private final long modifiedEpochSeconds;
    private final byte[] blockData;

    /** Block map, worked out on first use and kept for the life of the handle. */
    private List<Extent> extents;

    Ext4Inode(long number, ByteBuffer raw) {
        this.number = number;
        this.mode = raw.getShort(0x00) & 0xFFFF;
        this.size = (raw.getInt(0x04) & 0xFFFFFFFFL)
                | ((raw.getInt(0x6C) & 0xFFFFFFFFL) << 32);
        this.modifiedEpochSeconds = raw.getInt(0x10) & 0xFFFFFFFFL;
        this.flags = raw.getInt(0x20);

        this.blockData = new byte[60];
        for (int i = 0; i < 60; i++) {
            blockData[i] = raw.get(0x28 + i);
        }
    }

    public long number() { return number; }

    /** File size in bytes. Only meaningful for regular files. */
    public long size() { return size; }

    public long modifiedEpochSeconds() { return modifiedEpochSeconds; }

    public boolean isDirectory() { return (mode & MODE_MASK) == MODE_DIR; }

    public boolean isRegularFile() { return (mode & MODE_MASK) == MODE_FILE; }

    public boolean isSymlink() { return (mode & MODE_MASK) == MODE_LINK; }

    boolean usesExtents() { return (flags & FLAG_EXTENTS) != 0; }

    boolean hasInlineData() { return (flags & FLAG_INLINE_DATA) != 0; }

    /** The 60-byte {@code i_block} area: an extent tree root, or indirect block pointers. */
    byte[] blockData() { return blockData; }

    List<Extent> cachedExtents() { return extents; }

    void cacheExtents(List<Extent> extents) { this.extents = extents; }
}
