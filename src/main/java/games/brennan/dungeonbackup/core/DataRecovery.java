package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Relocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds player data that an install already lost, and works out what could be put back.
 *
 * <p>Relocation and migration stop the loss happening again. They do nothing for the player it
 * already happened to — someone who pressed "Update Pack", watched their data disappear, and now
 * has an install that looks factory-fresh. This class is for them.</p>
 *
 * <p><b>The loss signature has to be narrow</b>, because "this install has no data" is also what a
 * genuinely new install looks like, and nobody wants a recovery prompt on their first launch. So it
 * fires only when all three hold:</p>
 * <ol>
 *   <li>the registration's liveness probe says the live root holds nothing;</li>
 *   <li>the pre-move {@code config/} locations are empty too — if they aren't, nothing was lost and
 *       the migration is about to move them across;</li>
 *   <li>a candidate exists to restore <em>from</em>.</li>
 * </ol>
 *
 * <p>Candidates are ranked: this install's own backups first (certain provenance), then sibling
 * instance folders (a plausible guess). Sibling candidates are <b>never restored automatically</b>
 * — the folder next door may belong to a different pack or a different person, so the player is
 * shown the full path and decides.</p>
 *
 * <p>Read-only and {@code FMLPaths}-free: every entry point takes explicit roots, so the scan is
 * drivable from JUnit with {@code @TempDir}.</p>
 */
