package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.TestRegistrations;
import games.brennan.dungeonbackup.api.Kind;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Relocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration that moves player data out of {@code config/}.
 *
 * <p>What these assert is the promise the class makes: it can move everything across, and it
 * cannot lose anything on the way. Every entry point takes explicit roots for exactly this reason
 * — anything reaching {@code FMLPaths} can't be bootstrapped in a unit test. Ported from Dungeon
 * Train's PlayerDataMigrationTest against its relocation table.</p>
 */
class DataMigrationTest {

    @TempDir
    Path tmp;

    private Registration reg;
    private List<Relocation> relocations;

    @BeforeEach
    void setUp() {
        DataMigration.resetSession();
        reg = TestRegistrations.dungeonTrainLike(tmp).build();
        relocations = reg.relocations();
    }

    private Path configDir() throws IOException {
        return Files.createDirectories(tmp.resolve("config"));
    }

    private Path dataRoot() {
        return tmp.resolve("dungeontrain");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void movesEveryRelocatedEntry() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        for (Relocation relocation : relocations) {
            Path legacy = relocation.legacyPath(config);
            write(relocation.kind() == Kind.FILE ? legacy : legacy.resolve("a.dat"), relocation.newRelative());
        }

        DataMigration.Result result = DataMigration.migrate(relocations, config, data);

        assertEquals(relocations.size(), result.movedFiles());
        assertTrue(result.clean());
        for (Relocation relocation : relocations) {
            Path moved = relocation.kind() == Kind.FILE
                ? relocation.newPath(data)
                : relocation.newPath(data).resolve("a.dat");
            assertTrue(Files.isRegularFile(moved), "not migrated: " + relocation.newRelative());
            assertEquals(relocation.newRelative(), Files.readString(moved));
            assertFalse(Files.exists(relocation.legacyPath(config)),
                "left behind in config/: " + relocation.legacyRelative());
        }
    }

    @Test
    void nestedBuildDirectoriesKeepTheirLayout() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain/user/parts/cab/front.nbt"), "cab");
        write(config.resolve("dungeontrain/user/pillars/top/a.nbt"), "pillar");

        DataMigration.migrate(relocations, config, data);

        assertEquals("cab", Files.readString(data.resolve("user/parts/cab/front.nbt")));
        assertEquals("pillar", Files.readString(data.resolve("user/pillars/top/a.nbt")));
    }

    @Test
    void isIdempotent() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain-achievements/uuid.json"), "granted");

        assertEquals(1, DataMigration.migrate(relocations, config, data).movedFiles());
        DataMigration.Result second = DataMigration.migrate(relocations, config, data);

        assertEquals(0, second.movedFiles());
        assertEquals(0, second.skippedExisting());
        assertEquals("granted", Files.readString(data.resolve("achievements/uuid.json")));
    }

    @Test
    void neverOverwritesContentAlreadyAtTheDestination() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain-stats/uuid.json"), "old");
        write(data.resolve("stats/uuid.json"), "new");

        DataMigration.Result result = DataMigration.migrate(relocations, config, data);

        assertEquals(0, result.movedFiles());
        assertEquals(1, result.skippedExisting());
        assertEquals("new", Files.readString(data.resolve("stats/uuid.json")), "the destination file must win");
        assertEquals("old", Files.readString(config.resolve("dungeontrain-stats/uuid.json")),
            "the source must be left in place, never deleted");
    }

    @Test
    void leavesEverythingNotInTheTableAlone() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        // Dungeon Train's integrity-governed configs live here deliberately; only the table moves.
        write(config.resolve("adventureitemstats.properties"), "x");
        write(config.resolve("dungeontrain-server.toml"), "x");
        write(config.resolve("dungeontrain/cheat-mods.json"), "x");

        DataMigration.migrate(relocations, config, data);

        assertTrue(Files.isRegularFile(config.resolve("adventureitemstats.properties")));
        assertTrue(Files.isRegularFile(config.resolve("dungeontrain-server.toml")));
        assertTrue(Files.isRegularFile(config.resolve("dungeontrain/cheat-mods.json")));
    }

    @Test
    void prunesEmptiedDirectoriesButKeepsOnesStillHoldingFiles() throws IOException {
        Path config = configDir();
        Path data = dataRoot();
        write(config.resolve("dungeontrain-narrative/global.json"), "n");
        write(config.resolve("dungeontrain/user/templates/a.nbt"), "t");
        // A stray file the migration doesn't know about must keep its directory alive.
        write(config.resolve("dungeontrain/cheat-mods.json"), "x");

        DataMigration.migrate(relocations, config, data);

        assertFalse(Files.exists(config.resolve("dungeontrain-narrative")));
        assertFalse(Files.exists(config.resolve("dungeontrain/user")));
        assertTrue(Files.isDirectory(config.resolve("dungeontrain")));
    }

    @Test
    void hasLegacyDataSeesFilesAndNotEmptyFolders() throws IOException {
        Path config = configDir();
        assertFalse(DataMigration.hasLegacyData(relocations, config));

        Files.createDirectories(config.resolve("dungeontrain/user/templates"));
        assertFalse(DataMigration.hasLegacyData(relocations, config),
            "an empty folder is what a fresh install looks like");

        write(config.resolve("dungeontrain/user/templates/a.nbt"), "t");
        assertTrue(DataMigration.hasLegacyData(relocations, config));
    }

    @Test
    void runOnceSnapshotsTheLegacyTreeBeforeMovingAndRunsOncePerSession() throws IOException {
        Path config = configDir();
        write(config.resolve("dungeontrain/user/templates/a.nbt"), "t");
        write(config.resolve("dungeontrain-achievements/uuid.json"), "granted");
        boolean[] hookRan = {false};
        Registration r = TestRegistrations.dungeonTrainLike(tmp)
            .beforeMigration(() -> hookRan[0] = true).build();

        DataMigration.runOnce(r, config);

        assertTrue(hookRan[0], "the consumer's own earlier one-shot runs first");
        assertTrue(Files.isRegularFile(dataRoot().resolve("user/templates/a.nbt")));
        List<Path> archives = BackupArchiver.listArchives(r.backupsRoot(), r.modId());
        assertEquals(1, archives.size(), "no backup, no write");
        assertTrue(BackupArchiver.listEntries(archives.get(0)).contains("user/templates/a.nbt"));
        assertTrue(BackupArchiver.listEntries(archives.get(0)).contains("achievements/uuid.json"));

        // A second call in the same session is a no-op even with new legacy files.
        write(config.resolve("dungeontrain-stats/uuid.json"), "s");
        DataMigration.runOnce(r, config);
        assertFalse(Files.exists(dataRoot().resolve("stats/uuid.json")));
    }
}
