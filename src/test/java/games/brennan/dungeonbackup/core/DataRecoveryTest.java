package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.TestRegistrations;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loss signature and the candidate scan, driven through a Dungeon-Train-shaped registration.
 *
 * <p>The signature has to be narrow: "this install has no data" is also what a brand-new install
 * looks like, and a recovery prompt on someone's first launch would be nonsense. Most of these
 * tests are about the cases where it must stay quiet. Ported from Dungeon Train's
 * PlayerDataRecoveryTest.</p>
 */
class DataRecoveryTest {

    @TempDir
    Path tmp;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path instance() { return tmp.resolve("instance"); }

    private Registration reg() { return TestRegistrations.dungeonTrainLike(instance()).build(); }

    private Path dataRoot() { return instance().resolve("dungeontrain"); }

    private Path configDir() { return instance().resolve("config"); }

    /** A temp stand-in for the OS app-data folder, so tests never see this machine's real backups. */
    private Path externalRoot() { return tmp.resolve("external-backups"); }

    private Path dtpacksRoot() { return instance().resolve("dtpacks"); }

    @Test
    void aFreshInstallLooksEmptiedButOffersNothing() {
        assertTrue(DataRecovery.looksEmptied(reg(), configDir()));
        assertEquals(List.of(), DataRecovery.findCandidates(reg(), instance(), externalRoot()),
            "nothing to restore from means no prompt, however empty the install is");
    }

