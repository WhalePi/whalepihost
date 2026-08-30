package whalepihost.ext4;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streams a file's contents out of the filesystem, a block at a time.
 *
 * <p>Recordings run to hundreds of megabytes, so nothing is ever held whole in memory.
 */
class Ext4InputStream extends InputStream {

    private final Ext4FileSystem fs;
    private final Ext4Inode inode;
    private final byte[] block;
    private final long fileSize;

    private long position;
    /** Which file block {@link #block} currently holds, or -1 when nothing is buffered. */
    private long bufferedBlock = -1;

    Ext4InputStream(Ext4FileSystem fs, Ext4Inode inode) {
        this.fs = fs;
        this.inode = inode;
        this.fileSize = inode.size();
        this.block = new byte[fs.blockSize()];
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) == 1 ? one[0] & 0xFF : -1;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (position >= fileSize) return -1;

        int blockSize = fs.blockSize();
        int done = 0;
        while (done < length && position < fileSize) {
            long wanted = position / blockSize;
            if (wanted != bufferedBlock) {
                fs.readFileBlock(inode, wanted, block);
                bufferedBlock = wanted;
            }
            int within = (int) (position % blockSize);
            int take = (int) Math.min(Math.min(blockSize - within, length - done),
                    fileSize - position);
            System.arraycopy(block, within, target, offset + done, take);
            done += take;
            position += take;
        }
        return done;
    }

    @Override
    public long skip(long count) {
        long moved = Math.min(count, fileSize - position);
        if (moved <= 0) return 0;
        position += moved;
        return moved;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, fileSize - position);
    }
}
