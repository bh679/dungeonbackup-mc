package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.api.Source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rolling restore points: what goes in an archive, when one is skipped, and what pruning keeps. Ported from Dungeon Train's PlayerDataBackupTest. */
class BackupArchiverTest {

    /** The archives are named with Dungeon Train's id: that is the on-disk grammar being preserved. */
    static final String MOD = "dungeontrain";

    @TempDir
    Path tmp;

    private Path backups() {
        return tmp.resolve("backups");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Source> sources() {
        return List.of(
            new Source("user", tmp.resolve("data/user")),
            new Source("achievements", tmp.resolve("data/achievements")));
    }

    /** The shape the game actually uses: the whole data root, with backups/ excluded. */
    private List<Source> rootSource() {
        return List.of(new Source(
            "dungeontrain", tmp.resolve("data"), java.util.Set.of("backups")));
    }

    @Test
    void archivesEverySourceUnderItsOwnLabel() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("data/achievements/uuid.json"), "granted");

        BackupArchiver.Result result =
            BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3");

        assertTrue(result.wrote());
        assertEquals(2, result.fileCount());
        try (ZipFile zip = new ZipFile(result.archive().orElseThrow().toFile())) {
            assertNotNull(zip.getEntry("user/templates/a.nbt"));
            assertNotNull(zip.getEntry("achievements/uuid.json"));
            String manifest = new String(
                zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            assertTrue(manifest.contains("\"modVersion\": \"1.2.3\""));
            assertTrue(manifest.contains("\"reason\": \"launch\""));
            assertTrue(manifest.contains(result.digest()));
        }
    }

    @Test
    void archivingTheWholeRootCatchesLooseFilesAndExcludesBackups() throws IOException {
        // The loose files under the root were missed by an earlier per-folder enumeration, so
        // dtpacks-state.json (which package is active) and the queued uploads were in no archive.
        write(tmp.resolve("data/dtpacks-state.json"), "{}");
        write(tmp.resolve("data/outbox/relay-outbox.json"), "[]");
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("data/backups/dungeontrain-backup-20260101-000000.zip"), "old archive");

        BackupArchiver.Result result = BackupArchiver.create(
            tmp.resolve("data/backups"), MOD, rootSource(), "world-load", "1.2.3");

