package games.brennan.dungeonbackup.api;

import games.brennan.dungeonbackup.core.BackupArchiver;
import games.brennan.dungeonbackup.core.BackupMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The player's backup settings — one mode and one per-version count for every registered mod.
 *
 * <p>Dungeon Backup owns these; consumer mods must not re-declare them. They live in this mod's
 * CLIENT config ({@code dungeonbackup-client.toml}) because backups are this machine's local files.
 * This facade is what the rest of the library (and any consumer's options screen) reads and writes,
 * so it also works on a dedicated server where no client config was ever loaded: every getter has
 * a not-loaded fallback, and the fallback is the safe one — a server whose config isn't there keeps
 * the default rather than silently stopping backups.</p>
 *
 * <p><b>Operator override.</b> {@code -Ddungeonbackup.backups=<mode>} (or the environment variable
 * {@code DUNGEONBACKUP_BACKUPS}) wins over the options screen everywhere. It exists because the
 * setting is a CLIENT config, so a dedicated server had no other way to turn backups off — and a
 * launch flag touches no config file, so it cannot interact with any consumer's config-integrity
 * checks. Nobody sets a JVM flag by accident, and an override that silently lost to a UI toggle
 * would be worse than not having one, so it is logged once at startup. Consumers that previously
 * honoured their own flag names register them ({@link Registration.Builder#legacyOverrideNames})
 * and those keep working.</p>
 */
public final class BackupSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    public static final String OVERRIDE_PROPERTY = "dungeonbackup.backups";
    public static final String OVERRIDE_ENV = "DUNGEONBACKUP_BACKUPS";

    /** Slider bounds for "Backups per version". Kept in step with the config's own range. */
    public static final int PER_VERSION_MIN = 1;
    public static final int PER_VERSION_MAX = 20;

    private static volatile Supplier<BackupMode> modeProvider = () -> BackupMode.DEFAULT;
    private static volatile Consumer<BackupMode> modeWriter = m -> { };
    private static volatile IntSupplier perVersionProvider = () -> BackupArchiver.DEFAULT_PER_VERSION;
    private static volatile IntConsumer perVersionWriter = n -> { };

    /** Resolved once: neither the property nor the environment changes while the JVM runs. */
    private static volatile BackupMode override = null;
    private static volatile boolean overrideResolved = false;

    private BackupSettings() {}

    /** Installed by the client config once its spec is registered. Consumers never call this. */
    public static void install(Supplier<BackupMode> mode, Consumer<BackupMode> setMode,
                               IntSupplier perVersion, IntConsumer setPerVersion) {
        modeProvider = mode;
        modeWriter = setMode;
        perVersionProvider = perVersion;
        perVersionWriter = setPerVersion;
    }

    /** The effective mode: the operator override if set, else the stored setting, else the default. */
    public static BackupMode mode() {
        BackupMode forced = operatorOverride();
        if (forced != null) return forced;
        try {
            BackupMode stored = modeProvider.get();
            return stored == null ? BackupMode.DEFAULT : stored;
        } catch (Throwable notOnThisSide) {
            return BackupMode.DEFAULT;
        }
    }

    /** The stored setting, ignoring any override — what the options screen shows. */
    public static BackupMode storedMode() {
        try {
            BackupMode stored = modeProvider.get();
            return stored == null ? BackupMode.DEFAULT : stored;
        } catch (Throwable notOnThisSide) {
            return BackupMode.DEFAULT;
        }
    }

    public static void setMode(BackupMode mode) {
        if (mode == null) return;
        try {
            modeWriter.accept(mode);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonBackup] Couldn't store backup mode {}: {}", mode, t.toString());
        }
    }

    /** How many archives to keep per mod version, clamped to the slider range. */
    public static int perVersion() {
        try {
            int n = perVersionProvider.getAsInt();
            return Math.max(PER_VERSION_MIN, Math.min(PER_VERSION_MAX, n));
        } catch (Throwable notOnThisSide) {
            return BackupArchiver.DEFAULT_PER_VERSION;
        }
    }

    public static void setPerVersion(int value) {
        try {
            perVersionWriter.accept(Math.max(PER_VERSION_MIN, Math.min(PER_VERSION_MAX, value)));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonBackup] Couldn't store backups-per-version {}: {}", value, t.toString());
        }
    }

    /** Whether a launch flag is forcing the mode — the options screen greys itself out when so. */
    public static boolean isOverridden() {
        return operatorOverride() != null;
    }

    /** The launch-flag override, or {@code null} when unset. Resolved once and logged. */
    public static BackupMode operatorOverride() {
        if (overrideResolved) return override;
        synchronized (BackupSettings.class) {
            if (overrideResolved) return override;
            Resolved resolved;
            try {
                resolved = resolveOverride(System.getProperties()::getProperty, System::getenv,
                    DungeonBackup.registrations());
            } catch (SecurityException restricted) {
                resolved = null;
            }
            if (resolved != null) {
                LOGGER.info("[DungeonBackup] Backup mode forced to {} by {} — the Options setting is "
                    + "ignored while it is set.", resolved.mode(), resolved.source());
                override = resolved.mode();
            }
            overrideResolved = true;
            return override;
        }
    }

    /** Which name supplied an override, so the log sends an operator to the right place. */
    public record Resolved(BackupMode mode, String source) {}

    /**
     * Pure resolution of the override from a property lookup and an env lookup: this mod's own
     * names first, then each registration's legacy names in registration order. Package-visible
     * for tests, which pass maps instead of the real JVM environment.
     */
    public static Resolved resolveOverride(java.util.function.Function<String, String> property,
                                           java.util.function.Function<String, String> env,
                                           List<Registration> registrations) {
        List<String[]> names = new ArrayList<>();
        names.add(new String[] {OVERRIDE_PROPERTY, OVERRIDE_ENV});
        for (Registration r : registrations) {
            if (r.legacyOverrideProperty() != null || r.legacyOverrideEnv() != null) {
                names.add(new String[] {r.legacyOverrideProperty(), r.legacyOverrideEnv()});
            }
        }
        for (String[] pair : names) {
            String p = pair[0] == null ? null : property.apply(pair[0]);
            if (p != null && !p.isBlank()) return new Resolved(BackupMode.parse(p), pair[0]);
            String e = pair[1] == null ? null : env.apply(pair[1]);
            if (e != null && !e.isBlank()) return new Resolved(BackupMode.parse(e), pair[1]);
        }
        return null;
    }

    /** Test seam. */
    static void resetOverride() {
        synchronized (BackupSettings.class) {
            override = null;
            overrideResolved = false;
        }
    }

    // ---- Aggregate figures across every registration ----

    /** Every backups folder in use — each registration's in-instance root and its mirror. */
    public static List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        for (Registration r : DungeonBackup.registrations()) {
            roots.add(r.backupsRoot());
            r.externalBackupsRoot().ifPresent(roots::add);
        }
        return List.copyOf(roots);
    }

    /** Bytes held by every registered mod's archives, in both roots — the Clear button's figure. */
    public static long totalSize() {
        long total = 0;
        for (Registration r : DungeonBackup.registrations()) {
            total += BackupArchiver.totalSize(r.backupsRoot(), r.modId());
            total += r.externalBackupsRoot()
                .map(root -> BackupArchiver.totalSize(root, r.modId())).orElse(0L);
        }
        return total;
    }

    /** Number of archives across every registered mod and both roots. */
    public static int archiveCount() {
        int count = 0;
        for (Registration r : DungeonBackup.registrations()) {
            count += BackupArchiver.listArchives(r.backupsRoot(), r.modId()).size();
            count += r.externalBackupsRoot()
                .map(root -> BackupArchiver.listArchives(root, r.modId()).size()).orElse(0);
        }
        return count;
    }

    /** Delete every registered mod's archives from both roots. Never throws. */
    public static void clearAll() {
        for (Registration r : DungeonBackup.registrations()) {
            BackupArchiver.clear(r.backupsRoot(), r.modId());
            r.externalBackupsRoot().ifPresent(root -> BackupArchiver.clear(root, r.modId()));
        }
    }
}
