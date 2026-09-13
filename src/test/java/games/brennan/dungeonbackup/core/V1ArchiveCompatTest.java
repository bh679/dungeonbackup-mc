package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.TestRegistrations;
import games.brennan.dungeonbackup.api.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Archives Dungeon Train wrote before this library existed — schema 1, no {@code modId} — must
 * still be listed, restored, pruned and read. Players have them on disk right now, in both roots.
 */
class V1ArchiveCompatTest {

    @TempDir
    Path tmp;

    /** Byte-for-byte the manifest shape PlayerDataBackup wrote, minus nothing. */
    private static String v1Manifest(String digest) {
        return "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"modVersion\": \"0.842.0\",\n"
            + "  \"createdAt\": \"2026-08-20T10:11:12\",\n"
            + "  \"reason\": \"world-load\",\n"
            + "  \"digest\": \"" + digest + "\",\n"
            + "  \"fileCount\": 3,\n"
            + "  \"totalBytes\": 42\n"
            + "}\n";
    }

    private static Path writeV1(Path dir, String name, Map<String, String> entries, String digest) throws IOException {
        Files.createDirectories(dir);
        Path zip = dir.resolve(name);
        try (OutputStream raw = Files.newOutputStream(zip);
             ZipOutputStream out = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(v1Manifest(digest).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (var e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private Registration reg() {
        return TestRegistrations.dungeonTrainLike(tmp.resolve("instance")).build();
    }

    @Test
    void aV1ArchiveIsListedRestoredAndRead() throws IOException {
        Registration r = reg();
        Path archive = writeV1(r.backupsRoot(), "dungeontrain-backup-20260820-101112-v0.842.0.zip", Map.of(
            "dungeontrain/user/carriages/mine.nbt", "carriage",
            "dungeontrain/achievements/uuid.json", "granted",
            "dtpacks/My Pack/carriages/packed.nbt", "packed"), "abc");

        assertEquals(List.of(archive), BackupArchiver.listArchives(r.backupsRoot(), r.modId()));
        assertEquals("0.842.0", BackupArchiver.manifestVersionOf(archive).orElseThrow());
        assertEquals(1, BackupArchiver.manifestSchemaOf(archive).orElseThrow());
        assertTrue(BackupArchiver.manifestModIdOf(archive).isEmpty(), "v1 carries no modId");
        assertEquals("carriage", new String(
            BackupArchiver.readEntry(archive, "dungeontrain/user/carriages/mine.nbt").orElseThrow()));

        int written = BackupArchiver.restore(archive, r.restoreTargets());

        assertEquals(3, written);
        assertEquals("carriage", Files.readString(r.dataRoot().resolve("user/carriages/mine.nbt")));
        assertEquals("packed", Files.readString(tmp.resolve("instance/dtpacks/My Pack/carriages/packed.nbt")));
    }

    @Test
    void theRecoveryScanFindsV1ArchivesInBothRoots() throws IOException {
        Registration r = reg();
        Path external = tmp.resolve("external");
        writeV1(r.backupsRoot(), "dungeontrain-backup-20260820-101112-v0.842.0.zip", Map.of("dungeontrain/user/a.nbt", "x"), "a");
        writeV1(external, "dungeontrain-backup-20260819-101112-v0.841.0.zip", Map.of("dungeontrain/user/a.nbt", "x"), "b");

        assertTrue(DataRecovery.looksEmptied(r, tmp.resolve("instance/config")));
        List<DataRecovery.Candidate> found = DataRecovery.findCandidates(r, tmp.resolve("instance"), external);

        assertEquals(2, found.size());
        assertEquals(DataRecovery.Kind.EXTERNAL_BACKUP, found.get(0).kind());
        assertEquals(DataRecovery.Kind.BACKUP, found.get(1).kind());
        assertEquals(1, DataRecovery.restore(r, found.get(0)));
    }

    @Test
    void aNewArchiveSkipsWhenItsDigestMatchesTheNewestV1() throws IOException {
        // The digest check reads the manifest field by name; a v1 manifest has it in the same place.
        Registration r = reg();
        Files.createDirectories(r.dataRoot().resolve("user"));
        Files.writeString(r.dataRoot().resolve("user/a.nbt"), "x");
        BackupArchiver.Result first = BackupArchiver.create(r.backupsRoot(), r.modId(), r.sources(), "t", "0.861.0");
        String digest = first.digest();
        Files.delete(first.archive().orElseThrow());
        writeV1(r.backupsRoot(), "dungeontrain-backup-20260820-101112-v0.842.0.zip", Map.of("dungeontrain/user/a.nbt", "x"), digest);

        BackupArchiver.Result second = BackupArchiver.create(r.backupsRoot(), r.modId(), r.sources(), "t", "0.861.0");

        assertFalse(second.wrote(), "unchanged since the v1 archive — no new zip");
    }

    @Test
    void v1AndV2ArchivesPruneTogetherByVersion() throws IOException {
        Registration r = reg();
        for (int i = 1; i <= 4; i++) {
            writeV1(r.backupsRoot(), "dungeontrain-backup-2026082" + i + "-000000-v0.842.0.zip", Map.of("dungeontrain/u/a", "x"), "d" + i);
        }
        Files.createDirectories(r.dataRoot().resolve("user"));
        Files.writeString(r.dataRoot().resolve("user/new.nbt"), "y");
        BackupArchiver.Result v2 = BackupArchiver.create(r.backupsRoot(), r.modId(), r.sources(), "t", "0.861.0", 2);

        List<Path> left = BackupArchiver.listArchives(r.backupsRoot(), r.modId());
        assertEquals(v2.archive().orElseThrow(), left.get(0), "the v2 archive is newest");
        // Two of 0.842.0 kept + the exempt oldest + the one 0.861.0.
        assertEquals(4, left.size());
        assertEquals(2, BackupArchiver.manifestSchemaOf(left.get(0)).orElseThrow());
        assertEquals("dungeontrain", BackupArchiver.manifestModIdOf(left.get(0)).orElseThrow());
    }
}
