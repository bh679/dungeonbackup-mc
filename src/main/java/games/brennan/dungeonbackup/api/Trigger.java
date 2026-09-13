package games.brennan.dungeonbackup.api;

/**
 * The automatic moments a restore point can be taken at. A consumer picks the subset it wants;
 * manual requests ({@link DungeonBackup#requestBackup}) and the in-game command are always
 * available regardless.
 */
public enum Trigger {
    /** {@code ServerStartedEvent} — once per world load. */
    WORLD_LOAD,
    /** A player (never a mob) died. */
    PLAYER_DEATH,
    /** {@code ServerStoppedEvent} — written synchronously, because the JVM may be about to exit. */
    SESSION_END
}
