package games.brennan.dungeonbackup;

import games.brennan.dungeonbackup.api.Kind;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.core.FileTrees;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A registration shaped like Dungeon Train's — the first consumer, and the one whose on-disk
 * layout (archive names, labels, relocation table) the library must keep reading.
 */
public final class TestRegistrations {

    public static final String MOD = "dungeontrain";

    private TestRegistrations() {}

    /** The relocation table Dungeon Train registers, verbatim. */
    public static Registration.Builder dungeonTrainLike(Path instance) {
        Path dataRoot = instance.resolve(MOD);
        Path dtpacks = instance.resolve("dtpacks");
        return Registration.builder(MOD)
            .displayName("Dungeon Train")
            .modVersion(() -> "0.861.0")
            .dataRoot(dataRoot)
            .rootLabel(MOD)
            .alsoBackUp("dtpacks", dtpacks)
            .externalDirName("DungeonTrain")
            .relocate("dungeontrain/user", "user", Kind.DIRECTORY)
            .relocate("dungeontrain/dtpacks-state.json", "dtpacks-state.json", Kind.FILE)
            .relocate("dungeontrain-achievements", "achievements", Kind.DIRECTORY)
            .relocate("dungeontrain-stats", "stats", Kind.DIRECTORY)
            .relocate("dungeontrain-narrative", "narrative", Kind.DIRECTORY)
            .relocate("dungeontrain/loadouts", "loadouts", Kind.DIRECTORY)
            .relocate("dungeontrain/translations", "translations", Kind.DIRECTORY)
            .relocate("dungeontrain-relay-outbox.json", "outbox/relay-outbox.json", Kind.FILE)
            .relocate("dungeontrain-chat-outbox.json", "outbox/chat-outbox.json", Kind.FILE)
            .relocate("dungeontrain-translation-outbox.json", "outbox/translation-outbox.json", Kind.FILE)
            .livenessProbe(root -> hasLiveData(root) || hasSavedPackages(dtpacks))
            .siblingProbe(TestRegistrations::dataHeldBy)
            .commandAliases("dtbackup", "dtrestore")
            .legacyOverrideNames("dungeontrain.backups", "DUNGEONTRAIN_BACKUPS");
    }

    /** Is there any build, advancement or stat at the live root? */
    static boolean hasLiveData(Path dataRoot) {
        for (String sub : List.of("user", "achievements", "stats")) {
            if (FileTrees.containsAnyFile(dataRoot.resolve(sub))) return true;
        }
        return false;
    }

    /** An extracted package folder with content, or a .zip snapshot — never a stray README. */
    public static boolean hasSavedPackages(Path dtpacksRoot) {
        if (dtpacksRoot == null || !Files.isDirectory(dtpacksRoot)) return false;
        try (var children = Files.list(dtpacksRoot)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    if (FileTrees.containsAnyFile(child)) return true;
                } else if (child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    public static Optional<String> dataHeldBy(Path instanceRoot) {
        Path data = instanceRoot.resolve(MOD);
        Path config = instanceRoot.resolve("config");
        boolean builds = FileTrees.containsAnyFile(data.resolve("user"))
            || FileTrees.containsAnyFile(config.resolve("dungeontrain").resolve("user"));
        boolean profile = FileTrees.containsAnyFile(data.resolve("achievements"))
            || FileTrees.containsAnyFile(config.resolve("dungeontrain-achievements"));
        if (builds && profile) return Optional.of("builds and progress");
        if (builds) return Optional.of("builds");
        if (profile) return Optional.of("progress");
        return Optional.empty();
    }
}
