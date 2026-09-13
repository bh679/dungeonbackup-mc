package games.brennan.dungeonbackup.client;

import games.brennan.dungeonbackup.DungeonBackupMod;
import games.brennan.dungeonbackup.api.DungeonBackup;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.core.DataRecovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Surfaces {@link DataRecoveryScreen} at the title screen when an install looks like it lost a
 * registered mod's data and something can be put back.
 *
 * <p>Arm on {@code ScreenEvent.Init.Post} for the title screen, open after a short tick delay,
 * bail if the player navigated away meanwhile so it never steals a click from someone already
 * heading into a world. That navigate-away guard is also what keeps this out of the way of other
 * title-screen prompts.</p>
 *
 * <p>Title screen rather than world join: the data has to be back before a world loads, or the
 * run has already started without it. The scan is a bounded directory walk and runs <b>once per
 * session</b>, cached. With several registrations, each one that needs the card gets it in turn —
 * the next opens when the player is back on the title screen.</p>
 *
 * <p>"Don't ask again" is recorded as a marker file in the mod's data root rather than in the
 * client config — the answer is about this install's data, so it belongs next to the data, and it
 * survives a config folder being replaced by the very kind of update that caused the loss.</p>
 */
@EventBusSubscriber(modid = DungeonBackupMod.MOD_ID, value = Dist.CLIENT)
public final class DataRecoveryPromptHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    /**
     * Ticks to wait after the title screen initialises before opening.
     *
     * <p>Deliberately short: other mods' title-screen prompts commonly wait ~24 ticks. They all
     * disarm themselves the moment another screen takes over, so whichever opens first wins the
     * visit and the rest wait for the player to close it — and "your data is missing" outranks
     * anything else a mod might say here.</p>
     */
    private static final int OPEN_DELAY_TICKS = 18;

    static final String DISMISSED_MARKER = "recovery-dismissed.marker";

    /** One registration's scan result. */
    record Pending(Registration registration, List<DataRecovery.Candidate> candidates) {}

    private static int openDelayRemaining = -1;
    private static TitleScreen pendingParent = null;

    /** Session cache of the scan: {@code null} until the first title screen. */
    private static Deque<Pending> queue = null;

    private DataRecoveryPromptHandler() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (openDelayRemaining <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen)) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        openDelayRemaining--;
        if (openDelayRemaining != 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent) return;
        Pending next = nextPending();
        if (next == null) return;
        LOGGER.info("[DungeonBackup] {}: offering {} candidate(s) at the title screen",
            next.registration().modId(), next.candidates().size());
        mc.setScreen(new DataRecoveryScreen(parent, next.registration(), next.candidates()));
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (openDelayRemaining > 0) return;
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) return;
        if (nextPending() == null) return;
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
    }

    /** The next registration with something to offer, or null. Scans once per session. */
    private static Pending nextPending() {
        if (queue == null) queue = scan();
        return queue.peek();
    }

    private static Deque<Pending> scan() {
        Deque<Pending> found = new ArrayDeque<>();
        for (Registration registration : DungeonBackup.registrations()) {
            List<DataRecovery.Candidate> candidates = scan(registration);
            if (!candidates.isEmpty()) found.add(new Pending(registration, candidates));
        }
        return found;
    }

    private static List<DataRecovery.Candidate> scan(Registration registration) {
        try {
            // A consumer's dev builds routinely have an empty data root — a branch switch, a wiped
            // run/ folder — which is the exact signature of a player who lost everything, so the
            // consumer can suppress the unprompted card. The scan and the restore command still
            // work: the command is asked for, and only the card is noise.
            if (registration.promptSuppressed()) {
                LOGGER.debug("[DungeonBackup] {}: prompt suppressed by the consumer.", registration.modId());
                return List.of();
            }
            if (Files.exists(dismissedMarker(registration))) return List.of();
            if (!DataRecovery.looksEmptied(registration, FMLPaths.CONFIGDIR.get())) return List.of();
            List<DataRecovery.Candidate> found = DataRecovery.findCandidates(registration,
                FMLPaths.GAMEDIR.get(), registration.externalBackupsRoot().orElse(null));
            if (!found.isEmpty()) {
                LOGGER.info("[DungeonBackup] {}: this install has no data and {} candidate(s) were "
                    + "found to restore from.", registration.modId(), found.size());
            }
            return found;
        } catch (Exception e) {
            // A recovery offer must never be the thing that stops the game reaching the menu.
            LOGGER.warn("[DungeonBackup] {}: recovery scan failed: {}", registration.modId(), e.toString());
            return List.of();
        }
    }

    /**
     * The player answered for {@code registration}. {@code permanent} writes the marker so the
     * offer never returns; a restore passes {@code false}, because once the data is back the loss
     * signature no longer matches and the scan won't fire anyway.
     */
    static void onAnswered(Registration registration, boolean permanent) {
        if (queue != null) queue.removeIf(p -> p.registration().modId().equals(registration.modId()));
        if (!permanent) return;
        try {
            Path marker = dismissedMarker(registration);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, registration.displayName()
                + ": recovery offer dismissed by the player.\n");
            LOGGER.info("[DungeonBackup] {}: recovery offer dismissed permanently.", registration.modId());
        } catch (IOException e) {
            LOGGER.warn("[DungeonBackup] {}: couldn't record the dismissal: {}",
                registration.modId(), e.toString());
        }
    }

    private static Path dismissedMarker(Registration registration) {
        return registration.dataRoot().resolve(DISMISSED_MARKER);
    }
}
