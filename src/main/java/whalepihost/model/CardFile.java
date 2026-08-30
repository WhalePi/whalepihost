package whalepihost.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A file or folder on a card, wherever the card is being read from.
 *
 * <p>The card may be a mounted volume the operating system understands, or an ext4
 * partition WhalePi Host is parsing itself. Everything above this interface —
 * scanning, sizing, copying — works the same either way.
 */
public interface CardFile {

    /** The file's own name, with no path. */
    String name();

    /** Full path as it appears on the card, for display. */
    String path();

    boolean isDirectory();

    boolean isRegularFile();

    /** Size in bytes; 0 for a directory. */
    long size();

    /** Modification time in seconds since the epoch, or 0 if unknown. */
    long modifiedEpochSeconds();

    /** Children of a directory, or an empty list for anything else. */
    List<CardFile> list() throws IOException;

    /** Opens a regular file for reading. */
    InputStream open() throws IOException;

    /** A named child, or null if there isn't one. */
    CardFile child(String name) throws IOException;

    /** Junk the desktop operating systems scatter over removable media. */
    default boolean isNoise() {
        String name = name();
        return name.startsWith("._") || name.equals(".DS_Store") || name.equals("Thumbs.db");
    }
}
