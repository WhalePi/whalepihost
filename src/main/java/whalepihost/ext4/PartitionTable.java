package whalepihost.ext4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * The partition table at the front of a whole disk, read directly.
 *
 * <p>This is needed on Windows. macOS and Linux both publish a device node per
 * partition, so WhalePi Host can open the data partition by name and never has to know
 * where on the card it starts. Windows publishes drive letters, and a partition whose
 * filesystem it does not recognise gets none — so the only way to the ext4 partition is
 * {@code \\.\PhysicalDriveN}, the whole disk, and finding the partition inside it means
 * reading the table ourselves.
 *
 * <p>Both layouts a card can use are handled: the MBR a Raspberry Pi image actually
 * uses, and GPT. Logical partitions inside an MBR extended partition are not walked —
 * no Pi image has ever used one, and the two partitions of a WhalePi card are both
 * primary.
 */
public final class PartitionTable {

    /** SD cards, and every Pi image, use 512-byte sectors. */
    private static final int SECTOR = 512;

    /** Enough of the front of a disk to hold an MBR, a GPT header and its entries. */
    private static final long HEADER_REGION = 2L * 1024 * 1024;

    private static final int MBR_SIGNATURE_OFFSET = 510;
    private static final int MBR_FIRST_ENTRY = 0x1BE;
    private static final int MBR_ENTRY_SIZE = 16;
    private static final int MBR_ENTRY_COUNT = 4;

    /** MBR partition type 0xEE: the disk is really GPT and this is the protective MBR. */
    private static final int MBR_TYPE_GPT = 0xEE;
    /** MBR partition type 0x83: a Linux filesystem, which is what a Pi's rootfs is. */
    private static final int MBR_TYPE_LINUX = 0x83;

    /** "EFI PART", the GPT header signature. */
    private static final long GPT_SIGNATURE = 0x5452415020494645L;

    /** Bytes of a GPT header sector to read: the header itself is 92 of them. */
    private static final int GPT_HEADER_READ = 512;

    /** Sector sizes a GPT header might be found at, in order of likelihood. */
    private static final int[] GPT_SECTOR_SIZES = { 512, 4096 };

    /**
     * Partition type GUIDs that mean "a Linux filesystem lives here", as they appear on
     * disk — the first three fields little-endian, the rest in order.
     */
    private static final List<byte[]> GPT_LINUX_TYPES = List.of(
            // 0fc63daf-8483-4772-8e79-3d69d8477de4 — Linux filesystem data
            guid(0x0fc63daf, 0x8483, 0x4772, "8e793d69d8477de4"),
            // b921b045-1df0-41c3-af44-4c6f280d3fae — Linux root (arm64)
            guid(0xb921b045, 0x1df0, 0x41c3, "af444c6f280d3fae"),
            // 69dad710-2ce4-4e3c-b16c-21a1d49abed3 — Linux root (arm32)
            guid(0x69dad710, 0x2ce4, 0x4e3c, "b16c21a1d49abed3"));

    private PartitionTable() { }

    /**
     * One partition of a disk.
     *
     * @param index  partition number as the table gives it, counting from 1
     * @param offset where the partition starts, in bytes from the front of the disk
     * @param length how long it is, in bytes
     * @param type   the type as the table records it, for messages
     * @param name   the partition's name, or "" — GPT carries one, MBR does not
     * @param linux  true if the type says a Linux filesystem, so probably the card's data
     */
    public record Entry(int index, long offset, long length, String type, String name,
                        boolean linux) {

        /** The region of {@code disk} this partition occupies. */
        public DeviceRegion region(Path disk) {
            return new DeviceRegion(disk, offset, length);
        }
    }

    /**
     * Reads the partition table of a whole disk.
     *
     * @throws IOException if the disk cannot be opened — on Windows that normally means
     *                     the program is not running as administrator
     */
    public static List<Entry> read(Path disk) throws IOException {
        // A fixed region, so that a device which will not report its length is not
        // binary-searched for an end we have no use for.
        try (BlockDevice device = new BlockDevice(new DeviceRegion(disk, 0, HEADER_REGION), false)) {
            return read(device);
        }
    }

    /** Reads the partition table through an already-open device. */
    static List<Entry> read(BlockDevice device) throws IOException {
        ByteBuffer mbr = device.read(0, SECTOR);
        boolean signed = (mbr.getShort(MBR_SIGNATURE_OFFSET) & 0xFFFF) == 0xAA55;

        // A GPT disk carries a protective MBR holding one 0xEE partition. Some disks
        // have no valid MBR at all, so GPT is worth a look either way.
        if (!signed || hasGptMarker(mbr)) {
            List<Entry> gpt = readGpt(device);
            if (!gpt.isEmpty()) return gpt;
        }
        return signed ? readMbr(mbr) : List.of();
    }

    // ------------------------------------------------------------------------- MBR

    private static boolean hasGptMarker(ByteBuffer mbr) {
        for (int i = 0; i < MBR_ENTRY_COUNT; i++) {
            if ((mbr.get(MBR_FIRST_ENTRY + i * MBR_ENTRY_SIZE + 4) & 0xFF) == MBR_TYPE_GPT) {
                return true;
            }
        }
        return false;
    }

