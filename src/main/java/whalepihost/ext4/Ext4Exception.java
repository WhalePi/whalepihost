package whalepihost.ext4;

import java.io.IOException;

/** Raised when a device is not an ext4 filesystem, or holds something unreadable. */
public class Ext4Exception extends IOException {

    private static final long serialVersionUID = 1L;

    public Ext4Exception(String message) {
        super(message);
    }

    public Ext4Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
