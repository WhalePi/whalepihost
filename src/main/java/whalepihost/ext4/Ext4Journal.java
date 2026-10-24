package whalepihost.ext4;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The card's journal, read so that a card taken out of a running WhalePi still reads
 * correctly.
 *
 * <p>ext4 does not write a change straight to where it belongs. It writes it to the
 * journal first, and only later copies it to its final place — so a card pulled out
 * mid-recording has the newest inode, directory block and bitmap sitting in the
 * journal while their proper homes still hold the previous contents. Linux does not
 * notice because mounting replays the journal before anything can read the
 * filesystem; WhalePi Host, which never mounts anything, sees the stale version. That
 * is what turned the last recording of a deployment into a folder: the inode in the
 * inode table was the slot's previous occupant, and its replacement was in the journal
 * all along.
 *
 * <p>This class replays the journal <em>in memory</em>. Nothing is written to the card:
 * it works out which blocks the journal holds a newer committed copy of, and
 * {@link Ext4FileSystem} then serves reads of those blocks from the journal instead of
 * from the block itself. The card is left exactly as it was found, and putting it back
 * in the WhalePi for a clean shutdown still does the real recovery.
 *
 * <p>Only whole, committed transactions are used. A transaction is taken as committed
 * when its commit block is present with the sequence number expected next, and — on a
 * filesystem with journal checksums — when every block of it passes its checksum. The
 * first transaction that fails any of those ends the replay, so what is served is
 * always a state the filesystem genuinely passed through.
 */
final class Ext4Journal {

    /** Every block in the journal starts with this. */
    private static final int MAGIC = 0xC03B3998;

    private static final int TYPE_DESCRIPTOR    = 1;
    private static final int TYPE_COMMIT        = 2;
    private static final int TYPE_SUPERBLOCK_V1 = 3;
    private static final int TYPE_SUPERBLOCK_V2 = 4;
    private static final int TYPE_REVOKE        = 5;

    private static final int FEATURE_REVOKE       = 0x01;
    private static final int FEATURE_64BIT        = 0x02;
    private static final int FEATURE_ASYNC_COMMIT = 0x04;
    private static final int FEATURE_CSUM_V2      = 0x08;
    private static final int FEATURE_CSUM_V3      = 0x10;
    private static final int FEATURE_FAST_COMMIT  = 0x20;

    /** t_flags: the block began with the journal's own magic, which was zeroed in the copy. */
    private static final int TAG_ESCAPE    = 0x1;
    /** t_flags: this tag carries no filesystem UUID of its own. */
    private static final int TAG_SAME_UUID = 0x2;
    /** t_flags: last tag in this descriptor block. */
    private static final int TAG_LAST      = 0x8;

    /** Journal blocks kept in hand, so repeated metadata reads do not re-read the card. */
    private static final int CACHED_BLOCKS = 64;

    private final Ext4FileSystem fs;
    private final Ext4Inode journalInode;
    private final int blockSize;

    /** Filesystem block → the block of the device holding the journal's newer copy. */
    private final Map<Long, Long> newest = new HashMap<>();
    /** Filesystem blocks whose journal copy has had its first four bytes zeroed. */
    private final Set<Long> escaped = new HashSet<>();

