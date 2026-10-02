package games.brennan.dungeonbackup.api;

import games.brennan.dungeonbackup.TestRegistrations;
import games.brennan.dungeonbackup.core.BackupArchiver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registration contract: what a consumer describes, and what is derived from it. */
class RegistrationTest {

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        DungeonBackup.clearRegistrations();
    }

    @Test
    void aDataRootIsRequiredAndBackupsIsAlwaysExcluded() {
        assertThrows(IllegalStateException.class, () -> Registration.builder("x").build());
        assertThrows(IllegalArgumentException.class, () -> Registration.builder("Not-Valid"));
        Registration r = Registration.builder("x").dataRoot(tmp).excludeTopLevel("cache").build();
        assertEquals(Set.of("cache", Registration.BACKUPS_DIR), r.excludeTopLevel());
        assertEquals(tmp.resolve("backups"), r.backupsRoot());
    }

    @Test
    void sourcesAreTheRootThenEveryExtraRootInOrder() {
        Registration r = TestRegistrations.dungeonTrainLike(tmp).build();
        List<Source> sources = r.sources();
        assertEquals(2, sources.size());
        assertEquals("dungeontrain", sources.get(0).label());
        assertTrue(sources.get(0).excludeTopLevel().contains("backups"));
        assertEquals("dtpacks", sources.get(1).label());
        assertEquals(tmp.resolve("dtpacks"), sources.get(1).dir());
    }

    @Test
    void duplicateLabelsAreRejected() {
        assertThrows(IllegalStateException.class, () -> Registration.builder("x").dataRoot(tmp)
            .rootLabel("same").alsoBackUp("same", tmp.resolve("other")).build());
    }

    @Test
    void aNewSubfolderIsArchivedWithNoCodeChange() throws IOException {
        // The whole point: nothing enumerates the root's contents.
        Registration r = Registration.builder("x").dataRoot(tmp.resolve("x")).build();
        Files.createDirectories(tmp.resolve("x/brand-new-store/deep"));
        Files.writeString(tmp.resolve("x/brand-new-store/deep/file.json"), "{}");
        Files.writeString(tmp.resolve("x/loose.json"), "{}");

        BackupArchiver.Result result = BackupArchiver.create(r.backupsRoot(), r.modId(), r.sources(), "t", "1");

        List<String> entries = BackupArchiver.listEntries(result.archive().orElseThrow());
        assertTrue(entries.contains("x/brand-new-store/deep/file.json"));
        assertTrue(entries.contains("x/loose.json"));
    }

    @Test
    void twoModsInOneFolderDoNotSeeEachOther() throws IOException {
        Path shared = tmp.resolve("shared");
        Registration a = Registration.builder("moda").dataRoot(tmp.resolve("a")).build();
        Registration b = Registration.builder("modb").dataRoot(tmp.resolve("b")).build();
        Files.createDirectories(tmp.resolve("a")); Files.writeString(tmp.resolve("a/f"), "1");
        Files.createDirectories(tmp.resolve("b")); Files.writeString(tmp.resolve("b/f"), "2");
        BackupArchiver.create(shared, a.modId(), a.sources(), "t", "1");
        BackupArchiver.create(shared, b.modId(), b.sources(), "t", "1");

        assertEquals(1, BackupArchiver.listArchives(shared, "moda").size());
        assertEquals(1, BackupArchiver.listArchives(shared, "modb").size());
        BackupArchiver.clear(shared, "moda");
        assertEquals(0, BackupArchiver.listArchives(shared, "moda").size());
        assertEquals(1, BackupArchiver.listArchives(shared, "modb").size(), "clearing one mod leaves the other");
    }

    @Test
    void registerReplacesAnEarlierEntryForTheSameMod() {
        Registration.builder("x").dataRoot(tmp.resolve("one")).register();
        Registration.builder("x").dataRoot(tmp.resolve("two")).register();
        assertEquals(1, DungeonBackup.registrations().size());
        assertEquals(tmp.resolve("two"), DungeonBackup.registration("x").orElseThrow().dataRoot());
    }

    @Test
    void probesThatThrowFailSafe() {
        Registration r = Registration.builder("x").dataRoot(tmp)
            .livenessProbe(p -> { throw new IllegalStateException("boom"); })
            .siblingProbe(p -> { throw new IllegalStateException("boom"); })
            .modVersion(() -> { throw new IllegalStateException("boom"); })
            .build();
        assertTrue(r.hasLiveData(tmp), "a broken probe must never make an install look emptied");
        assertTrue(r.dataHeldBy(tmp).isEmpty());
        assertEquals("", r.modVersion());
    }

    @Test
    void relocationsResolveUnderTheirRoot() {
        Relocation r = new Relocation("legacy/nested", "new/place", Kind.DIRECTORY);
        assertEquals(tmp.resolve("legacy").resolve("nested"), r.legacyPath(tmp));
        assertEquals(tmp.resolve("new").resolve("place"), r.newPath(tmp));
        assertThrows(IllegalArgumentException.class, () -> new Relocation("", "x", Kind.FILE));
    }

    @Test
    void locatedPrefersTheNewPathAndFallsBackToLegacy() throws IOException {
        Path preferred = tmp.resolve("new/file");
        Path legacy = tmp.resolve("old/file");
        Located located = new Located(preferred, legacy);
        assertEquals(preferred, located.read(), "neither exists: write where reads go");
        Files.createDirectories(legacy.getParent()); Files.writeString(legacy, "old");
        assertEquals(legacy, located.read());
        Files.createDirectories(preferred.getParent()); Files.writeString(preferred, "new");
        assertEquals(preferred, located.read());
        assertEquals(List.of(preferred, legacy), located.all());
    }

    @Test
    @SuppressWarnings("deprecation")
    void commandAliasesAreAcceptedAndIgnored() {
        // Older consumer jars still call commandAliases; the library registers no commands any
        // more — backups and restores are never run by a player — so the names go nowhere.
        Registration r = Registration.builder("x").dataRoot(tmp).commandAliases("xbackup", "xrestore").build();

        assertNull(r.backupCommandAlias());
        assertNull(r.restoreCommandAlias());
    }
}
