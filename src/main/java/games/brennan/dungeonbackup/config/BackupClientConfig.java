package games.brennan.dungeonbackup.config;

import games.brennan.dungeonbackup.api.BackupSettings;
import games.brennan.dungeonbackup.core.BackupArchiver;
import games.brennan.dungeonbackup.core.BackupMode;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/dungeonbackup-client.toml} — the two backup settings, owned by this mod.
 *
 * <p>Consumers do not re-declare these. Read and written through {@link BackupSettings}, whose
 * not-loaded fallbacks are what a dedicated server sees: resolving an unreadable setting to "no
 * backups" would silently disable the safety net for exactly the installs least able to notice.</p>
 */
public final class BackupClientConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<BackupMode> BACKUP_MODE;
    public static final ModConfigSpec.IntValue BACKUPS_PER_VERSION;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("backups");
        BACKUP_MODE = b
            .comment("Where restore points of your mods' player data are written.",
                     "EXTERNAL keeps a copy outside the Minecraft instance as well as inside it — the only",
                     "setting that survives the instance being deleted or reinstalled. INSTANCE keeps them",
                     "inside the instance only: likely safe from a modpack update, but a pack can overwrite the",
                     "data root too — and certainly lost with the instance itself. OFF disables backups entirely.",
                     "A launch flag -Ddungeonbackup.backups=<mode> (or DUNGEONBACKUP_BACKUPS) overrides this.")
            .defineEnum("backupMode", BackupMode.DEFAULT);
        BACKUPS_PER_VERSION = b
            .comment("How many backup archives to keep for each version of each mod.",
                     "Older archives for that version are removed once there are more than this.",
                     "Lowering it takes effect the next time a backup is written, which will remove",
                     "archives you already have.")
            .defineInRange("backupsPerVersion", BackupArchiver.DEFAULT_PER_VERSION,
                BackupSettings.PER_VERSION_MIN, BackupSettings.PER_VERSION_MAX);
        b.pop();
        SPEC = b.build();
    }

    private BackupClientConfig() {}

    /** Wire the spec into {@link BackupSettings}. Called once from the mod constructor. */
    public static void install() {
        BackupSettings.install(
            () -> SPEC.isLoaded() ? BACKUP_MODE.get() : BackupMode.DEFAULT,
            mode -> {
                if (!SPEC.isLoaded() || BACKUP_MODE.get() == mode) return; // skip a needless TOML write
                BACKUP_MODE.set(mode);
                BACKUP_MODE.save();
            },
            () -> SPEC.isLoaded() ? BACKUPS_PER_VERSION.get() : BackupArchiver.DEFAULT_PER_VERSION,
            value -> {
                if (!SPEC.isLoaded() || BACKUPS_PER_VERSION.get() == value) return;
                BACKUPS_PER_VERSION.set(value);
                BACKUPS_PER_VERSION.save();
            });
    }
}
