package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.api.Kind;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Relocation;
import games.brennan.dungeonbackup.api.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves a mod's player data out of {@code config/} and into its data root.
 *
 * <p>The move itself is the fix for the pack-update data loss Dungeon Backup exists for: modpack
 * launchers replace {@code config/} on update. This class is the one-shot that gets existing
 * installs out of there.</p>
 *
 * <p><b>It cannot lose data, by construction.</b> Files move one at a time; a file whose
 * destination already exists is left where it is rather than overwriting newer content; a move that
 * fails leaves the source untouched so the next launch retries it; and directories are only deleted
 * once demonstrably empty. On top of that, {@link BackupArchiver} takes a snapshot of the legacy
 * tree <em>before</em> the first file moves: no backup, no write.</p>
 *
 * <p>Idempotent, so it is safe to call on every server start, and it is: a second run finds nothing
 * left in {@code config/} and returns immediately.</p>
 *
 * <p>Takes explicit roots and an explicit relocation list rather than reading {@code FMLPaths}, so
 * the whole migration is drivable from a unit test against {@code @TempDir}.</p>
 */
public final class DataMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    /** Mod ids whose migration has completed in this JVM, so repeated server starts don't re-walk. */
    private static final Set<String> RAN_THIS_SESSION = ConcurrentHashMap.newKeySet();

    /** What one migration run did. */
    public record Result(int movedFiles, int skippedExisting, int failed) {
        public boolean movedAnything() { return movedFiles > 0; }
        public boolean clean() { return failed == 0; }
    }

    private DataMigration() {}

    /**
     * Migrate one registration's install, at most once per game session. Never throws: player data
     * must not be able to take the game down, and an install that fails to migrate still works —
     * every relocated store falls back to reading its {@code config/} copy
     * ({@link games.brennan.dungeonbackup.api.Located#readPath}).
     */
    public static void runOnce(Registration registration, Path configDir) {
        if (!RAN_THIS_SESSION.add(registration.modId())) return;

        // The consumer's own earlier one-shots first, so their files are at their final names
        // before this pass walks.
        try {
            registration.beforeMigration().run();
        } catch (Exception e) {
            LOGGER.error("[DungeonBackup] {}: pre-migration hook failed: {}", registration.modId(), e.toString());
        }

        if (registration.relocations().isEmpty()) return;
        Path dataRoot = registration.dataRoot();
        try {
            if (!hasLegacyData(registration.relocations(), configDir)) {
                LOGGER.debug("[DungeonBackup] {}: nothing left in config/.", registration.modId());
                return;
            }
            backupBeforeMoving(registration, configDir);
            Result result = migrate(registration.relocations(), configDir, dataRoot);
            LOGGER.info("[DungeonBackup] {}: moved {} file(s) out of config/ into {} "
                + "({} already present, {} failed and left in place)",
                registration.modId(), result.movedFiles(), dataRoot, result.skippedExisting(), result.failed());
        } catch (Exception e) {
            LOGGER.error("[DungeonBackup] {}: migration failed — data left in config/, reading from "
                + "there and retrying next launch", registration.modId(), e);
        }
    }

    /** Test seam: forget that a mod already migrated this session. */
    static void resetSession() {
        RAN_THIS_SESSION.clear();
    }

    /** Is there anything still in the old {@code config/} locations? */
    public static boolean hasLegacyData(List<Relocation> relocations, Path configDir) {
        for (Relocation relocation : relocations) {
            Path legacy = relocation.legacyPath(configDir);
            if (relocation.kind() == Kind.FILE) {
                if (Files.isRegularFile(legacy)) return true;
            } else if (FileTrees.containsAnyFile(legacy)) {
                return true;
            }
        }
        return false;
    }

    /** Move every relocated entry from {@code configDir} into {@code dataRoot}. */
    public static Result migrate(List<Relocation> relocations, Path configDir, Path dataRoot)
            throws IOException {
        int moved = 0;
        int skipped = 0;
        int failed = 0;
        for (Relocation relocation : relocations) {
            Path from = relocation.legacyPath(configDir);
            Path to = relocation.newPath(dataRoot);
            if (relocation.kind() == Kind.FILE) {
                switch (moveFile(from, to)) {
                    case MOVED -> moved++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                    case ABSENT -> { }
                }
                continue;
            }
            if (!Files.isDirectory(from)) continue;
            for (Path file : FileTrees.filesUnder(from)) {
                Path target = to.resolve(from.relativize(file).toString());
                switch (moveFile(file, target)) {
                    case MOVED -> moved++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                    case ABSENT -> { }
                }
            }
            deleteEmptyDirs(from);
        }
        return new Result(moved, skipped, failed);
    }

    // ---- Internals ----

    private enum MoveOutcome { MOVED, SKIPPED, FAILED, ABSENT }

    /**
     * Move one file. A destination that already exists wins — it is either newer content the player
     * made after the migration, or a previous run's work — so the source is left alone rather than
     * overwriting it. Nothing here deletes.
     */
    private static MoveOutcome moveFile(Path from, Path to) {
        if (!Files.isRegularFile(from)) return MoveOutcome.ABSENT;
        if (Files.exists(to)) {
            LOGGER.debug("[DungeonBackup] Migration: {} already exists — leaving {} alone.", to, from);
            return MoveOutcome.SKIPPED;
        }
        try {
            Files.createDirectories(to.getParent());
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException atomicUnsupported) {
                // config/ and the instance root can be on different filesystems.
                Files.move(from, to);
            }
            return MoveOutcome.MOVED;
        } catch (IOException e) {
            LOGGER.warn("[DungeonBackup] Migration: couldn't move {} -> {} ({}). "
                + "Left in place; will retry next launch.", from, to, e.toString());
            return MoveOutcome.FAILED;
        }
    }

    /**
     * Remove {@code dir} and its subdirectories, but only the ones that are empty — a directory
     * still holding a file we failed to move stays, and so does everything above it.
     */
    private static void deleteEmptyDirs(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) deleteEmptyDirs(child);
            }
        } catch (IOException e) {
            return;
        }
        try (DirectoryStream<Path> remaining = Files.newDirectoryStream(dir)) {
            if (remaining.iterator().hasNext()) return;
        } catch (IOException e) {
            return;
        }
        try {
            Files.delete(dir);
        } catch (IOException ignored) {
            // An empty directory we can't remove is harmless — it just sits there.
        }
    }

    /**
     * Snapshot the legacy tree before anything moves. A failure here <b>aborts the migration</b>:
     * data that can't be backed up doesn't get touched, and the install keeps working off its
     * {@code config/} copies until the next launch.
     */
    private static void backupBeforeMoving(Registration registration, Path configDir) throws IOException {
        List<Source> sources = new ArrayList<>();
        for (Relocation relocation : registration.relocations()) {
            if (relocation.kind() != Kind.DIRECTORY) continue;
            Path legacy = relocation.legacyPath(configDir);
            if (Files.isDirectory(legacy)) {
                sources.add(new Source(relocation.newRelative().replace('/', '_'), legacy));
            }
        }
        if (sources.isEmpty()) return;
        BackupArchiver.create(registration.backupsRoot(), registration.modId(), sources,
            "pre-migration", registration.modVersion());
    }
}
