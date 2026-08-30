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

    /**
     * A warning about the card worth showing the user, or "" if all is well —
     * for instance an ext4 filesystem that was never cleanly unmounted.
     */
    default String warning() {
        return "";
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