    @Test
    void anInstallWithBuildsIsNotEmptied() throws IOException {
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        assertFalse(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void anInstallWithAdvancementsIsNotEmptied() throws IOException {
        write(dataRoot().resolve("achievements/uuid.json"), "granted");
        assertFalse(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void dataStillWaitingInConfigIsNotALoss() throws IOException {
        // The migration is about to move this across on the very same launch.
        write(configDir().resolve("dungeontrain/user/templates/a.nbt"), "carriage");
        assertFalse(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void savedPackagesAreNotALoss() throws IOException {
        // dtpacks/ has always lived outside config/, so a pack update never touched it. A player
        // whose builds are all in a saved package still has them — the consumer's probe says so.
        write(dtpacksRoot().resolve("My Pack/templates/a.nbt"), "carriage");
        assertFalse(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void theDtpacksReadmeIsNotASavedPackage() throws IOException {
        write(dtpacksRoot().resolve("README.txt"), "Dungeon Train packages folder.");
        assertFalse(TestRegistrations.hasSavedPackages(dtpacksRoot()));
        assertTrue(DataRecovery.looksEmptied(reg(), configDir()));
    }

    @Test
    void aDefaultProbeSeesAnyFileOutsideBackups() throws IOException {
        // Without a consumer probe the whole root counts, except the archives folder — otherwise
        // an install that lost everything but kept its backups would never be offered them.
        Registration plain = Registration.builder("plainmod").dataRoot(tmp.resolve("plainmod")).build();
        assertTrue(DataRecovery.looksEmptied(plain, configDir()));
        write(tmp.resolve("plainmod/backups/plainmod-backup-20260101-000000.zip"), "z");
        assertTrue(DataRecovery.looksEmptied(plain, configDir()), "backups alone are not live data");
        write(tmp.resolve("plainmod/anything/at/all.json"), "{}");
        assertFalse(DataRecovery.looksEmptied(plain, configDir()));
    }

    @Test
    void restoreTargetsMatchTheLabelsBackupsAreWrittenWith() {
        // Both come from the registration, so they cannot drift — if they could, a restore would
        // silently write nothing: every archive entry's label fails to match and is skipped.
        List<Source> targets = reg().restoreTargets();
        assertEquals(reg().sources(), targets);
        assertEquals(List.of("dungeontrain", "dtpacks"), targets.stream().map(Source::label).toList());
        assertNotNull(BackupArchiver.targetFor("dungeontrain/user/templates/a.nbt", targets));
        assertNotNull(BackupArchiver.targetFor("dtpacks/My Pack/templates/a.nbt", targets));
    }

    @Test
    void ranksAnOutOfInstanceBackupAboveEverythingElse() throws IOException {
        // The only candidate that still exists when the instance itself was deleted, so it leads.
        write(externalRoot().resolve("dungeontrain-backup-20260101-000000.zip"), "z");
        write(dataRoot().resolve("backups/dungeontrain-backup-20260102-000000.zip"), "z");
        write(tmp.resolve("instance-old/config/dungeontrain/user/a.nbt"), "carriage");

        List<DataRecovery.Candidate> found = DataRecovery.findCandidates(reg(), instance(), externalRoot());

        assertEquals(3, found.size());
        assertEquals(DataRecovery.Kind.EXTERNAL_BACKUP, found.get(0).kind());
        assertEquals(DataRecovery.Kind.BACKUP, found.get(1).kind());
        assertEquals(DataRecovery.Kind.SIBLING_INSTANCE, found.get(2).kind());
    }

    @Test
    void anotherModsArchivesAreInvisible() throws IOException {
        // Two consumers can share an external folder; each sees only its own prefix.
        write(externalRoot().resolve("othermod-backup-20260101-000000.zip"), "z");
        write(dataRoot().resolve("backups/othermod-backup-20260102-000000.zip"), "z");
        assertEquals(List.of(), DataRecovery.findCandidates(reg(), instance(), externalRoot()));
    }

    @Test
    void candidateExposesItsDisplayFormsAsStrings() {
        // Component.translatable throws at RENDER time on a non-String argument, so a Path passed
        // straight in compiles and then crashes the screen. These accessors are what the screen uses.
        DataRecovery.Candidate c = new DataRecovery.Candidate(DataRecovery.Kind.EXTERNAL_BACKUP,
            Path.of("/tmp/DungeonTrain/backups/dungeontrain-backup-20260101-000000.zip"), "x");
        assertEquals("dungeontrain-backup-20260101-000000.zip", c.fileName());
        assertEquals("/tmp/DungeonTrain/backups", c.folder());
        assertEquals("/tmp/DungeonTrain/backups/dungeontrain-backup-20260101-000000.zip", c.location());
        DataRecovery.Candidate root = new DataRecovery.Candidate(DataRecovery.Kind.SIBLING_INSTANCE, Path.of("/"), "y");
        assertEquals("", root.folder());
    }

    @Test
    void findsThisInstallsOwnBackups() throws IOException {
        write(dataRoot().resolve("backups/dungeontrain-backup-20260101-000000.zip"), "z");
        List<DataRecovery.Candidate> found = DataRecovery.findCandidates(reg(), instance(), externalRoot());
        assertEquals(1, found.size());
        assertEquals(DataRecovery.Kind.BACKUP, found.get(0).kind());
    }

    @Test
    void findsASiblingInstanceInEitherLayout() throws IOException {
        write(tmp.resolve("instance-old/config/dungeontrain/user/templates/a.nbt"), "carriage");
        write(tmp.resolve("instance-old/config/dungeontrain-achievements/uuid.json"), "granted");
        write(tmp.resolve("instance-new/dungeontrain/user/templates/b.nbt"), "carriage");

        List<DataRecovery.Candidate> found = DataRecovery.findSiblingInstances(reg(), instance());

        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(c -> c.kind() == DataRecovery.Kind.SIBLING_INSTANCE));
        // Sorted by path, so the listing is stable across launches rather than filesystem order.
        assertEquals("builds", found.get(0).description(), "instance-new, on the current layout");
        assertEquals("builds and progress", found.get(1).description(),
            "instance-old, still on the pre-relocation config/ layout");
    }

    @Test
    void theDefaultSiblingProbeChecksBothLayouts() throws IOException {
        Registration plain = Registration.builder("plainmod")
            .dataRoot(instance().resolve("plainmod"))
            .relocate("plainmod-progress", "progress", games.brennan.dungeonbackup.api.Kind.DIRECTORY)
            .build();
        write(tmp.resolve("instance-old/config/plainmod-progress/uuid.json"), "granted");
        write(tmp.resolve("instance-new/plainmod/progress/uuid.json"), "granted");
        write(tmp.resolve("instance-none/plainmod/backups/plainmod-backup-20260101-000000.zip"), "z");

        List<DataRecovery.Candidate> found = DataRecovery.findSiblingInstances(plain, instance());

        assertEquals(2, found.size(), "backups alone do not make a sibling a candidate");
    }

    @Test
    void ranksBackupsAheadOfSiblings() throws IOException {
        write(dataRoot().resolve("backups/dungeontrain-backup-20260101-000000.zip"), "z");
        write(tmp.resolve("instance-old/config/dungeontrain/user/a.nbt"), "carriage");
        List<DataRecovery.Candidate> found = DataRecovery.findCandidates(reg(), instance(), externalRoot());
        assertEquals(2, found.size());
        assertEquals(DataRecovery.Kind.BACKUP, found.get(0).kind(),
            "our own archive has certain provenance; the folder next door is a guess");
    }

    @Test
    void ignoresAnInstanceWithNoData() throws IOException {
        write(tmp.resolve("some-other-game/config/whatever.toml"), "x");
        assertTrue(reg().dataHeldBy(tmp.resolve("some-other-game")).isEmpty());
        assertEquals(List.of(), DataRecovery.findSiblingInstances(reg(), instance()));
    }

    @Test
    void neverOffersTheInstanceItIsRunningIn() throws IOException {
        write(dataRoot().resolve("user/templates/a.nbt"), "carriage");
        assertFalse(DataRecovery.findSiblingInstances(reg(), instance()).stream()
            .anyMatch(c -> c.path().equals(instance().toAbsolutePath().normalize())));
    }

    @Test
    void restoringFromASiblingCopiesRatherThanMoves() throws IOException {
        Path sibling = tmp.resolve("instance-old");
        write(sibling.resolve("config/dungeontrain/user/templates/a.nbt"), "carriage");
        write(sibling.resolve("config/dungeontrain-achievements/uuid.json"), "granted");
        DataRecovery.Candidate candidate = new DataRecovery.Candidate(
            DataRecovery.Kind.SIBLING_INSTANCE, sibling, "builds and progress");

        int written = DataRecovery.restore(reg(), candidate);

        assertEquals(2, written);
        assertEquals("carriage", Files.readString(dataRoot().resolve("user/templates/a.nbt")));
        assertEquals("granted", Files.readString(dataRoot().resolve("achievements/uuid.json")));
        assertTrue(Files.isRegularFile(sibling.resolve("config/dungeontrain/user/templates/a.nbt")),
            "the instance we recovered from must be left exactly as it was");
    }

    @Test
    void restoringFromANewLayoutSiblingTakesTheWholeRootButNotItsBackups() throws IOException {
        Path sibling = tmp.resolve("instance-new");
        write(sibling.resolve("dungeontrain/user/templates/a.nbt"), "carriage");
        write(sibling.resolve("dungeontrain/outbox/relay-outbox.json"), "[]");
        write(sibling.resolve("dungeontrain/backups/dungeontrain-backup-20260101-000000.zip"), "z");
        DataRecovery.Candidate candidate = new DataRecovery.Candidate(
            DataRecovery.Kind.SIBLING_INSTANCE, sibling, "builds");

        int written = DataRecovery.restore(reg(), candidate);

        assertEquals(2, written, "loose files under the root come too; the sibling's archives do not");
        assertTrue(Files.isRegularFile(dataRoot().resolve("outbox/relay-outbox.json")));
        assertFalse(Files.exists(dataRoot().resolve("backups")));
    }

    @Test
    void restoringNeverOverwritesWhatIsAlreadyThere() throws IOException {
        Path sibling = tmp.resolve("instance-old");
        write(sibling.resolve("config/dungeontrain/user/templates/a.nbt"), "old");
        write(dataRoot().resolve("user/templates/a.nbt"), "MINE");
        DataRecovery.Candidate candidate = new DataRecovery.Candidate(
            DataRecovery.Kind.SIBLING_INSTANCE, sibling, "builds");

        DataRecovery.restore(reg(), candidate);

        assertEquals("MINE", Files.readString(dataRoot().resolve("user/templates/a.nbt")));
    }
}
