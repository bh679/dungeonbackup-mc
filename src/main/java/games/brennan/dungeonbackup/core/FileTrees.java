package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.api.RestoreMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Small, never-throwing directory helpers shared by the archiver, migration and recovery.
 *
 * <p>Pure {@code java.nio}; every method takes explicit paths so it is drivable from JUnit with
 * {@code @TempDir}.</p>
 */
public final class FileTrees {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    private FileTrees() {}

    /**
     * Fold {@code backedUp} into the existing {@code target} with {@code merger}. Never throws:
     * a merger failure, an empty result or an unchanged result leaves {@code target} as it was.
     * The write goes through a sibling temp file and a rename, so a crash can't leave half a file.
     *
     * @return true when {@code target} was rewritten
     */
    public static boolean mergeInto(Path target, byte[] backedUp, RestoreMerger merger) {
        Path tmp = target.resolveSibling(target.getFileName() + ".merge.tmp");
        try {
            byte[] live = Files.readAllBytes(target);
            Optional<byte[]> merged = merger.merge(live, backedUp);
            if (merged.isEmpty() || java.util.Arrays.equals(merged.get(), live)) return false;
            Files.write(tmp, merged.get());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonBackup] Couldn't merge into {}: {}", target, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | SecurityException ignored) {
                // Best effort; a stray temp file is harmless and the next merge replaces it.
            }
            return false;
        }
    }

    /** Any regular file anywhere beneath {@code dir}? False for a missing or unreadable dir. */
    public static boolean containsAnyFile(Path dir) {
        return containsAnyFile(dir, Set.of());
    }

    /**
     * As above, ignoring the named direct children of {@code dir} — so "does the data root hold
     * anything" can leave the backups folder out of the answer.
     */
    public static boolean containsAnyFile(Path dir, Set<String> excludeTopLevel) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (var children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (excludeTopLevel.contains(child.getFileName().toString())) continue;
                if (Files.isRegularFile(child)) return true;
                if (Files.isDirectory(child) && containsAnyFile(child, Set.of())) return true;
            }
            return false;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Every regular file under {@code dir}, collected up front so a walk isn't mutated mid-flight. */
    public static List<Path> filesUnder(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return files;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOGGER.warn("[DungeonBackup] Couldn't read {}: {}", file, exc.toString());
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** What {@link #copyTree} did. */
    public record CopyReport(int copied, int skippedExisting, List<String> failures) {
        public boolean clean() { return failures.isEmpty(); }
    }

    /**
     * Copy every file under {@code from} into {@code to}, keeping the layout. <b>Additive</b>: a
     * file that already exists at the destination is left alone, so this can only ever put
     * content back, never replace something the player has since made. A file that fails to copy
     * is recorded and the walk continues.
     */
    public static CopyReport copyTree(Path from, Path to) throws IOException {
        return copyTree(from, to, Set.of());
    }

    /** As above, skipping the named direct children of {@code from}. */
    public static CopyReport copyTree(Path from, Path to, Set<String> excludeTopLevel) throws IOException {
        return copyTree(from, to, excludeTopLevel, relativePath -> Optional.empty());
    }

    /**
     * As above, but a file that already exists is handed to the merger {@code mergers} returns for
     * its {@code /}-separated path under {@code from}, when there is one. A merged file counts as
     * copied; one the merger leaves alone counts as skipped.
     */
    public static CopyReport copyTree(Path from, Path to, Set<String> excludeTopLevel,
                                      java.util.function.Function<String, Optional<RestoreMerger>> mergers)
            throws IOException {
        int copied = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        if (from == null || !Files.isDirectory(from)) return new CopyReport(0, 0, List.of());
        for (Path file : filesUnder(from)) {
            Path relative = from.relativize(file);
            if (relative.getNameCount() > 0 && excludeTopLevel.contains(relative.getName(0).toString())) {
                continue;
            }
            Path target = to.resolve(relative.toString());
            if (Files.exists(target)) {
                Optional<RestoreMerger> merger = mergers.apply(relative.toString().replace('\\', '/'));
                boolean merged = false;
                if (merger.isPresent()) {
                    try {
                        merged = mergeInto(target, Files.readAllBytes(file), merger.get());
                    } catch (IOException | SecurityException e) {
                        LOGGER.warn("[DungeonBackup] Couldn't read {} to merge: {}", file, e.toString());
                    }
                }
                if (merged) copied++; else skipped++;
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
                copied++;
            } catch (IOException | SecurityException e) {
                LOGGER.warn("[DungeonBackup] Couldn't copy {} -> {}: {}", file, target, e.toString());
                failures.add(relative.toString());
            }
        }
        return new CopyReport(copied, skipped, List.copyOf(failures));
    }
}
