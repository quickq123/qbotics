package qbotics.block;

/**
 * For a block that stores fluid or energy and offers it to other mods. Their standard
 * transfer APIs try a change and may take it back, so the block must be able to remember
 * what it held and return to it.
 */
public interface StorageSnapshots {
    /** Everything that a transfer could change, copied. */
    Object takeSnapshot();

    /** Goes back to what {@link #takeSnapshot()} recorded. */
    void restoreSnapshot(Object snapshot);

    /** Marks the block as needing to be saved. */
    void setChanged();
}
