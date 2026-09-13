package games.brennan.dungeonbackup.client;

import games.brennan.dungeonbackup.DungeonBackupMod;
import games.brennan.dungeonbackup.api.BackupSettings;
import games.brennan.dungeonbackup.core.BackupArchiver;
import games.brennan.dungeonbackup.core.BackupMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The three backup controls, built for whoever wants to host them — a consumer's own options
 * screen (Dungeon Train files them under an Options → Backups tab) or a plain settings list.
 *
 * <p>Each builder returns a widget at {@code (0, 0)} sized {@code width × height}; the host
 * positions it. Label helpers expose every text a control can show so a host that packs rows by
 * measured width can size them before the widgets exist.</p>
 */
public final class BackupOptionsWidgets {

    private static final String K = "gui." + DungeonBackupMod.MOD_ID + ".options.";
    public static final String KEY_MODE = K + "backups";
    public static final String KEY_PER_VERSION = K + "backups_per_version";
    public static final String KEY_CLEAR = K + "clear_backups";
    private static final String KEY_VALUE_ROW = K + "value_row";

    private BackupOptionsWidgets() {}

    // ---- Mode ----

    /**
     * The On / Instanced / Off cycle button.
     *
     * <p>Unlike most option rows the tooltip is PER VALUE, not per row: "Instanced" means nothing
     * on its own, and the whole point of the setting is the difference in what each option
     * survives. The tooltip is therefore re-set on every change as well as seeded with the initial
     * value. When a launch flag forces the mode the button is disabled and says so.</p>
     */
    public static CycleButton<BackupMode> modeButton(int width, int height) {
        CycleButton<BackupMode> button = CycleButton.<BackupMode>builder(BackupOptionsWidgets::modeLabel)
            .withValues(List.of(BackupMode.EXTERNAL, BackupMode.INSTANCE, BackupMode.OFF))
            .withInitialValue(BackupSettings.storedMode())
            .create(0, 0, width, height, Component.translatable(KEY_MODE), (btn, mode) -> {
                BackupSettings.setMode(mode);
                btn.setTooltip(modeTip(mode));
            });
        button.setTooltip(modeTip(BackupSettings.storedMode()));
        if (BackupSettings.isOverridden()) {
            button.active = false;
            button.setTooltip(Tooltip.create(Component.translatable(KEY_MODE + ".overridden",
                BackupSettings.operatorOverride().name().toLowerCase(Locale.ROOT))));
        }
        return button;
    }

    /** Every label the mode button can show — for hosts that size rows by measured text. */
    public static List<Component> modeLabels() {
        List<Component> out = new ArrayList<>();
        for (BackupMode mode : BackupMode.values()) {
            out.add(Component.translatable(KEY_VALUE_ROW, Component.translatable(KEY_MODE), modeLabel(mode)));
        }
        return out;
    }

    /** On / Instanced / Off, each with its own translated label. */
    public static Component modeLabel(BackupMode mode) {
        return Component.translatable(KEY_MODE + "." + mode.name().toLowerCase(Locale.ROOT));
    }

    /** What the currently-selected backup mode actually protects against. */
    public static Tooltip modeTip(BackupMode mode) {
        return Tooltip.create(Component.translatable(KEY_MODE + "." + mode.name().toLowerCase(Locale.ROOT) + ".tip"));
    }

    // ---- Per version ----

    /**
     * The "Backups per version" slider as an {@link OptionInstance}: the stored value is read once,
     * at construction, and commits through {@link BackupSettings#setPerVersion} on release. Hosts
     * that render {@code OptionInstance}s via {@code createButton} get the slider for free.
     */
    public static OptionInstance<Integer> perVersionOption() {
        int initial = Mth.clamp(BackupSettings.perVersion(),
            BackupSettings.PER_VERSION_MIN, BackupSettings.PER_VERSION_MAX);
        return new OptionInstance<Integer>(
            KEY_PER_VERSION,
            OptionInstance.cachedConstantTooltip(Component.translatable(KEY_PER_VERSION + ".tip")),
            (caption, value) -> perVersionLabel(caption, value),
            new OptionInstance.IntRange(BackupSettings.PER_VERSION_MIN, BackupSettings.PER_VERSION_MAX),
            initial,
            value -> BackupSettings.setPerVersion(value));
    }

    /** The slider as a ready widget. */
    public static AbstractWidget perVersionSlider(int width) {
        return perVersionOption().createButton(Minecraft.getInstance().options, 0, 0, width);
    }

    /** {@code "Backups per version: 5"}. */
    public static Component perVersionLabel(Component caption, int perVersion) {
        return Component.translatable(KEY_VALUE_ROW, caption, Integer.toString(perVersion));
    }

    /** The widest label the slider can show. */
    public static Component perVersionWidestLabel() {
        return perVersionLabel(Component.translatable(KEY_PER_VERSION), BackupSettings.PER_VERSION_MAX);
    }

    // ---- Clear ----

    /**
     * "Clear Backups (<size>)". The size is every registered mod's archives in both roots.
     *
     * @param host        the screen to return to after the confirm card
     * @param afterClear  run after a confirmed clear — the label carries the size, so the host
     *                    must rebuild its widgets (returning to the screen alone is not enough:
     *                    {@code Screen.init(Minecraft, int, int)} only repositions an initialised
     *                    screen, so the button kept reporting the space it had just freed)
     */
    public static Button clearButton(int width, int height, Screen host, Runnable afterClear) {
        Button button = Button.builder(clearLabel(BackupArchiver.formatBytes(BackupSettings.totalSize())),
                b -> confirmClear(host, afterClear))
            .bounds(0, 0, width, height).build();
        button.setTooltip(Tooltip.create(Component.translatable(KEY_CLEAR + ".tip")));
        return button;
    }

    public static Component clearLabel(String size) {
        return Component.translatable(KEY_CLEAR, size);
    }

    /** The widest label the clear button can show, for hosts that size rows by measured text. */
    public static Component clearWidestLabel() {
        return clearLabel("000.0 GB");
    }

    /**
     * Ask before deleting, then delete from every root.
     *
     * <p>The message names each folder explicitly, including the out-of-instance one. "Clear all
     * backups" that quietly spared a folder the player cannot see would be the worse surprise of
     * the two, and this is the only place that folder is ever surfaced.</p>
     */
    private static void confirmClear(Screen host, Runnable afterClear) {
        Minecraft mc = Minecraft.getInstance();
        List<Path> roots = BackupSettings.roots();
        int count = BackupSettings.archiveCount();
        Component body;
        if (roots.size() == 2) {
            body = Component.translatable(KEY_CLEAR + ".confirm.both",
                roots.get(0).toString(), roots.get(1).toString());
        } else {
            StringBuilder where = new StringBuilder();
            for (Path root : roots) {
                if (where.length() > 0) where.append('\n');
                where.append(root);
            }
            body = Component.translatable(KEY_CLEAR + ".confirm.one", where.toString());
        }
        mc.setScreen(new ConfirmScreen(
            proceed -> {
                mc.setScreen(host);
                if (!proceed) return;
                BackupSettings.clearAll();
                afterClear.run();
            },
            Component.translatable(KEY_CLEAR + ".confirm.title", count),
            body,
            Component.translatable(KEY_CLEAR + ".confirm.yes"),
            CommonComponents.GUI_CANCEL));
    }
}
