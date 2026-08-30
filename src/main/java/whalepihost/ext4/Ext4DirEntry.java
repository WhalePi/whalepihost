package whalepihost.ext4;

/**
 * One entry in a directory.
 *
 * @param name  the file name, without any path
 * @param inode the inode it points at
 * @param type  the {@code file_type} byte, when the filesystem records it
 */
public record Ext4DirEntry(String name, long inode, int type) {

    private static final int TYPE_UNKNOWN   = 0;
    private static final int TYPE_REGULAR   = 1;
    private static final int TYPE_DIRECTORY = 2;

    /**
     * Whether this entry names a directory according to the directory itself.
     *
     * <p>Old filesystems without the {@code filetype} feature always record
     * {@link #TYPE_UNKNOWN}; callers must then read the inode to find out.
     */
    public boolean isDirectory() { return type == TYPE_DIRECTORY; }

    public boolean isRegularFile() { return type == TYPE_REGULAR; }

    /** True if the entry gives no type, so the inode has to be consulted. */
    public boolean typeUnknown() { return type == TYPE_UNKNOWN; }
}
