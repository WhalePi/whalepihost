package whalepihost.ext4;

/**
 * A run of consecutive file blocks stored consecutively on disk.
 *
 * <p>Extent-mapped files describe themselves this way directly; for older
 * indirect-block files the reader coalesces runs into the same shape, so the rest of
 * the code only ever deals with extents.
 *
 * @param logicalBlock  first block within the file
 * @param physicalBlock first block on the device
 * @param length        number of blocks
 */
record Extent(long logicalBlock, long physicalBlock, long length) {

    /** True if {@code block} (a file-relative block number) falls inside this run. */
    boolean contains(long block) {
        return block >= logicalBlock && block < logicalBlock + length;
    }

    /** Device block holding the given file block. */
    long physicalFor(long block) {
        return physicalBlock + (block - logicalBlock);
    }
}
