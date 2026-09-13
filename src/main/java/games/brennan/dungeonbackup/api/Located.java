package games.brennan.dungeonbackup.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One player-data file at both the address it has now and the one it had before the move.
 *
 * <p>Stores read through {@link #read()}, which prefers the new location. Deletes go through
 * {@link #all()}, so a legacy copy the migration had to leave behind (because a file of the same
 * name already existed at the destination) can't survive a profile reset and reappear.</p>
 */
public record Located(Path preferred, Path legacy) {

    /** Where to read from — and, by extension, where to write, so the two never disagree. */
    public Path read() {
        return readPath(preferred, legacy);
    }

    /** Both addresses, for callers that must act on every copy — i.e. deletion. */
    public List<Path> all() {
        return List.of(preferred, legacy);
    }

    /**
     * The path a store should <b>read</b> from: the new one when it exists, otherwise the
     * pre-move {@code config/} copy.
     *
     * <p>The migration normally moves everything at server start, so the fallback is rarely taken.
     * It exists because a migration can be partial — an unreadable file is left where it was and
     * retried next launch — and a player must never see "your progress is gone" because one file
     * in the batch failed to move. Writes always go to the new path.</p>
     */
    public static Path readPath(Path preferred, Path legacy) {
        if (preferred == null) return legacy;
        if (legacy == null) return preferred;
        return Files.exists(preferred) ? preferred : (Files.exists(legacy) ? legacy : preferred);
    }
}
