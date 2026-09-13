package games.brennan.dungeonbackup.api;

import java.nio.file.Path;
import java.util.Set;

/**
 * One tree to back up. {@code label} becomes the top-level folder inside the archive, and is
 * also what a restore uses to work out which tree an entry belongs to.
 *
 * @param excludeTopLevel names of direct children of {@code dir} to skip — this is how the
 *                        backups folder stays out of its own archives
 */
public record Source(String label, Path dir, Set<String> excludeTopLevel) {

    public Source {
        if (label == null || label.isBlank() || label.contains("/") || label.contains("\\")) {
            throw new IllegalArgumentException("label must be a single non-empty path segment: " + label);
        }
        excludeTopLevel = excludeTopLevel == null ? Set.of() : Set.copyOf(excludeTopLevel);
    }

    public Source(String label, Path dir) {
        this(label, dir, Set.of());
    }

    /**
     * Whether {@code file} sits under one of this source's excluded top-level folders — what the
     * walk skips, and therefore what is <em>not</em> in an archive. Public because a caller
     * working out which entry name a file would have has to ask the same question the writer did.
     */
    public boolean excludes(Path dir, Path file) {
        Path relative = dir.relativize(file);
        return relative.getNameCount() > 0
            && excludeTopLevel.contains(relative.getName(0).toString());
    }
}
