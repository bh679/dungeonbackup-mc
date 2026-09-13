package games.brennan.dungeonbackup.api;

import games.brennan.dungeonbackup.core.BackupMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The operator override: this mod's names first, then each consumer's legacy names — so a server
 * operator who set {@code -Ddungeontrain.backups=off} before the extraction still has it honoured.
 */
class BackupSettingsOverrideTest {

    @TempDir
    Path tmp;

    private Registration dt() {
        return Registration.builder("dungeontrain").dataRoot(tmp)
            .legacyOverrideNames("dungeontrain.backups", "DUNGEONTRAIN_BACKUPS").build();
    }

    @Test
    void nothingSetMeansNoOverride() {
        assertNull(BackupSettings.resolveOverride(Map.<String, String>of()::get, Map.<String, String>of()::get, List.of(dt())));
    }

    @Test
    void thisModsNamesWinOverLegacyNames() {
        BackupSettings.Resolved r = BackupSettings.resolveOverride(
            Map.of("dungeonbackup.backups", "instanced", "dungeontrain.backups", "off")::get,
            Map.<String, String>of()::get, List.of(dt()));
        assertEquals(BackupMode.INSTANCE, r.mode());
        assertEquals("dungeonbackup.backups", r.source());
    }

    @Test
    void aLegacyNameStillWorksAndIsNamedAsTheSource() {
        BackupSettings.Resolved r = BackupSettings.resolveOverride(Map.<String, String>of()::get,
            Map.of("DUNGEONTRAIN_BACKUPS", "off")::get, List.of(dt()));
        assertEquals(BackupMode.OFF, r.mode());
        assertEquals("DUNGEONTRAIN_BACKUPS", r.source(), "tell the operator which name to change back");
    }

    @Test
    void thePropertyWinsOverTheEnvironmentWithinAPair() {
        BackupSettings.Resolved r = BackupSettings.resolveOverride(
            Map.of("dungeonbackup.backups", "  ")::get,          // blank is not a value
            Map.of("DUNGEONBACKUP_BACKUPS", "on")::get, List.of(dt()));
        assertEquals(BackupMode.EXTERNAL, r.mode());
        assertEquals("DUNGEONBACKUP_BACKUPS", r.source());
    }

    @Test
    void aTypoFallsBackToTheDefaultNeverOff() {
        BackupSettings.Resolved r = BackupSettings.resolveOverride(
            Map.of("dungeonbackup.backups", "offf")::get, Map.<String, String>of()::get, List.of());
        assertEquals(BackupMode.DEFAULT, r.mode());
    }
}
