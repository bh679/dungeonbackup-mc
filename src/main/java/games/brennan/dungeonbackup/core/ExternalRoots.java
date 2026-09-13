package games.brennan.dungeonbackup.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The OS-specific out-of-instance location for restore points.
 *
 * <p>Everything else Dungeon Backup writes is instance-local, which is enough for a modpack
 * update: packs replace {@code config/}, not the whole instance. It is <em>not</em> enough for a
 * delete-and-reinstall, or for the launcher bugs that have removed entire instance trees — and a
 * pack can also ship {@code overrides/<dataRoot>/} straight into the data root. The per-user
 * application-data folder is the only location no launcher operation on the instance can
 * reach.</p>
 *
 * <p>Empty when no home directory can be resolved, which is a normal outcome on a locked-down or
 * headless host and must never be treated as an error — the in-instance backup still ran.</p>
 */
public final class ExternalRoots {

    /** Folder name under the app-data dir, always. */
    public static final String BACKUPS = "backups";

    private ExternalRoots() {}

    /** {@code <os app data>/<dirName>/backups}, or empty. Never throws. */
    public static Optional<Path> resolve(String dirName) {
        if (dirName == null || dirName.isBlank()) return Optional.empty();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                if (appData != null && !appData.isBlank()) {
                    return Optional.of(Path.of(appData).resolve(dirName).resolve(BACKUPS));
                }
            }
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) return Optional.empty();
            Path base = Path.of(home);
            if (os.contains("mac") || os.contains("darwin")) {
                return Optional.of(base.resolve("Library").resolve("Application Support")
                    .resolve(dirName).resolve(BACKUPS));
            }
            String xdg = System.getenv("XDG_DATA_HOME");
            Path dataHome = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : base.resolve(".local").resolve("share");
            return Optional.of(dataHome.resolve(dirName).resolve(BACKUPS));
        } catch (Exception e) {
            // A malformed home path must not take the game down.
            return Optional.empty();
        }
    }
}