    private static List<Entry> readMbr(ByteBuffer mbr) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < MBR_ENTRY_COUNT; i++) {
            int at = MBR_FIRST_ENTRY + i * MBR_ENTRY_SIZE;
            int status = mbr.get(at) & 0xFF;
            int type = mbr.get(at + 4) & 0xFF;
            long startSector = mbr.getInt(at + 8) & 0xFFFFFFFFL;
            long sectors = mbr.getInt(at + 12) & 0xFFFFFFFFL;
            if (type == 0 || startSector == 0 || sectors == 0) continue;
            // A real entry is either bootable or not; anything else is not a partition
            // table at all. Worth checking, because on Windows this program may be
            // looking at the front of a disk that was never partitioned.
            if (status != 0x00 && status != 0x80) continue;

            entries.add(new Entry(i + 1, startSector * SECTOR, sectors * SECTOR,
                    mbrTypeName(type), "", type == MBR_TYPE_LINUX));
        }
        return entries;
    }

    private static String mbrTypeName(int type) {
        String name = switch (type) {
            case 0x01, 0x04, 0x06, 0x0e -> "FAT16";
            case 0x05, 0x0f, 0x85 -> "extended";
            case 0x07 -> "NTFS or exFAT";
            case 0x0b, 0x0c -> "FAT32";
            case 0x82 -> "Linux swap";
            case MBR_TYPE_LINUX -> "Linux";
            case 0xaf -> "HFS+";
            case MBR_TYPE_GPT -> "GPT";
            default -> "";
        };
        String hex = String.format("0x%02x", type);
        return name.isEmpty() ? hex : name + " (" + hex + ")";
    }

    // ------------------------------------------------------------------------- GPT

    /**
     * Reads a GPT, if there is one.
     *
     * <p>Both of the format's CRC32s are checked. That matters more than it looks: on
     * Windows this may be reading the front of any disk at all, and without the
     * checksums a disk of random bytes can present a plausible-looking table. With them,
     * only a genuine GPT parses.
     */
    private static List<Entry> readGpt(BlockDevice device) throws IOException {
        for (int sectorSize : GPT_SECTOR_SIZES) {
            byte[] header = new byte[GPT_HEADER_READ];
            try {
                device.readInto(sectorSize, header, 0, header.length);
            } catch (IOException e) {
                continue;
            }
            ByteBuffer fields = ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (fields.getLong(0) != GPT_SIGNATURE) continue;

            int headerSize = fields.getInt(12);
            if (headerSize < 92 || headerSize > GPT_HEADER_READ) continue;
            if (crc32(header, 0, headerSize, 16) != (fields.getInt(16) & 0xFFFFFFFFL)) continue;

            long entryLba = fields.getLong(72);
            long count = fields.getInt(80) & 0xFFFFFFFFL;
            int entrySize = fields.getInt(84);
            if (entryLba <= 0 || count <= 0 || count > 512 || entrySize < 128 || entrySize > 4096) {
                continue;
            }
            long tableStart = entryLba * sectorSize;
            long tableBytes = count * entrySize;
            if (tableStart + tableBytes > HEADER_REGION) continue;

            byte[] table = new byte[(int) tableBytes];
            try {
                device.readInto(tableStart, table, 0, table.length);
            } catch (IOException e) {
                continue;
            }
            if (crc32(table, 0, table.length, -1) != (fields.getInt(88) & 0xFFFFFFFFL)) continue;

            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ByteBuffer entry = ByteBuffer.wrap(table, i * entrySize, entrySize)
                        .slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
                Entry parsed = parseGptEntry(entry, i + 1, sectorSize);
                if (parsed != null) entries.add(parsed);
            }
            return entries;
        }
        return List.of();
    }

    /**
     * CRC32 of a run of bytes.
     *
     * @param skipAt offset of a four-byte field to treat as zero — the GPT header holds
     *               its own checksum, which cannot be part of what it covers — or -1
     */
    private static long crc32(byte[] bytes, int offset, int length, int skipAt) {
        byte[] covered = java.util.Arrays.copyOfRange(bytes, offset, offset + length);
        if (skipAt >= 0) {
            for (int i = 0; i < 4; i++) covered[skipAt - offset + i] = 0;
        }
        CRC32 crc = new CRC32();
        crc.update(covered);
        return crc.getValue();
    }

    private static Entry parseGptEntry(ByteBuffer entry, int index, int sectorSize) {
        byte[] type = new byte[16];
        for (int i = 0; i < 16; i++) type[i] = entry.get(i);
        if (isZero(type)) return null;

        long firstLba = entry.getLong(32);
        long lastLba = entry.getLong(40);
        if (firstLba <= 0 || lastLba < firstLba) return null;

        byte[] nameBytes = new byte[72];
        for (int i = 0; i < 72; i++) nameBytes[i] = entry.get(56 + i);
        String name = new String(nameBytes, StandardCharsets.UTF_16LE);
        int end = name.indexOf('\0');
        if (end >= 0) name = name.substring(0, end);
        name = name.trim();

        return new Entry(index, firstLba * sectorSize,
                (lastLba - firstLba + 1) * sectorSize,
                guidText(type), name, isLinuxType(type));
    }

    private static boolean isLinuxType(byte[] type) {
        for (byte[] known : GPT_LINUX_TYPES) {
            if (java.util.Arrays.equals(known, type)) return true;
        }
        return false;
    }

    private static boolean isZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

    /** Builds the on-disk form of a type GUID: three little-endian fields, then bytes. */
    private static byte[] guid(int first, int second, int third, String rest) {
        byte[] bytes = new byte[16];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, first);
        buffer.putShort(4, (short) second);
        buffer.putShort(6, (short) third);
        for (int i = 0; i < 8; i++) {
            bytes[8 + i] = (byte) Integer.parseInt(rest.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /** The printed form of an on-disk type GUID, for messages. */
    private static String guidText(byte[] type) {
        ByteBuffer buffer = ByteBuffer.wrap(type).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%08x-%04x-%04x-",
                buffer.getInt(0), buffer.getShort(4) & 0xFFFF, buffer.getShort(6) & 0xFFFF));
        for (int i = 8; i < 16; i++) {
            if (i == 10) text.append('-');
            text.append(String.format(Locale.ROOT, "%02x", type[i]));
        }
        return text.toString();
    }
}