public final class DataRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    /** How many directory levels above the instance to look for sibling installs. */
    static final int SIBLING_LEVELS = 2;

    /** Cap on sibling directories examined per level, so a huge folder can't stall the title screen. */
    static final int MAX_SIBLINGS_PER_LEVEL = 64;

    /** Where a candidate came from. Ordinal order is the ranking the player is offered. */
    public enum Kind { EXTERNAL_BACKUP, BACKUP, SIBLING_INSTANCE }

    /**
     * Something worth restoring.
     *
     * @param kind        backup archive, or another instance folder on this machine
     * @param path        the archive, or the sibling instance root — shown to the player verbatim
     * @param description short human-readable label
     */
    public record Candidate(Kind kind, Path path, String description) {

        /**
         * The archive's filename, or the instance folder's name, as a String.
         *
         * <p>String, not {@link Path}, on purpose: these values are handed to
         * {@code Component.translatable}, which accepts only Component / Number / Boolean / String
         * and throws at render time on anything else. Passing a {@code Path} compiles cleanly and
         * then crashes the screen — it did exactly that. Exposing the display forms as Strings
         * means the mistake can no longer be made.</p>
         */
        public String fileName() {
            return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
        }

        /** The full path, as a String, for the same reason as {@link #fileName()}. */
        public String location() {
            return path == null ? "" : path.toString();
        }

        /** The containing folder, as a String; empty when there is no parent. */
        public String folder() {
            return path == null || path.getParent() == null ? "" : path.getParent().toString();
        }
    }

    private DataRecovery() {}

    /**
     * Does this install look like one that lost its data?
     *
     * @param configDir the config directory, checked for data the migration hasn't moved yet
     */
    public static boolean looksEmptied(Registration registration, Path configDir) {
        if (registration.hasLiveData(registration.dataRoot())) return false;
        // Data still sitting in config/ isn't lost — it's about to be migrated.
        return !DataMigration.hasLegacyData(registration.relocations(), configDir);
    }

    /**
     * Everything that could be restored, best first.
     *
     * @param gameDir the instance root, whose parents are searched for sibling installs
     * @param externalBackupsRoot the out-of-instance backup folder, or {@code null} if there is
     *                            none. Passed in rather than resolved here so this stays a pure
     *                            function of its arguments — reading the real OS app-data folder
     *                            made the result depend on whatever else is on the machine.
     */
    public static List<Candidate> findCandidates(Registration registration, Path gameDir,
                                                 Path externalBackupsRoot) {
        String modId = registration.modId();
        List<Candidate> found = new ArrayList<>();
        // Ranked first: an archive this install wrote, kept OUTSIDE the instance. It has the same
        // certain provenance as an in-instance backup and survives strictly more — it is the only
        // candidate that exists at all when the instance was deleted and reinstalled.
        for (Path external : externalBackupsRoot == null
                ? List.<Path>of() : BackupArchiver.listArchives(externalBackupsRoot, modId)) {
            found.add(new Candidate(Kind.EXTERNAL_BACKUP, external, external.getFileName().toString()));
        }
        for (Path archive : BackupArchiver.listArchives(registration.backupsRoot(), modId)) {
            found.add(new Candidate(Kind.BACKUP, archive, archive.getFileName().toString()));
        }
        found.addAll(findSiblingInstances(registration, gameDir));
        return List.copyOf(found);
    }

    /**
     * Other Minecraft instances on this machine that still hold this mod's data.
     *
     * <p>A player who "updated" by installing the pack into a fresh instance still has the old one
     * next door — that is the single most likely place their data still exists. Launchers nest
     * instances one or two levels up ({@code .../Instances/<pack>/} for CurseForge,
     * {@code .../profiles/<pack>/} for Modrinth), so both levels are searched.</p>
     *
     * <p>Bounded on every axis and read-only: two levels, a capped number of entries per level, no
     * symlink following, and nothing below an instance root is walked beyond what the
     * registration's sibling probe looks at.</p>
     */
    static List<Candidate> findSiblingInstances(Registration registration, Path gameDir) {
        List<Candidate> found = new ArrayList<>();
        Path self;
        try {
            self = gameDir.toAbsolutePath().normalize();
        } catch (Exception e) {
            return List.of();
        }
        Path level = self.getParent();
        for (int depth = 0; depth < SIBLING_LEVELS && level != null; depth++, level = level.getParent()) {
            found.addAll(siblingsIn(registration, level, self));
        }
        found.sort(Comparator.comparing(c -> c.path().toString()));
        return found;
    }

    private static List<Candidate> siblingsIn(Registration registration, Path parent, Path self) {
        List<Candidate> found = new ArrayList<>();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return found;
        int examined = 0;
        try (var children = Files.list(parent)) {
            for (Path child : children.toList()) {
                if (++examined > MAX_SIBLINGS_PER_LEVEL) break;
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                Path candidate = child.toAbsolutePath().normalize();
                if (candidate.equals(self)) continue;
                Optional<String> what = registration.dataHeldBy(candidate);
                what.ifPresent(w -> found.add(new Candidate(Kind.SIBLING_INSTANCE, candidate, w)));
            }
        } catch (IOException | SecurityException e) {
            LOGGER.debug("[DungeonBackup] Recovery: couldn't list {}: {}", parent, e.toString());
        }
        return found;
    }

    /**
     * Restore {@code candidate} into the registration's roots.
     *
     * <p>Additive in both directions: an archive restore skips entries whose file already exists —
     * or, for paths the registration names in {@code mergeOnRestore}, merges them with the
     * consumer's {@link games.brennan.dungeonbackup.api.RestoreMerger} — and a sibling restore copies rather than moves, so the instance it came from is left exactly
     * as it was. Nothing here can make the player worse off than before they pressed the button.
     * The registration's {@code onRestored} hook is <em>not</em> run here — callers that want
     * caches reloaded run it after this returns.</p>
     *
     * @return the number of files written
     */
    public static int restore(Registration registration, Candidate candidate) throws IOException {
        return switch (candidate.kind()) {
            // Both are archives this install wrote, in the same format — only the folder differs.
            case EXTERNAL_BACKUP, BACKUP ->
                BackupArchiver.restore(candidate.path(), registration.restoreTargets(),
                    registration::mergerFor);
            case SIBLING_INSTANCE -> restoreFromInstance(registration, candidate.path());
        };
    }

    /**
     * Copy this mod's data out of another instance folder, from whichever layout it is in: the
     * whole {@code <rootLabel>/} tree at the instance root (minus the excluded folders — no point
     * copying their backups), then every relocation's pre-move location under its {@code config/}.
     * Existing files win, so this can only ever add.
     */
    private static int restoreFromInstance(Registration registration, Path instanceRoot) throws IOException {
        int written = 0;
        Path otherData = instanceRoot.resolve(registration.rootLabel());
        Path otherConfig = instanceRoot.resolve("config");
        if (Files.isDirectory(otherData)) {
            written += FileTrees.copyTree(otherData, registration.dataRoot(),
                registration.excludeTopLevel(),
                relative -> registration.mergerFor(registration.rootLabel(), relative)).copied();
        }
        for (Relocation relocation : registration.relocations()) {
            if (relocation.kind() != games.brennan.dungeonbackup.api.Kind.DIRECTORY) continue;
            Path from = relocation.legacyPath(otherConfig);
            if (!Files.isDirectory(from)) continue;
            Path to = relocation.newPath(registration.dataRoot());
            String prefix = registration.dataRoot().relativize(to).toString().replace('\\', '/') + "/";
            written += FileTrees.copyTree(from, to, java.util.Set.of(),
                relative -> registration.mergerFor(registration.rootLabel(), prefix + relative)).copied();
        }
        LOGGER.info("[DungeonBackup] {}: copied {} file(s) from {}", registration.modId(), written, instanceRoot);
        return written;
    }
}
