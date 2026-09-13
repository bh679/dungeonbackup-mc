package games.brennan.dungeonbackup.api;

import games.brennan.dungeonbackup.core.ExternalRoots;
import games.brennan.dungeonbackup.core.FileTrees;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Everything Dungeon Backup knows about one consumer mod's player data — and the only place a
 * consumer has to describe it.
 *
 * <p>The design constraint this class exists to meet: <b>a consumer must not need a code change
 * every time it adds a data folder.</b> So a registration names a data <em>root</em>, not its
 * contents. Archives take the whole root wholesale ({@link #sources()}), the loss scan asks a
 * probe rather than a folder list, and restores land wherever the archive's labels say. The one
 * enumeration — {@link #relocations()} — is the finite list of pre-move locations a one-shot
 * migration drains, which cannot be anything but a list.</p>
 *
 * <p>Immutable, {@code FMLPaths}-free and built through {@link Builder}; a unit test constructs one
 * against {@code @TempDir} roots exactly the way a mod does.</p>
 */
public final class Registration {

    /** Folder name under the data root that holds this mod's archives. Always excluded from them. */
    public static final String BACKUPS_DIR = "backups";

    private final String modId;
    private final String displayName;
    private final Supplier<String> modVersion;
    private final Path dataRoot;
    private final String rootLabel;
    private final Set<String> excludeTopLevel;
    private final Map<String, Path> extraRoots;
    private final String externalDirName;
    private final List<Relocation> relocations;
    private final Predicate<Path> livenessProbe;
    private final Function<Path, Optional<String>> siblingProbe;
    private final Runnable beforeMigration;
    private final Runnable onRestored;
    private final Set<Trigger> triggers;
    private final BooleanSupplier promptSuppressed;
    private final String dataDescriptionKey;
    private final String backupCommandAlias;
    private final String restoreCommandAlias;
    private final String legacyOverrideProperty;
    private final String legacyOverrideEnv;

    private Registration(Builder b) {
        this.modId = b.modId;
        this.displayName = b.displayName == null ? b.modId : b.displayName;
        this.modVersion = b.modVersion == null ? () -> "" : b.modVersion;
        this.dataRoot = b.dataRoot;
        this.rootLabel = b.rootLabel == null ? b.modId : b.rootLabel;
        Set<String> excluded = new LinkedHashSet<>(b.excludeTopLevel);
        excluded.add(BACKUPS_DIR);
        this.excludeTopLevel = Collections.unmodifiableSet(excluded);
        this.extraRoots = Collections.unmodifiableMap(new LinkedHashMap<>(b.extraRoots));
        this.externalDirName = b.externalDirName == null ? b.modId : b.externalDirName;
        this.relocations = List.copyOf(b.relocations);
        this.livenessProbe = b.livenessProbe == null ? this::defaultLiveness : b.livenessProbe;
        this.siblingProbe = b.siblingProbe == null ? this::defaultSiblingProbe : b.siblingProbe;
        this.beforeMigration = b.beforeMigration == null ? () -> { } : b.beforeMigration;
        this.onRestored = b.onRestored == null ? () -> { } : b.onRestored;
        this.triggers = b.triggers.isEmpty()
            ? Collections.unmodifiableSet(EnumSet.allOf(Trigger.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(b.triggers));
        this.promptSuppressed = b.promptSuppressed == null ? () -> false : b.promptSuppressed;
        this.dataDescriptionKey = b.dataDescriptionKey;
        this.backupCommandAlias = b.backupCommandAlias;
        this.restoreCommandAlias = b.restoreCommandAlias;
        this.legacyOverrideProperty = b.legacyOverrideProperty;
        this.legacyOverrideEnv = b.legacyOverrideEnv;
    }

    // ---- Identity ----

    public String modId() { return modId; }

    /** Human-readable mod name, for the recovery card and command output. */
    public String displayName() { return displayName; }

    /** The version stamped into each archive's manifest and filename. */
    public String modVersion() {
        try {
            String v = modVersion.get();
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---- Roots ----

    /** The player-data root, e.g. {@code <gameDir>/dungeontrain/}. */
    public Path dataRoot() { return dataRoot; }

    /** The archive label the data root is written under. Also the sibling-instance folder name. */
    public String rootLabel() { return rootLabel; }

    /** Direct children of the data root that are not archived. Always contains {@link #BACKUPS_DIR}. */
    public Set<String> excludeTopLevel() { return excludeTopLevel; }

    /** Extra trees archived alongside the root, by label. Order is registration order. */
    public Map<String, Path> extraRoots() { return extraRoots; }

    /** {@code <dataRoot>/backups/} — where this mod's archives are written. */
    public Path backupsRoot() { return dataRoot.resolve(BACKUPS_DIR); }

    /** Folder name under the OS's per-user application data dir for the out-of-instance mirror. */
    public String externalDirName() { return externalDirName; }

    /**
     * {@code <os app data>/<externalDirName>/backups} — restore points kept <b>outside</b> the
     * instance. Empty when no home directory can be resolved. See {@link ExternalRoots}.
     */
    public Optional<Path> externalBackupsRoot() {
        return ExternalRoots.resolve(externalDirName);
    }

    /**
     * Everything worth archiving: the <b>whole</b> data root (minus {@link #excludeTopLevel()}),
     * then every extra root. Taking the root wholesale rather than listing folders is the point:
     * an enumeration once missed the loose files sitting directly under the root, so they were in
     * no archive at all.
     */
    public List<Source> sources() {
        List<Source> sources = new ArrayList<>();
        sources.add(new Source(rootLabel, dataRoot, excludeTopLevel));
        extraRoots.forEach((label, dir) -> sources.add(new Source(label, dir)));
        return List.copyOf(sources);
    }

    /**
     * The label → directory mapping a restore lands entries into. The same as {@link #sources()}
     * — derived from one place, so they cannot drift apart.
     */
    public List<Source> restoreTargets() {
        return sources();
    }

    // ---- Migration ----

    public List<Relocation> relocations() { return relocations; }

    /** Runs before the legacy migration walks; for a consumer's own earlier one-shot moves. */
    public Runnable beforeMigration() { return beforeMigration; }

    // ---- Recovery ----

    /** "Does this install still have data?" Asked of the data root by the loss scan. */
    public boolean hasLiveData(Path root) {
        try {
            return livenessProbe.test(root);
        } catch (RuntimeException e) {
            // A probe that throws must read as "has data": erring towards NOT offering recovery
            // is the safe direction — the card can never fire for an install that isn't empty.
            return true;
        }
    }

    /**
     * What another instance folder on this machine holds of this mod's data, as a short
     * description, or empty when it holds none.
     */
    public Optional<String> dataHeldBy(Path instanceRoot) {
        try {
            Optional<String> held = siblingProbe.apply(instanceRoot);
            return held == null ? Optional.empty() : held;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Called after a restore lands files, so the consumer can reload caches. */
    public Runnable onRestored() { return onRestored; }

    /** Whether the title-screen card is suppressed (e.g. on a developer build). */
    public boolean promptSuppressed() {
        try {
            return promptSuppressed.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Translation key for what the data <em>is</em> ("builds and progress"), used inside the
     * recovery card's sentences. Null when the consumer gave none — the generic wording is used.
     */
    public String dataDescriptionKey() { return dataDescriptionKey; }

    // ---- Triggers, commands, overrides ----

    public Set<Trigger> triggers() { return triggers; }

    public boolean backsUpOn(Trigger trigger) { return triggers.contains(trigger); }

    /** Root command name that aliases {@code /dungeonbackup backup} for this mod, or null. */
    public String backupCommandAlias() { return backupCommandAlias; }

    /** Root command name that aliases {@code /dungeonbackup restore [n]} for this mod, or null. */
    public String restoreCommandAlias() { return restoreCommandAlias; }

    /** A system-property name this mod previously honoured as a backup-mode override, or null. */
    public String legacyOverrideProperty() { return legacyOverrideProperty; }

    /** An environment-variable name this mod previously honoured as a backup-mode override, or null. */
    public String legacyOverrideEnv() { return legacyOverrideEnv; }

    // ---- Defaults ----

    /** Default liveness: any regular file under the root outside the excluded top-level folders. */
    private boolean defaultLiveness(Path root) {
        return FileTrees.containsAnyFile(root, excludeTopLevel);
    }

    /**
     * Default sibling probe: the instance holds {@code <rootLabel>/} with files in it, or any
     * relocation's legacy path under its {@code config/}.
     */
    private Optional<String> defaultSiblingProbe(Path instanceRoot) {
        if (FileTrees.containsAnyFile(instanceRoot.resolve(rootLabel), excludeTopLevel)) {
            return Optional.of(displayName + " data");
        }
        Path config = instanceRoot.resolve("config");
        for (Relocation relocation : relocations) {
            Path legacy = relocation.legacyPath(config);
            boolean present = relocation.kind() == Kind.FILE
                ? Files.isRegularFile(legacy)
                : FileTrees.containsAnyFile(legacy);
            if (present) return Optional.of(displayName + " data");
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "Registration[" + modId + " -> " + dataRoot + "]";
    }

    // ---- Builder ----

    public static Builder builder(String modId) {
        return new Builder(modId);
    }

    /** Fluent construction; every setter returns {@code this}. {@link #build()} validates. */
    public static final class Builder {
        private final String modId;
        private String displayName;
        private Supplier<String> modVersion;
        private Path dataRoot;
        private String rootLabel;
        private final Set<String> excludeTopLevel = new LinkedHashSet<>();
        private final Map<String, Path> extraRoots = new LinkedHashMap<>();
        private String externalDirName;
        private final List<Relocation> relocations = new ArrayList<>();
        private Predicate<Path> livenessProbe;
        private Function<Path, Optional<String>> siblingProbe;
        private Runnable beforeMigration;
        private Runnable onRestored;
        private final Set<Trigger> triggers = EnumSet.noneOf(Trigger.class);
        private BooleanSupplier promptSuppressed;
        private String dataDescriptionKey;
        private String backupCommandAlias;
        private String restoreCommandAlias;
        private String legacyOverrideProperty;
        private String legacyOverrideEnv;

        private Builder(String modId) {
            if (modId == null || !modId.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("modId must be a valid mod id: " + modId);
            }
            this.modId = modId;
        }

        public Builder displayName(String name) { this.displayName = name; return this; }

        public Builder modVersion(Supplier<String> version) { this.modVersion = version; return this; }

        /** The player-data root. Required. */
        public Builder dataRoot(Path root) { this.dataRoot = root; return this; }

        /** Archive label for the root; defaults to the mod id. Existing archives depend on it. */
        public Builder rootLabel(String label) { this.rootLabel = label; return this; }

        /** Skip a direct child of the data root. {@code backups} is always skipped. */
        public Builder excludeTopLevel(String name) { this.excludeTopLevel.add(name); return this; }

        /** Archive another tree too, under {@code label}. A null {@code dir} is ignored. */
        public Builder alsoBackUp(String label, Path dir) {
            if (dir != null) this.extraRoots.put(label, dir);
            return this;
        }

        /** Folder name outside the instance; defaults to the mod id. Existing mirrors depend on it. */
        public Builder externalDirName(String name) { this.externalDirName = name; return this; }

        public Builder relocate(String legacyRelative, String newRelative, Kind kind) {
            this.relocations.add(new Relocation(legacyRelative, newRelative, kind));
            return this;
        }

        public Builder relocations(List<Relocation> list) { this.relocations.addAll(list); return this; }

        public Builder livenessProbe(Predicate<Path> probe) { this.livenessProbe = probe; return this; }

        public Builder siblingProbe(Function<Path, Optional<String>> probe) { this.siblingProbe = probe; return this; }

        public Builder beforeMigration(Runnable hook) { this.beforeMigration = hook; return this; }

        public Builder onRestored(Runnable hook) { this.onRestored = hook; return this; }

        /** Restrict automatic backups to these moments. Unset means all of them. */
        public Builder backupOn(Trigger first, Trigger... rest) {
            this.triggers.add(first);
            Collections.addAll(this.triggers, rest);
            return this;
        }

        public Builder promptSuppressed(BooleanSupplier suppressed) { this.promptSuppressed = suppressed; return this; }

        public Builder dataDescriptionKey(String key) { this.dataDescriptionKey = key; return this; }

        /** Root command names to register as aliases, e.g. {@code ("dtbackup", "dtrestore")}. */
        public Builder commandAliases(String backup, String restore) {
            this.backupCommandAlias = backup;
            this.restoreCommandAlias = restore;
            return this;
        }

        /** Names this mod used to honour as its own backup-mode override, kept working. */
        public Builder legacyOverrideNames(String property, String env) {
            this.legacyOverrideProperty = property;
            this.legacyOverrideEnv = env;
            return this;
        }

        public Registration build() {
            if (dataRoot == null) throw new IllegalStateException("dataRoot is required for " + modId);
            Registration r = new Registration(this);
            Set<String> labels = new LinkedHashSet<>();
            for (Source s : r.sources()) {
                if (!labels.add(s.label())) {
                    throw new IllegalStateException("duplicate archive label '" + s.label() + "' for " + modId);
                }
            }
            return r;
        }

        /** {@link #build()} and hand the result to {@link DungeonBackup}. */
        public Registration register() {
            return DungeonBackup.add(build());
        }
    }
}
