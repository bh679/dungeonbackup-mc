package games.brennan.dungeonbackup.api;

import games.brennan.dungeonbackup.core.BackupArchiver;
import games.brennan.dungeonbackup.core.BackupScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point for consumer mods.
 *
 * <pre>{@code
 * DungeonBackup.register("mymod")
 *     .dataRoot(FMLPaths.GAMEDIR.get().resolve("mymod"))
 *     .relocate("mymod-progress", "progress", Kind.DIRECTORY)   // one-shot legacy migration
 *     .onRestored(MyStores::reload)
 *     .register();
 * }</pre>
 *
 * <p>Register at mod construction. From then on the library migrates the root out of
 * {@code config/} if needed, takes debounced restore points at the registered triggers, mirrors
 * them outside the instance, prunes per mod version, detects the "install looks factory-fresh but
 * shouldn't" signature, and offers a title-screen restore. A new sub-folder under the root needs
 * nothing from the consumer.</p>
 */
public final class DungeonBackup {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    private static final List<Registration> REGISTRATIONS = new CopyOnWriteArrayList<>();

    private DungeonBackup() {}

    /** Start describing a mod's data. Finish with {@link Registration.Builder#register()}. */
    public static Registration.Builder register(String modId) {
        return Registration.builder(modId);
    }

    /** Add a built registration. Re-registering a mod id replaces the earlier entry. */
    public static Registration add(Registration registration) {
        REGISTRATIONS.removeIf(r -> r.modId().equals(registration.modId()));
        REGISTRATIONS.add(registration);
        LOGGER.info("[DungeonBackup] Registered {} — data root {}, {} extra root(s), {} relocation(s)",
            registration.modId(), registration.dataRoot(), registration.extraRoots().size(),
            registration.relocations().size());
        return registration;
    }

    /** Every registration, in registration order. */
    public static List<Registration> registrations() {
        return List.copyOf(REGISTRATIONS);
    }

    public static Optional<Registration> registration(String modId) {
        return REGISTRATIONS.stream().filter(r -> r.modId().equals(modId)).findFirst();
    }

    /**
     * Ask for a restore point of {@code modId}'s data. Returns immediately; the write is debounced
     * onto a worker thread. For the moment a consumer has just written brand-new work that exists
     * nowhere else. Unknown mod ids are ignored (logged).
     */
    public static void requestBackup(String modId, String reason) {
        Optional<Registration> registration = registration(modId);
        if (registration.isEmpty()) {
            LOGGER.warn("[DungeonBackup] requestBackup({}) — no such registration", modId);
            return;
        }
        BackupScheduler.request(registration.get(), reason);
    }

    /**
     * Write a restore point of {@code modId}'s data right now, on the calling thread, honouring
     * the mode and mirroring outside the instance. For commands. Empty result when the mode is OFF.
     */
    public static Optional<BackupArchiver.Result> backupNow(String modId, String reason) throws IOException {
        Registration registration = registration(modId).orElseThrow(
            () -> new IllegalArgumentException("no such registration: " + modId));
        if (!BackupSettings.mode().writesAnything()) return Optional.empty();
        int perVersion = BackupSettings.perVersion();
        BackupArchiver.Result result = BackupArchiver.create(registration.backupsRoot(), modId,
            registration.sources(), reason, registration.modVersion(), perVersion);
        if (BackupSettings.mode().writesOutsideTheInstance() && result.wrote()) {
            registration.externalBackupsRoot().ifPresent(external ->
                BackupArchiver.mirror(result.archive().orElseThrow(), external, modId, perVersion));
        }
        return Optional.of(result);
    }

    /**
     * Every archive of {@code modId}, newest first, in-instance before the out-of-instance mirror.
     * For consumers that read single files back out of archives via
     * {@link BackupArchiver#readEntry} / {@link BackupArchiver#listEntries}.
     */
    public static List<Path> archives(String modId) {
        Optional<Registration> registration = registration(modId);
        if (registration.isEmpty()) return List.of();
        Registration r = registration.get();
        List<Path> all = new ArrayList<>(BackupArchiver.listArchives(r.backupsRoot(), modId));
        r.externalBackupsRoot().ifPresent(root -> all.addAll(BackupArchiver.listArchives(root, modId)));
        return List.copyOf(all);
    }

    /** Test seam: drop every registration. */
    public static void clearRegistrations() {
        REGISTRATIONS.clear();
    }
}
