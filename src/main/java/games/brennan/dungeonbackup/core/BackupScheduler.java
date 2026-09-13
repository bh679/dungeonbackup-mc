package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.api.BackupSettings;
import games.brennan.dungeonbackup.api.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Decides <em>when</em> a restore point is taken, and takes it off the server thread.
 *
 * <p>Backups happen at the moments a player would be upset to lose the work either side of:
 * loading a world, a consumer's own "something was just saved" call, dying, and leaving the world.
 * The load/exit pair alone would leave a whole session unprotected — work made and then lost
 * before the next world load would be in no archive at all, which is the exact failure this
 * library exists to prevent.</p>
 *
 * <p><b>Why it is debounced and asynchronous.</b> A consumer may request constantly (an editor
 * saving on every change), and a backup is a full walk-and-zip of the data root. Doing that inline
 * would hitch the game on every save, and doing it on every save would be almost entirely
 * redundant work. So a request marks the mod dirty and a single daemon thread writes at most one
 * archive per {@link #MIN_INTERVAL_MS} per mod; the content digest in {@link BackupArchiver} then
 * skips even that when nothing actually changed. Session end is the exception — it runs
 * <b>synchronously</b>, because the JVM may exit before a queued task would run.</p>
 *
 * <p>The walk can overlap a save in progress, so an archive may catch a file mid-write. That is
 * accepted deliberately: archives are written atomically and never replace each other, so the
 * previous restore point is always intact, and one suspect entry in the newest archive is a far
 * smaller problem than not having the archive.</p>
 *
 * <p>Free of Forge types: the events live in {@code neoforge.BackupEvents}.</p>
 */
public final class BackupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    /**
     * Floor on the gap between two automatic backups of one mod. Two minutes is short enough that
     * little work is ever at risk and long enough that a save-tweak-save loop doesn't thrash the disk.
     */
    static final long MIN_INTERVAL_MS = 120_000L;

    /** Single daemon thread: backups are strictly serialised and never hold up shutdown. */
    private static final ScheduledExecutorService WORKER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DungeonBackup-Worker");
            t.setDaemon(true);
            return t;
        });

    /** Per-mod debounce state. */
    private static final class State {
        long lastBackupAt = 0L;
        ScheduledFuture<?> scheduled = null;
        String pendingReason = null;
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private BackupScheduler() {}

    private static State stateOf(Registration registration) {
        return STATES.computeIfAbsent(registration.modId(), id -> new State());
    }

    /**
     * Ask for a restore point. Returns immediately; the write happens on the worker thread, no
     * sooner than {@link #MIN_INTERVAL_MS} after the last one. Repeat requests inside that window
     * collapse into the one already queued.
     */
    public static void request(Registration registration, String reason) {
        State state = stateOf(registration);
        synchronized (state) {
            if (state.scheduled != null && !state.scheduled.isDone()) {
                // Already queued — keep the earlier deadline, but report the most recent cause.
                state.pendingReason = reason;
                return;
            }
            state.pendingReason = reason;
            long sinceLast = System.currentTimeMillis() - state.lastBackupAt;
            long delay = Math.max(0L, MIN_INTERVAL_MS - sinceLast);
            state.scheduled = WORKER.schedule(() -> runQueued(registration), delay, TimeUnit.MILLISECONDS);
        }
    }

    private static void runQueued(Registration registration) {
        State state = stateOf(registration);
        String reason;
        synchronized (state) {
            reason = state.pendingReason == null ? "periodic" : state.pendingReason;
            state.pendingReason = null;
            state.scheduled = null;
        }
        writeNow(registration, reason);
    }

    /**
     * Cancel anything queued and write <b>now</b>, on the calling thread. For session end: a
     * scheduled task would lose the race with JVM shutdown, and this is the backup that captures
     * everything the session just did.
     */
    public static void flush(Registration registration, String reason) {
        State state = stateOf(registration);
        synchronized (state) {
            if (state.scheduled != null) {
                state.scheduled.cancel(false);
                state.scheduled = null;
            }
            state.pendingReason = null;
        }
        writeNow(registration, reason);
    }

    /** Write a restore point immediately. Never throws — a failed backup must not stop the game. */
    public static void writeNow(Registration registration, String reason) {
        State state = stateOf(registration);
        try {
            BackupMode mode = BackupSettings.mode();
            if (!mode.writesAnything()) return;
            int perVersion = BackupSettings.perVersion();
            BackupArchiver.Result result = BackupArchiver.create(registration.backupsRoot(),
                registration.modId(), registration.sources(), reason, registration.modVersion(), perVersion);
            if (mode.writesOutsideTheInstance() && result.wrote()) {
                registration.externalBackupsRoot().ifPresent(external ->
                    BackupArchiver.mirror(result.archive().orElseThrow(), external,
                        registration.modId(), perVersion));
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonBackup] {}: couldn't write a restore point ({}): {}",
                registration.modId(), reason, e.toString());
        } finally {
            synchronized (state) {
                state.lastBackupAt = System.currentTimeMillis();
            }
        }
    }
}
