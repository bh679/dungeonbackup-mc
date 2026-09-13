package games.brennan.dungeonbackup.neoforge;

import games.brennan.dungeonbackup.DungeonBackupMod;
import games.brennan.dungeonbackup.api.DungeonBackup;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.Trigger;
import games.brennan.dungeonbackup.command.BackupCommands;
import games.brennan.dungeonbackup.core.BackupScheduler;
import games.brennan.dungeonbackup.core.DataMigration;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The NeoForge event surface: one subscriber for every registration.
 *
 * <p>The only class besides the entrypoint and the client prompt that touches {@code FMLPaths}
 * or Forge events — the engine underneath is loader-free and {@code @TempDir}-testable.</p>
 */
@EventBusSubscriber(modid = DungeonBackupMod.MOD_ID)
public final class BackupEvents {

    /** Whether this world load has already been backed up on the way in. */
    private static volatile boolean backedUpThisLoad = false;

    private BackupEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BackupCommands.register(event.getDispatcher());
    }

    /**
     * Migrate every registration's data out of {@code config/}. {@code HIGHEST} so a consumer's
     * stores find their files at the new addresses when they load during the same start.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStarting(ServerStartingEvent event) {
        for (Registration registration : DungeonBackup.registrations()) {
            DataMigration.runOnce(registration, FMLPaths.CONFIGDIR.get());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(ServerStartedEvent event) {
        if (backedUpThisLoad) return;
        backedUpThisLoad = true;
        for (Registration registration : DungeonBackup.registrations()) {
            if (registration.backsUpOn(Trigger.WORLD_LOAD)) {
                BackupScheduler.request(registration, "world-load");
            }
        }
    }

    /**
     * A death ends a run, and the profile it wrote to is worth a restore point. Players only —
     * every mob death would fire this hundreds of times a session for nothing.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) return;
        for (Registration registration : DungeonBackup.registrations()) {
            if (registration.backsUpOn(Trigger.PLAYER_DEATH)) {
                BackupScheduler.request(registration, "death");
            }
        }
    }

    /**
     * Leaving the world. Runs <b>now</b>, on the calling thread, and cancels anything queued: a
     * scheduled task would lose the race with JVM shutdown, and this is the backup that captures
     * everything the session just did.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        for (Registration registration : DungeonBackup.registrations()) {
            if (registration.backsUpOn(Trigger.SESSION_END)) {
                BackupScheduler.flush(registration, "session-end");
            }
        }
        backedUpThisLoad = false;
    }
}
