package whalepihost.ext4;

import java.nio.file.Path;

/**
 * A device or image file, and the region of it that holds a filesystem.
 *
 * <p>Most of the time the region is the whole thing: macOS and Linux both hand out a
 * device node per partition ({@code /dev/rdisk6s2}), so the filesystem starts at byte
 * zero of it. Windows does not — there is no dependable per-partition device for a
 * partition whose filesystem it does not recognise, so the only way in is
 * {@code \\.\PhysicalDrive1}, the whole disk, with the partition's own bytes somewhere
 * inside it. This record carries that "somewhere".
 *
 * @param path   device node or image file to open
 * @param offset byte offset of the region within it; 0 for the whole device
 * @param length bytes in the region, or 0 to run to the end of the device
 */
public record DeviceRegion(Path path, long offset, long length) {

    public DeviceRegion {
        if (offset < 0) throw new IllegalArgumentException("Negative region offset: " + offset);
        if (length < 0) throw new IllegalArgumentException("Negative region length: " + length);
    }

    /** The whole of a device or image, which is what a partition device node gives. */
    public static DeviceRegion whole(Path path) {
        return new DeviceRegion(path, 0, 0);
    }

    /** True if this is the whole device rather than a region inside it. */
    public boolean isWholeDevice() {
        return offset == 0 && length == 0;
    }

    /** For messages: the device, and where in it the filesystem sits. */
    @Override
    public String toString() {
        return isWholeDevice() ? path.toString() : path + " at " + offset;
    }
}
