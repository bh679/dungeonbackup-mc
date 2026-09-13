package games.brennan.dungeonbackup;

import games.brennan.dungeonbackup.config.BackupClientConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * NeoForge entrypoint. Registers the client config; everything else is event-driven
 * ({@link games.brennan.dungeonbackup.neoforge.BackupEvents}) or client-side
 * ({@link games.brennan.dungeonbackup.client.DataRecoveryPromptHandler}).
 *
 * <p>Consumer mods call {@link games.brennan.dungeonbackup.api.DungeonBackup#register} from their
 * own constructor; this mod is declared {@code ordering="AFTER"} by consumers only so that the
 * registration exists by the time the first server-start event fires — nothing here depends on any
 * consumer.</p>
 */
@Mod(DungeonBackupMod.MOD_ID)
public final class DungeonBackupMod {

    public static final String MOD_ID = "dungeonbackup";

    public DungeonBackupMod(IEventBus modBus, ModContainer modContainer) {
        // Backups are this machine's local files, so the choice is a CLIENT setting. See
        // BackupSettings for why a dedicated server still keeps the default without it.
        modContainer.registerConfig(ModConfig.Type.CLIENT, BackupClientConfig.SPEC,
            "dungeonbackup-client.toml");
        BackupClientConfig.install();
    }
}
