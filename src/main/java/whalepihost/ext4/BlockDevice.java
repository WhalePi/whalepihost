package whalepihost.ext4;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 *
 * <p>Offsets passed in are relative to a {@link DeviceRegion}, not to the device. On
 * macOS and Linux the region is the whole partition device and the two are the same
 * thing; on Windows the region is a partition inside {@code \\.\PhysicalDriveN}, so
 * every read is shifted by the partition's offset and stops at its end. Since a
 * partition starts on a sector boundary, shifted reads stay aligned.
 */
public class BlockDevice implements Closeable {

    /** Chunk size for device reads. A multiple of every plausible sector size. */
    private static final int CHUNK = 64 * 1024;

    /** Roughly 8 MB of cached chunks: enough for the metadata of a large tree. */
    private static final int MAX_CACHED_CHUNKS = 128;

    private final Closeable handle;
    private final FileChannel channel;
    private final String name;
    private final long base;
    private final long size;
    private final boolean writable;

    private final Map<Long, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_CACHED_CHUNKS;
        }
    };

    public BlockDevice(Path path) throws IOException {
        this(DeviceRegion.whole(path), false);
    }

    /**
     * @param forWriting open read-write. Raw devices are usually {@code root:rw},
     *                   so this fails for an ordinary account even when reading works.
     */
    public BlockDevice(Path path, boolean forWriting) throws IOException {
        this(DeviceRegion.whole(path), forWriting);
    }

    /**
     * Opens one region of a device or image: a partition inside a whole disk, or the
     * whole of a partition device.
     *
     * @param forWriting open read-write. Raw devices are usually {@code root:rw},
     *                   so this fails for an ordinary account even when reading works.
     */
    public BlockDevice(DeviceRegion region, boolean forWriting) throws IOException {
        this.name = region.toString();
        this.base = region.offset();
        this.writable = forWriting;

        Opened opened = open(region.path(), forWriting);
        this.handle = opened.handle();
        this.channel = opened.channel();

        if (region.length() > 0) {
            // A partition table already said how long the region is, and asking the
            // device would mean probing a disk we are only allowed a part of.
            this.size = region.length();
        } else {
            long length = channel.size();
            if (length <= 0) {
                // Character devices report zero length; fall back to what the OS knows.
                length = probeSize();
            }
            this.size = Math.max(0, length - base);
        }
    }

    /** Device or image this is reading. */
    public String name() {
        return name;
    }

    public boolean isWritable() {
        return writable;
    }

    /** Bytes in this region, or 0 if the device would not say how long it is. */
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
            int n = channel.write(buffer, base + chunkStart + written);
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

        // A region must not be read past its end: the next partition lies there.
        int wanted = CHUNK;
        if (size > 0) {
            long remaining = size - chunkStart;
            if (remaining <= 0) {
                throw new EOFException("Nothing to read at offset " + chunkStart + " of " + name);
            }
            if (remaining < CHUNK) wanted = (int) remaining;
        }

        ByteBuffer buffer = ByteBuffer.allocate(wanted);
        int read = 0;
        while (read < wanted) {
            int n = channel.read(buffer, base + chunkStart + read);
            if (n <= 0) break;
            read += n;
        }
        if (read <= 0) {
            throw new EOFException("Nothing to read at offset " + chunkStart + " of " + name);
        }
        byte[] chunk = read == wanted ? buffer.array() : java.util.Arrays.copyOf(buffer.array(), read);
        cache.put(chunkStart, chunk);
        return chunk;
    }

    /** Binary search for the end of a device that reports no length, in device bytes. */
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
        handle.close();
    }

    /** An open device: the channel to work through, and the thing to close. */
    private record Opened(FileChannel channel, Closeable handle) { }

    /**
     * Opens a device for reading, or for reading and writing.
     *
     * <p>The two are opened differently, and on Windows they have to be.
     * {@link RandomAccessFile} is what reads a raw device everywhere, but its
     * {@code "rw"} mode asks the system to create the file if it is missing — which
     * reaches Windows as {@code OPEN_ALWAYS}, and {@code CreateFile} refuses that for a
     * physical drive: a disk must be opened {@code OPEN_EXISTING}. So every write open
     * failed, whether or not the program was elevated, and the card came up read-only
     * with nothing but a "run as administrator" message to show for it.
     *
     * <p>A channel opened with {@code READ} and {@code WRITE} and nothing else asks for
     * {@code OPEN_EXISTING}, which Windows accepts. If it is refused anyway the old way
     * is still tried, since on macOS and Linux opening a device node read-write is
     * perfectly ordinary — there the only thing that ever refuses is permission, and
     * then both ways fail alike.
     */
    private static Opened open(Path path, boolean forWriting) throws IOException {
        if (!forWriting) {
            RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
            return new Opened(file.getChannel(), file);
        }
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            return new Opened(channel, channel);
        } catch (IOException | RuntimeException e) {
            RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
            return new Opened(file.getChannel(), file);
        }
    }
}
