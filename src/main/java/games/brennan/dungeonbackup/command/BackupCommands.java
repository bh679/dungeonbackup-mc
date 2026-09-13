package games.brennan.dungeonbackup.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.dungeonbackup.DungeonBackupMod;
import games.brennan.dungeonbackup.api.DungeonBackup;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.core.BackupArchiver;
import games.brennan.dungeonbackup.core.DataRecovery;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code /dungeonbackup backup [mod]} — write a restore point right now; and
 * {@code /dungeonbackup restore [mod] [n]} — list every place a mod's data could be recovered
 * from, and put one of them back.
 *
 * <p>Open to everyone, any game mode: neither can destroy anything. A backup only ever adds a zip,
 * and restoring is additive — a file that already exists on disk is left alone, and a sibling
 * instance is copied from, never moved — so the commands cannot make anyone worse off. Root
 * commands rather than subcommands of anything permission-gated, for the same reason.</p>
 *
 * <p>With one registration the mod argument is optional. Consumers may also ask for root-level
 * aliases ({@link Registration.Builder#commandAliases}) so a name players already know — Dungeon
 * Train's {@code /dtbackup} and {@code /dtrestore} — keeps working.</p>
 */
public final class BackupCommands {

    private static final String KEY = "command." + DungeonBackupMod.MOD_ID + ".";

    private BackupCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(DungeonBackupMod.MOD_ID)
            .requires(source -> true);
        LiteralArgumentBuilder<CommandSourceStack> backup = Commands.literal("backup")
            .executes(ctx -> backup(ctx, only()));
        LiteralArgumentBuilder<CommandSourceStack> restore = Commands.literal("restore")
            .executes(ctx -> list(ctx, only()))
            .then(Commands.argument("index", IntegerArgumentType.integer(1))
                .executes(ctx -> restore(ctx, only(), IntegerArgumentType.getInteger(ctx, "index"))));
        for (Registration r : DungeonBackup.registrations()) {
            backup.then(Commands.literal(r.modId()).executes(ctx -> backup(ctx, Optional.of(r))));
            restore.then(Commands.literal(r.modId())
                .executes(ctx -> list(ctx, Optional.of(r)))
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> restore(ctx, Optional.of(r), IntegerArgumentType.getInteger(ctx, "index")))));
        }
        dispatcher.register(root.then(backup).then(restore));

        for (Registration r : DungeonBackup.registrations()) {
            if (r.backupCommandAlias() != null) {
                dispatcher.register(Commands.literal(r.backupCommandAlias())
                    .requires(source -> true)
                    .executes(ctx -> backup(ctx, Optional.of(r))));
            }
            if (r.restoreCommandAlias() != null) {
                dispatcher.register(Commands.literal(r.restoreCommandAlias())
                    .requires(source -> true)
                    .executes(ctx -> list(ctx, Optional.of(r)))
                    .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> restore(ctx, Optional.of(r), IntegerArgumentType.getInteger(ctx, "index")))));
            }
        }
    }

    /** The one registration when there is exactly one; empty otherwise (the mod must be named). */
    private static Optional<Registration> only() {
        List<Registration> all = DungeonBackup.registrations();
        return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
    }

    private static int backup(CommandContext<CommandSourceStack> ctx, Optional<Registration> target) {
        CommandSourceStack source = ctx.getSource();
        if (target.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "name_mod", modIds()));
            return 0;
        }
        Registration r = target.get();
        try {
            Optional<BackupArchiver.Result> result = DungeonBackup.backupNow(r.modId(), "manual");
            if (result.isEmpty()) {
                source.sendFailure(Component.translatable(KEY + "backup.off"));
                return 0;
            }
            if (!result.get().wrote()) {
                source.sendSuccess(() -> Component.translatable(KEY + "backup.unchanged")
                    .withStyle(ChatFormatting.GREEN), false);
                return 1;
            }
            String name = result.get().archive().orElseThrow().getFileName().toString();
            int files = result.get().fileCount();
            source.sendSuccess(() -> Component.translatable(KEY + "backup.success", name, files)
                .withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.translatable(KEY + "backup.fail", e.toString()));
            return 0;
        }
    }

    private static List<DataRecovery.Candidate> candidates(Registration r) {
        return DataRecovery.findCandidates(r, FMLPaths.GAMEDIR.get(), r.externalBackupsRoot().orElse(null));
    }

    private static int list(CommandContext<CommandSourceStack> ctx, Optional<Registration> target) {
        CommandSourceStack source = ctx.getSource();
        if (target.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "name_mod", modIds()));
            return 0;
        }
        Registration r = target.get();
        List<DataRecovery.Candidate> found = candidates(r);
        if (found.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "restore.none")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "restore.header", r.displayName())
            .withStyle(ChatFormatting.GREEN), false);
        for (int i = 0; i < found.size(); i++) {
            DataRecovery.Candidate candidate = found.get(i);
            int index = i + 1;
            source.sendSuccess(() -> Component.translatable(KEY + "restore.entry",
                index, candidate.description(), candidate.location())
                .withStyle(ChatFormatting.GRAY), false);
        }
        return found.size();
    }

    private static int restore(CommandContext<CommandSourceStack> ctx, Optional<Registration> target, int index) {
        CommandSourceStack source = ctx.getSource();
        if (target.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "name_mod", modIds()));
            return 0;
        }
        Registration r = target.get();
        List<DataRecovery.Candidate> found = candidates(r);
        if (index > found.size()) {
            String cmd = r.restoreCommandAlias() != null
                ? r.restoreCommandAlias() : DungeonBackupMod.MOD_ID + " restore";
            source.sendFailure(Component.translatable(KEY + "restore.no_such", index, "/" + cmd));
            return 0;
        }
        DataRecovery.Candidate candidate = found.get(index - 1);
        try {
            int written = DataRecovery.restore(r, candidate);
            // Recovered files are on disk but not in any of the consumer's caches — its hook
            // reloads them so they appear without a restart.
            runRestoredHook(r);
            source.sendSuccess(() -> Component.translatable(KEY + "restore.success",
                written, candidate.location()).withStyle(ChatFormatting.GREEN), false);
            return written;
        } catch (IOException e) {
            source.sendFailure(Component.translatable(KEY + "restore.fail", e.toString()));
            return 0;
        }
    }

    /** Run the consumer's post-restore hook; its failure must not read as a failed restore. */
    public static void runRestoredHook(Registration r) {
        try {
            r.onRestored().run();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("DungeonBackup")
                .warn("[DungeonBackup] {}: onRestored hook failed: {}", r.modId(), e.toString());
        }
    }

    private static String modIds() {
        return String.join(", ", DungeonBackup.registrations().stream().map(Registration::modId).toList());
    }
}