        assertEquals(3, result.fileCount(), "the previous archive must not be inside this one");
        try (ZipFile zip = new ZipFile(result.archive().orElseThrow().toFile())) {
            assertNotNull(zip.getEntry("dungeontrain/dtpacks-state.json"));
            assertNotNull(zip.getEntry("dungeontrain/outbox/relay-outbox.json"));
            assertNotNull(zip.getEntry("dungeontrain/user/templates/a.nbt"));
            assertNull(zip.getEntry(
                "dungeontrain/backups/dungeontrain-backup-20260101-000000.zip"));
        }
    }

    @Test
    void writesNothingWhenThereIsNothingToBackUp() throws IOException {
        BackupArchiver.Result result =
            BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3");

        assertFalse(result.wrote());
        assertFalse(Files.exists(backups()), "an empty install shouldn't even make the folder");
    }

    @Test
    void skipsWhenNothingChangedSinceTheNewestArchive() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        assertTrue(BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3").wrote());

        BackupArchiver.Result second =
            BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3");

        assertFalse(second.wrote(), "an idle launch must not cost a zip");
        assertEquals(1, BackupArchiver.listArchives(backups(), MOD).size());
    }

    @Test
    void archivesAgainOnceTheContentChanges() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3");

        write(tmp.resolve("data/user/templates/b.nbt"), "another carriage");
        BackupArchiver.Result second =
            BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3");

        assertTrue(second.wrote());
        assertEquals(2, second.fileCount());
    }

    /** Name an archive the way create() does, so the tests exercise the real grammar. */
    private static String archiveName(String stamp, String version) {
        return "dungeontrain-backup-" + stamp + (version.isEmpty() ? "" : "-v" + version) + ".zip";
    }

    @Test
    void pruningKeepsTheNewestNOfEachVersion() throws IOException {
        Files.createDirectories(backups());
        for (String version : List.of("0.700.0", "0.701.0")) {
            for (int i = 1; i <= 8; i++) {
                write(backups().resolve(archiveName(String.format("2026030%d-00000%d", 1, i), version)), "z");
            }
        }

        BackupArchiver.prune(backups(), MOD, 3);

        List<Path> left = BackupArchiver.listArchives(backups(), MOD);
        for (String version : List.of("0.700.0", "0.701.0")) {
            List<Path> forVersion = left.stream()
                .filter(p -> BackupArchiver.versionOf(p).equals(version)).toList();
            // Three kept per version, plus the single oldest archive overall, which is exempt.
            assertTrue(forVersion.size() == 3 || forVersion.size() == 4,
                version + " kept " + forVersion.size());
            assertTrue(forVersion.stream().anyMatch(p -> p.getFileName().toString().contains("000008")),
                "the newest of each version must survive");
        }
    }

    @Test
    void versionsAreCountedSeparately() throws IOException {
        Files.createDirectories(backups());
        // One version far over the cap, another well under it. The under one must be untouched.
        for (int i = 1; i <= 6; i++) {
            write(backups().resolve(archiveName("20260301-00000" + i, "0.700.0")), "z");
        }
        write(backups().resolve(archiveName("20260302-000001", "0.701.0")), "z");

        BackupArchiver.prune(backups(), MOD, 2);

        List<Path> left = BackupArchiver.listArchives(backups(), MOD);
        assertEquals(1, left.stream().filter(p -> BackupArchiver.versionOf(p).equals("0.701.0")).count(),
            "a version under the cap must not lose anything to a noisy neighbour");
    }

    @Test
    void archivesFromBeforeVersionedNamesFormTheirOwnGroup() throws IOException {
        Files.createDirectories(backups());
        for (int i = 1; i <= 4; i++) {
            write(backups().resolve(archiveName("20260301-00000" + i, "")), "z");
        }
        write(backups().resolve(archiveName("20260302-000001", "0.701.0")), "z");

        BackupArchiver.prune(backups(), MOD, 2);

        List<Path> left = BackupArchiver.listArchives(backups(), MOD);
        assertEquals("", BackupArchiver.versionOf(
            backups().resolve(archiveName("20260301-000001", ""))));
        // 2 legacy + the exempt oldest + 1 versioned.
        assertEquals(4, left.size());
    }

    @Test
    void theOldestArchiveSurvivesTheCap() throws IOException {
        Files.createDirectories(backups());
        for (int i = 1; i <= 5; i++) {
            write(backups().resolve(archiveName("20260301-00000" + i, "0.700.0")), "z");
        }

        BackupArchiver.prune(backups(), MOD, 1);

        List<Path> left = BackupArchiver.listArchives(backups(), MOD);
        assertEquals(2, left.size(), "the newest under the cap, plus the exempt oldest");
        assertTrue(left.stream().anyMatch(p -> p.getFileName().toString().contains("000001")),
            "the pre-migration snapshot is never dropped");
    }

    @Test
    void orderingSurvivesAVersionRollingOverTen() throws IOException {
        Files.createDirectories(backups());
        // 0.10.0 sorts BEFORE 0.9.0 lexicographically. If the version were a filename PREFIX, or if
        // sorting used the whole name, the newer archive would be treated as the older one.
        Path older = backups().resolve(archiveName("20260301-000001", "0.9.0"));
        Path newer = backups().resolve(archiveName("20260302-000001", "0.10.0"));
        write(older, "z");
        write(newer, "z");

        List<Path> listed = BackupArchiver.listArchives(backups(), MOD);

        assertEquals(newer, listed.get(0), "newest first, by timestamp not by version string");
        assertEquals(older, listed.get(1));
    }

    @Test
    void clearRemovesEveryArchiveAndReportsTheSpace() throws IOException {
        Files.createDirectories(backups());
        write(backups().resolve(archiveName("20260301-000001", "0.700.0")), "0123456789");
        write(backups().resolve(archiveName("20260301-000002", "0.700.0")), "0123456789");
        // A file that is not a backup must survive: clear() works off the archive naming, so it
        // cannot take something of the player's with it.
        write(backups().resolve("notes.txt"), "keep me");

        BackupArchiver.ClearResult result = BackupArchiver.clear(backups(), MOD);

        assertEquals(2, result.deleted());
        assertEquals(20L, result.bytesFreed());
        assertTrue(result.clean());
        assertEquals(List.of(), BackupArchiver.listArchives(backups(), MOD));
        assertTrue(Files.exists(backups().resolve("notes.txt")));
    }

    @Test
    void clearOnAnAbsentFolderIsANoOp() {
        BackupArchiver.ClearResult result = BackupArchiver.clear(tmp.resolve("nope"), MOD);

        assertEquals(0, result.deleted());
        assertEquals(0L, result.bytesFreed());
        assertTrue(result.clean());
    }

    @Test
    void totalSizeAddsUpEveryArchiveAndIgnoresOtherFiles() throws IOException {
        Files.createDirectories(backups());
        write(backups().resolve(archiveName("20260301-000001", "0.700.0")), "12345");
        write(backups().resolve(archiveName("20260301-000002", "0.700.0")), "12345");
        write(backups().resolve("notes.txt"), "not an archive");

        assertEquals(10L, BackupArchiver.totalSize(backups(), MOD));
        assertEquals(0L, BackupArchiver.totalSize(tmp.resolve("nope"), MOD));
    }

    @Test
    void bytesAreFormattedTheWayAFileManagerWouldShowThem() {
        assertEquals("0 B", BackupArchiver.formatBytes(0));
        assertEquals("512 B", BackupArchiver.formatBytes(512));
        assertEquals("1 KB", BackupArchiver.formatBytes(1024));
        assertEquals("1.0 MB", BackupArchiver.formatBytes(1024L * 1024));
        assertEquals("1.5 MB", BackupArchiver.formatBytes(1024L * 1024 * 3 / 2));
        assertEquals("1.0 GB", BackupArchiver.formatBytes(1024L * 1024 * 1024));
        assertEquals("2.5 GB", BackupArchiver.formatBytes(1024L * 1024 * 1024 * 5 / 2));
    }

    @Test
    void mirrorCopiesTheArchiveOutOfTheInstanceAndPrunesThere() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        Path archive = BackupArchiver.create(backups(), MOD, sources(), "world-load", "1.2.3")
            .archive().orElseThrow();
        Path external = tmp.resolve("external");

        assertTrue(BackupArchiver.mirror(archive, external, MOD, 5));

        Path copy = external.resolve(archive.getFileName().toString());
        assertTrue(Files.isRegularFile(copy));
        assertArrayEquals(Files.readAllBytes(archive), Files.readAllBytes(copy));
        assertTrue(Files.isRegularFile(archive), "the in-instance archive is copied, never moved");
        // Idempotent: a second mirror of the same archive is a no-op, not a duplicate or a failure.
        assertTrue(BackupArchiver.mirror(archive, external, MOD, 5));
        assertEquals(1, BackupArchiver.listArchives(external, MOD).size());
    }

    @Test
    void mirrorFailingLeavesTheInstanceArchiveIntact() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        Path archive = BackupArchiver.create(backups(), MOD, sources(), "world-load", "1.2.3")
            .archive().orElseThrow();
        // A FILE where the external root should be: creating the directory cannot succeed. Stands
        // in for a read-only or forbidden home directory, which must never turn a backup that
        // already succeeded into a failure.
        Path blocked = tmp.resolve("blocked");
        Files.writeString(blocked, "not a directory");

        assertFalse(BackupArchiver.mirror(archive, blocked, MOD, 5));
        assertTrue(Files.isRegularFile(archive));
    }

    @Test
    void restoreOnlyAddsMissingFiles() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("data/achievements/uuid.json"), "granted");
        Path archive = BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3")
            .archive().orElseThrow();

        Path restoreRoot = tmp.resolve("restored");
        write(restoreRoot.resolve("user/templates/a.nbt"), "MINE, newer");
        List<Source> targets = List.of(
            new Source("user", restoreRoot.resolve("user")),
            new Source("achievements", restoreRoot.resolve("achievements")));

        int written = BackupArchiver.restore(archive, targets);

        assertEquals(1, written);
        assertEquals("MINE, newer", Files.readString(restoreRoot.resolve("user/templates/a.nbt")),
            "a restore must never overwrite what the player has now");
        assertEquals("granted", Files.readString(restoreRoot.resolve("achievements/uuid.json")));
    }

    @Test
    void readsOneEntryWithoutRestoringAnything() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "the build");
        write(tmp.resolve("data/achievements/uuid.json"), "granted");
        Path archive = BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3")
            .archive().orElseThrow();
        Files.delete(tmp.resolve("data/user/templates/a.nbt"));

        // The build reconcile reads one build out of an archive and uploads it. Nothing lands on
        // disk: a file missing from the store may be one the player deleted on purpose.
        assertEquals("the build",
            new String(BackupArchiver.readEntry(archive, "user/templates/a.nbt").orElseThrow()));
        assertFalse(Files.exists(tmp.resolve("data/user/templates/a.nbt")),
            "reading an entry must not write it back");
    }

    @Test
    void readEntryIsEmptyForAnythingItCannotRead() throws IOException {
        write(tmp.resolve("data/user/templates/a.nbt"), "the build");
        Path archive = BackupArchiver.create(backups(), MOD, sources(), "launch", "1.2.3")
            .archive().orElseThrow();

        assertTrue(BackupArchiver.readEntry(archive, "user/templates/missing.nbt").isEmpty());
        assertTrue(BackupArchiver.readEntry(tmp.resolve("not-an-archive.zip"), "user/a").isEmpty());
        assertTrue(BackupArchiver.readEntry(archive, "").isEmpty());
        assertEquals("1.2.3", BackupArchiver.manifestVersionOf(archive).orElseThrow());
    }

    @Test
    void restoreRefusesToEscapeItsTargetDirectory() {
        List<Source> targets =
            List.of(new Source("user", tmp.resolve("restored/user")));

        // Archive entry names are player-supplied data — a crafted zip must not write outside.
        assertNull(BackupArchiver.targetFor("user/../../evil.txt", targets));
        assertNull(BackupArchiver.targetFor("unknown-label/a.nbt", targets));
        assertNull(BackupArchiver.targetFor("no-slash", targets));
        assertNotNull(BackupArchiver.targetFor("user/templates/a.nbt", targets));
    }
}
