package games.brennan.dungeonbackup.api;

import java.io.IOException;
import java.util.Optional;

/**
 * How a restore folds a backed-up file into one that already exists on disk.
 *
 * <p>Restores are additive: a file already on disk is normally left alone. That is right for a
 * build the player has since edited, and wrong for a file the consumer rewrites on its own — a
 * cross-world progress record regenerated on every world join, say. After a data loss that file
 * reappears, small, before the player gets to the restore button, and a skip-if-exists restore
 * then keeps the small one. A merger lets the consumer say "these files grow; combine them".</p>
 *
 * <p>Registered per path with {@link Registration.Builder#mergeOnRestore}. Anything it throws, or
 * an empty result, leaves the live file exactly as it was — a restore never makes things worse.</p>
 */
@FunctionalInterface
public interface RestoreMerger {

    /**
     * @param live     the bytes of the file on disk now
     * @param backedUp the bytes of the same file in the backup
     * @return the bytes to write in place of {@code live}, or empty to leave it untouched
     */
    Optional<byte[]> merge(byte[] live, byte[] backedUp) throws IOException;

    /** Finds the merger for one file, by archive label and {@code /}-separated path under it. */
    @FunctionalInterface
    interface Resolver {
        Resolver NONE = (label, relativePath) -> Optional.empty();

        Optional<RestoreMerger> find(String label, String relativePath);
    }
}
