package whalepihost.ext4;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Random-access reads over a raw disk partition or a disk image file.
 *
 * <p>Raw character devices ({@code /dev/rdisk6s2} on macOS, {@code \\.\PhysicalDrive1}
 * on Windows) only accept reads that are a whole number of sectors, starting on a
 * sector boundary. Every read here is therefore served out of sector-aligned chunks,
 * which are cached — ext4 metadata is read in small scattered pieces, and without a
 * cache each inode lookup would cost a fresh device read.
 */
public class BlockDevice implements Closeable {

    /** Chunk size for device reads. A multiple of every plausible sector size. */
    private static final int CHUNK = 64 * 1024;

    /** Roughly 8 MB of cached chunks: enough for the metadata of a large tree. */
    private static final int MAX_CACHED_CHUNKS = 128;

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final String name;
    private final long size;
    private final boolean writable;

    private final Map<Long, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_CACHED_CHUNKS;
        }
    };

    public BlockDevice(Path path) throws IOException {
        this(path, false);
    }

    /**
     * @param forWriting open read-write. Raw devices are usually {@code root:rw},
     *                   so this fails for an ordinary account even when reading works.
     */
    public BlockDevice(Path path, boolean forWriting) throws IOException {
        this.name = path.toString();
        this.writable = forWriting;
        this.file = new RandomAccessFile(path.toFile(), forWriting ? "rw" : "r");
        this.channel = file.getChannel();

        long length = channel.size();
        if (length <= 0) {
            // Character devices report zero length; fall back to what the OS knows.
            length = probeSize();
        }
        this.size = length;
    }

    /** Device or image this is reading. */
    public String name() {
        return name;
    }

    public boolean isWritable() {
        return writable;
    }

    /** Total bytes, or 0 if the device would not say. */
    public long size() {
        return size;
    }

    /**
     * Reads {@code length} bytes from {@code offset}, little-endian.
     *
     * @throws EOFException if the device ends before the requested bytes
     */
    public ByteBuffer read(long offset, int length) throws IOException {
        byte[] out = new byte[length];
        readInto(offset, out, 0, length);
        return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Reads into an existing array, assembling from cached aligned chunks. */
    public void readInto(long offset, byte[] target, int targetOffset, int length)
            throws IOException {
        if (offset < 0) throw new IOException("Negative read offset: " + offset);

        int done = 0;
        while (done < length) {
            long position = offset + done;
            long chunkStart = Math.floorDiv(position, CHUNK) * (long) CHUNK;
            byte[] chunk = chunk(chunkStart);

            int within = (int) (position - chunkStart);
            int available = chunk.length - within;
            if (available <= 0) {
                throw new EOFException("Read past the end of " + name + " at " + position);
            }
            int take = Math.min(available, length - done);
            System.arraycopy(chunk, within, target, targetOffset + done, take);
            done += take;
        }
    }

    /**
     * Overwrites bytes at {@code offset}.
     *
     * <p>Raw devices only accept whole sectors on sector boundaries, so this is a
     * read-modify-write of the aligned chunks the bytes land in.
     */
    public void write(long offset, byte[] source, int sourceOffset, int length)
            throws IOException {
        if (!writable) {
            throw new IOException("Opened read-only: " + name);
        }
        int done = 0;
        while (done < length) {
            long position = offset + done;
            long chunkStart = Math.floorDiv(position, CHUNK) * (long) CHUNK;
            byte[] chunk = chunk(chunkStart);

            int within = (int) (position - chunkStart);
            int take = Math.min(chunk.length - within, length - done);
            if (take <= 0) throw new EOFException("Write past the end of " + name);

            System.arraycopy(source, sourceOffset + done, chunk, within, take);
            writeChunk(chunkStart, chunk);
            done += take;
        }
    }

    private void writeChunk(long chunkStart, byte[] chunk) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(chunk);
        int written = 0;
        while (buffer.hasRemaining()) {
            int n = channel.write(buffer, chunkStart + written);
            if (n <= 0) throw new IOException("Device refused the write at " + chunkStart);
            written += n;
        }
        cache.put(chunkStart, chunk);
    }

    /** Forces everything written so far out to the device. */
    public void flush() throws IOException {
        if (writable) channel.force(false);
    }

    private byte[] chunk(long chunkStart) throws IOException {
        byte[] cached = cache.get(chunkStart);
        if (cached != null) return cached;

        ByteBuffer buffer = ByteBuffer.allocate(CHUNK);
        int read = 0;
        while (read < CHUNK) {
            int n = channel.read(buffer, chunkStart + read);
            if (n <= 0) break;
            read += n;
        }
        if (read <= 0) {
            throw new EOFException("Nothing to read at offset " + chunkStart + " of " + name);
        }
        byte[] chunk = read == CHUNK ? buffer.array() : java.util.Arrays.copyOf(buffer.array(), read);
        cache.put(chunkStart, chunk);
        return chunk;
    }

    /** Binary search for the end of a device that reports no length. */
    private long probeSize() {
        ByteBuffer one = ByteBuffer.allocate(CHUNK);
        long low = 0;
        long high = 1L << 51; // 2 PB, far beyond any SD card
        while (low < high) {
            long mid = low + (high - low) / 2;
            long probe = Math.floorDiv(mid, CHUNK) * (long) CHUNK;
            one.clear();
            try {
                if (channel.read(one, probe) > 0) {
                    low = probe + CHUNK;
                } else {
                    high = probe;
                }
            } catch (IOException e) {
                high = probe;
            }
        }
        return low;
    }

    @Override
    public void close() throws IOException {
        cache.clear();
        file.close();
    }
}