    private final Map<Long, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > CACHED_BLOCKS;
        }
    };

    private long first;
    private long maxlen;
    private boolean sixtyFourBit;
    private boolean checksums;
    private boolean checksumV3;
    private int checksumSeed;

    private int transactions;
    private String declined = "";

    private Ext4Journal(Ext4FileSystem fs, Ext4Inode journalInode) {
        this.fs = fs;
        this.journalInode = journalInode;
        this.blockSize = fs.blockSize();
    }

    /**
     * Reads the journal of {@code fs} and works out what it supersedes.
     *
     * <p>Never throws for a journal it cannot use: a card that will not give up its
     * journal must still open, so the reason is recorded in {@link #declinedReason()}
     * and no block is superseded.
     */
    static Ext4Journal read(Ext4FileSystem fs, Ext4Inode journalInode) {
        Ext4Journal journal = new Ext4Journal(fs, journalInode);
        try {
            journal.scan();
        } catch (IOException e) {
            journal.newest.clear();
            journal.escaped.clear();
            journal.declined = describe(e);
        }
        return journal;
    }

    /** Number of blocks whose newest committed contents are in the journal. */
    int blockCount() { return newest.size(); }

    /** Number of finished transactions replayed. */
    int transactionCount() { return transactions; }

    /** Why the journal could not be used, or "" if there was nothing in the way. */
    String declinedReason() { return declined; }

    boolean holds(long filesystemBlock) {
        return !newest.isEmpty() && newest.containsKey(filesystemBlock);
    }

    /**
     * The journal's copy of a block, {@code blockSize} bytes long.
     *
     * <p>The returned array is the cached copy; callers read from it and must not
     * change it.
     */
    byte[] block(long filesystemBlock) throws IOException {
        byte[] cached = cache.get(filesystemBlock);
        if (cached != null) return cached;

        byte[] content = new byte[blockSize];
        fs.device().readInto(newest.get(filesystemBlock) * (long) blockSize, content, 0, blockSize);
        if (escaped.contains(filesystemBlock)) {
            // The block really did start with the journal's magic; the copy in the log
            // has it zeroed so it cannot be mistaken for a journal block of its own.
            content[0] = (byte) (MAGIC >>> 24);
            content[1] = (byte) (MAGIC >>> 16);
            content[2] = (byte) (MAGIC >>> 8);
            content[3] = (byte) MAGIC;
        }
        cache.put(filesystemBlock, content);
        return content;
    }

    // ------------------------------------------------------------------ reading

    private void scan() throws IOException {
        byte[] superblock = journalBlock(0);
        if (be32(superblock, 0x00) != MAGIC) {
            declined = "its journal does not start with a journal";
            return;
        }
        int type = be32(superblock, 0x04);
        if (type != TYPE_SUPERBLOCK_V1 && type != TYPE_SUPERBLOCK_V2) {
            declined = "its journal begins with a block of the wrong kind";
            return;
        }
        int journalBlockSize = be32(superblock, 0x0C);
        if (journalBlockSize != blockSize) {
            declined = "its journal is written in " + journalBlockSize + "-byte blocks but the "
                    + "filesystem uses " + blockSize;
            return;
        }

        this.maxlen = be32(superblock, 0x10) & 0xFFFFFFFFL;
        this.first = be32(superblock, 0x14) & 0xFFFFFFFFL;
        long sequence = be32(superblock, 0x18) & 0xFFFFFFFFL;
        long start = be32(superblock, 0x1C) & 0xFFFFFFFFL;

        int features = type == TYPE_SUPERBLOCK_V2 ? be32(superblock, 0x28) : 0;
        this.sixtyFourBit = (features & FEATURE_64BIT) != 0;
        this.checksumV3 = (features & FEATURE_CSUM_V3) != 0;
        this.checksums = checksumV3 || (features & FEATURE_CSUM_V2) != 0;

        String unsupported = unsupported(features);
        if (!unsupported.isEmpty()) {
            declined = "its journal uses " + unsupported + ", which WhalePi Host cannot read";
            return;
        }
        if (first == 0 || first >= maxlen) {
            declined = "its journal describes itself as empty";
            return;
        }
        if (start == 0) {
            // A journal with no start block has nothing outstanding: everything it once
            // held has already been copied to where it belongs.
            return;
        }
        if (checksums) {
            this.checksumSeed = Ext4Checksum.crc32c(~0, superblock, 0x30, 16);
        }
        walk(start, sequence);
    }

    /** Names any journal feature that would make a replay wrong rather than merely harder. */
    private String unsupported(int features) {
        List<String> names = new ArrayList<>();
        if ((features & FEATURE_FAST_COMMIT) != 0) names.add("fast commit");
        if ((features & FEATURE_ASYNC_COMMIT) != 0 && !checksums) {
            // Without checksums there is no way to tell a finished transaction from one
            // that was still being written, which is the whole safety of async commit.
            names.add("asynchronous commit without checksums");
        }
        int known = FEATURE_REVOKE | FEATURE_64BIT | FEATURE_ASYNC_COMMIT | FEATURE_CSUM_V2
                | FEATURE_CSUM_V3 | FEATURE_FAST_COMMIT;
        if ((features & ~known) != 0) {
            names.add("features this reader does not know (0x"
                    + Integer.toHexString(features & ~known) + ")");
        }
        return String.join(" and ", names);
    }

    /**
     * Walks the log from {@code start}, taking transactions in turn until one is not
     * whole.
     *
     * <p>A transaction's blocks are only adopted once its commit block has been seen,
     * so a transaction that was still being written when the card came out is
     * discarded entirely rather than half applied.
     */
    private void walk(long start, long sequence) throws IOException {
        long cursor = start;
        long expected = sequence;

        Map<Long, Long> pending = new LinkedHashMap<>();
        Set<Long> pendingEscaped = new HashSet<>();
        List<Long> pendingRevokes = new ArrayList<>();

        for (long scanned = 0; scanned < maxlen; scanned++) {
            byte[] block = journalBlock(cursor);
            if (be32(block, 0x00) != MAGIC) break;
            if ((be32(block, 0x08) & 0xFFFFFFFFL) != expected) break;

            int type = be32(block, 0x04);
            if (type == TYPE_DESCRIPTOR) {
                if (!tailChecksumMatches(block)) break;
                cursor = readTags(block, cursor, expected, pending, pendingEscaped);
                if (cursor < 0) break;
            } else if (type == TYPE_REVOKE) {
                if (!tailChecksumMatches(block)) break;
                if (!readRevokes(block, pendingRevokes)) break;
                cursor = advance(cursor);
            } else if (type == TYPE_COMMIT) {
                if (checksums && !commitChecksumMatches(block)) break;
                commit(pending, pendingEscaped, pendingRevokes);
                transactions++;
                expected = (expected + 1) & 0xFFFFFFFFL;
                cursor = advance(cursor);
            } else {
                break;
            }
        }
        // Whatever is still pending belongs to a transaction with no commit block, which
        // never happened as far as the filesystem is concerned.
    }

    /**
     * Adopts a finished transaction.
     *
     * <p>Revocations are applied first: a revoked block is one the filesystem freed and
     * handed to something else, so the copies of it in this transaction and every
     * earlier one describe a file that no longer exists there. A later transaction may
     * still write the block again, and then it counts.
     */
    private void commit(Map<Long, Long> pending, Set<Long> pendingEscaped,
                        List<Long> pendingRevokes) {
        for (long revoked : pendingRevokes) {
            newest.remove(revoked);
            escaped.remove(revoked);
        }
        for (Map.Entry<Long, Long> entry : pending.entrySet()) {
            newest.put(entry.getKey(), entry.getValue());
            if (pendingEscaped.contains(entry.getKey())) escaped.add(entry.getKey());
            else escaped.remove(entry.getKey());
        }
        pending.clear();
        pendingEscaped.clear();
        pendingRevokes.clear();
    }

    /**
     * Reads one descriptor block's tags, each naming a filesystem block whose new
     * contents are in the journal block that follows.
     *
     * @return the cursor after the last data block, or -1 if the block is unusable
     */
    private long readTags(byte[] descriptor, long cursor, long sequence,
                          Map<Long, Long> pending, Set<Long> pendingEscaped)
            throws IOException {
        int tagSize = checksumV3 ? 16 : (sixtyFourBit ? 12 : 8);
        int limit = blockSize - (checksums ? 4 : 0);
        int at = 12;
        long data = cursor;

        while (at + tagSize <= limit) {
            int tagAt = at;
            long low = be32(descriptor, tagAt) & 0xFFFFFFFFL;
            int flags;
            long high = 0;
            if (checksumV3) {
                flags = be32(descriptor, tagAt + 4);
                high = be32(descriptor, tagAt + 8) & 0xFFFFFFFFL;
            } else {
                flags = be16(descriptor, tagAt + 6);
                if (sixtyFourBit) high = be32(descriptor, tagAt + 8) & 0xFFFFFFFFL;
            }
            long target = low | (high << 32);

            at = tagAt + tagSize;
            // A tag that does not share the filesystem's UUID carries its own.
            if ((flags & TAG_SAME_UUID) == 0) at += 16;
            if (at > limit) return -1;
            if (target >= fs.blockCount()) return -1;

            data = advance(data);
            if (checksums && !tagChecksumMatches(descriptor, tagAt, data, sequence)) return -1;

            pending.put(target, physical(data));
            if ((flags & TAG_ESCAPE) != 0) pendingEscaped.add(target);
            else pendingEscaped.remove(target);

            if ((flags & TAG_LAST) != 0) break;
        }
        return advance(data);
    }

    /**
     * Collects the blocks a transaction says must not be replayed from before it.
     *
     * @return false if the block does not describe itself sensibly, which ends the replay
     */
    private boolean readRevokes(byte[] block, List<Long> out) {
        int used = be32(block, 12);
        int limit = blockSize - (checksums ? 4 : 0);
        if (used < 16 || used > limit) return false;

        int recordSize = sixtyFourBit ? 8 : 4;
        for (int at = 16; at + recordSize <= used; at += recordSize) {
            out.add(sixtyFourBit
                    ? ((long) be32(block, at) << 32) | (be32(block, at + 4) & 0xFFFFFFFFL)
                    : be32(block, at) & 0xFFFFFFFFL);
        }
        return true;
    }

    // --------------------------------------------------------------- checksums

    /**
     * Checks the checksum a descriptor or revocation block carries in its last four
     * bytes. Filesystems without journal checksums have nothing to check.
     */
    private boolean tailChecksumMatches(byte[] block) {
        if (!checksums) return true;
        int provided = be32(block, blockSize - 4);
        byte[] copy = block.clone();
        copy[blockSize - 4] = 0;
        copy[blockSize - 3] = 0;
        copy[blockSize - 2] = 0;
        copy[blockSize - 1] = 0;
        return provided == Ext4Checksum.crc32c(checksumSeed, copy, 0, blockSize);
    }

    /** Checks the checksum a commit block carries over itself. */
    private boolean commitChecksumMatches(byte[] block) {
        int provided = be32(block, 0x10);
        byte[] copy = block.clone();
        copy[0x10] = 0;
        copy[0x11] = 0;
        copy[0x12] = 0;
        copy[0x13] = 0;
        return provided == Ext4Checksum.crc32c(checksumSeed, copy, 0, blockSize);
    }

    /**
     * Checks one tag's checksum over the data block it points at, which is what proves
     * the block reached the card whole.
     */
    private boolean tagChecksumMatches(byte[] descriptor, int tagAt, long dataBlock,
                                       long sequence) throws IOException {
        byte[] data = journalBlock(dataBlock);
        int crc = Ext4Checksum.crc32c(checksumSeed, beBytes(sequence));
        crc = Ext4Checksum.crc32c(crc, data, 0, blockSize);

        if (checksumV3) return be32(descriptor, tagAt + 12) == crc;
        return be16(descriptor, tagAt + 4) == (crc & 0xFFFF);
    }

    // ----------------------------------------------------------------- helpers

    /** Wraps a cursor round the end of the circular log, as the journal itself does. */
    private long advance(long block) {
        long next = block + 1;
        return next >= maxlen ? next - (maxlen - first) : next;
    }

    /** Reads a block of the journal file, by its position within that file. */
    private byte[] journalBlock(long index) throws IOException {
        byte[] block = new byte[blockSize];
        long physical = physical(index);
        fs.device().readInto(physical * (long) blockSize, block, 0, blockSize);
        return block;
    }

    private long physical(long index) throws IOException {
        long physical = fs.blockOf(journalInode, index);
        if (physical == 0) {
            throw new Ext4Exception("The journal has a hole at block " + index);
        }
        return physical;
    }

    private static byte[] beBytes(long value) {
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16),
                            (byte) (value >>> 8), (byte) value };
    }

    private static int be32(byte[] block, int at) {
        return ((block[at] & 0xFF) << 24) | ((block[at + 1] & 0xFF) << 16)
                | ((block[at + 2] & 0xFF) << 8) | (block[at + 3] & 0xFF);
    }

    private static int be16(byte[] block, int at) {
        return ((block[at] & 0xFF) << 8) | (block[at + 1] & 0xFF);
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank())
                ? "its journal could not be read (" + e.getClass().getSimpleName() + ")"
                : "its journal could not be read: " + message;
    }
}
