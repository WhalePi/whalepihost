package whalepihost.model;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * An opened card, ready to be scanned.
 *
 * <p>Either a mounted folder or an ext4 partition read directly — see
 * {@link LocalCardSource} and {@link Ext4CardSource}.
 */
public interface CardSource extends Closeable {

    /** Where this card came from, for messages: a mount point or a device name. */
    String description();

    CardFile root() throws IOException;

    /**
     * Looks up an absolute path as recorded on the Pi, for example
     * {@code /home/whalepi/PAMRecordings}.
     *
     * @return the file, or null if it is not there
     */
    CardFile find(String absolutePath) throws IOException;

    /** True if files on this card can be written back. */
    default boolean canWrite() {
        return false;
    }

    /**
     * Why the card cannot be written to, for the user. "" when it can be.
     */
    default String writeBlockedReason() {
        return "This card is open for reading only.";
    }

    /**
     * Longest content an in-place rewrite of {@code file} may produce, or -1 when
     * there is no limit. An unmounted ext4 card can only rewrite a file at exactly
     * its current length, so the limit is the file's own size.
     */
    default long writeCapacity(CardFile file) {
        return -1;
    }

    /** Replaces a file's contents. */
    default void write(CardFile file, byte[] content) throws IOException {
        throw new IOException(writeBlockedReason());
    }

    /**
     * A warning about the card worth showing the user, or "" if all is well —
     * for instance an ext4 filesystem that was never cleanly unmounted.
     */
    default String warning() {
        return "";
    }

    // --------------------------------------------------------------------- deleting

    /** Told about each file as it goes, so a long delete can say what it is doing. */
    interface DeleteProgress {
        void deleted(String path);

        default boolean isCancelled() { return false; }
    }

    /**
     * True if files can be removed from this card. Stricter than {@link #canWrite()}:
     * an ext4 card also has to be one this program knows how to free space on.
     */
    default boolean canDelete() {
        return false;
    }

    /** Why files cannot be removed, for the user. "" when they can be. */
    default String deleteBlockedReason() {
        return "Files cannot be removed from this card.";
    }

    /**
     * Removes a file, or a folder and everything under it.
     *
     * <p>This is not reversible and there is no trash to fall back on, so callers are
     * expected to have asked first.
     */
    default DeleteResult delete(CardFile file, DeleteProgress progress) throws IOException {
        throw new IOException(deleteBlockedReason());
    }

    /** Opens whatever kind of card {@code volume} refers to. */
    static CardSource open(Volume volume) throws IOException {
        Path device = volume.device();
        if (device != null) return new Ext4CardSource(device, volume.label());

        Path path = volume.path();
        if (path == null) throw new IOException("Nothing to read on " + volume.label());
        return new LocalCardSource(path);
    }
}
