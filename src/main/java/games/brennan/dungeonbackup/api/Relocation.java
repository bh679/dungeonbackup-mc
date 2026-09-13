package games.brennan.dungeonbackup.api;

import java.nio.file.Path;

/**
 * One piece of player data that moved out of {@code config/}.
 *
 * <p>Relocations are the <em>only</em> place a consumer enumerates its data, and only because a
 * one-shot legacy migration is by nature a finite historical list: "this used to live at
 * {@code config/<x>}, now it lives at {@code <dataRoot>/<y>}". Everything else in Dungeon Backup —
 * archiving, restoring, the loss scan — works on the whole data root wholesale, so a folder added
 * later is protected without anyone remembering to list it.</p>
 *
 * @param legacyRelative where it used to live, relative to the <b>config</b> directory
 * @param newRelative    where it lives now, relative to the registration's data root
 */
public record Relocation(String legacyRelative, String newRelative, Kind kind) {

    public Relocation {
        if (legacyRelative == null || legacyRelative.isBlank()) {
            throw new IllegalArgumentException("legacyRelative is required");
        }
        if (newRelative == null || newRelative.isBlank()) {
            throw new IllegalArgumentException("newRelative is required");
        }
        if (kind == null) throw new IllegalArgumentException("kind is required");
    }

    public Path legacyPath(Path configDir) {
        return resolve(configDir, legacyRelative);
    }

    public Path newPath(Path dataRoot) {
        return resolve(dataRoot, newRelative);
    }

    /** Resolve a {@code /}-separated relative path without assuming the platform separator. */
    public static Path resolve(Path base, String relative) {
        Path p = base;
        for (String segment : relative.split("/")) {
            if (!segment.isEmpty()) p = p.resolve(segment);
        }
        return p;
    }
}
