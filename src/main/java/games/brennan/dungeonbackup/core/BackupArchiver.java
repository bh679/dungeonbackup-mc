package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.api.RestoreMerger;
import games.brennan.dungeonbackup.api.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rolling restore points for one mod's player data.
 *
 * <p>Relocating the data out of {@code config/} stops the known way of losing it. Backups are the
 * belt to that pair of braces: launchers have deleted worlds and whole instance trees before, and
 * a player who loses their data to <em>any</em> cause should have something to go back to. One
 * archive per trigger, at most, and only when something changed.</p>
 *
 * <p>Archives land in the mod's backups folder as {@code <modId>-backup-<yyyyMMdd-HHmmss>-v<version>.zip}.
 * Each holds a top-level {@code manifest.json} — schema version, mod id, mod version, timestamp,
 * reason, file count and a content digest — and then every backed-up file under a per-source
 * prefix, so one archive can span several trees without their names colliding.</p>
 *
 * <p><b>Namespacing.</b> Every archive belongs to exactly one registering mod: the filename
 * prefix says which, and so does {@code modId} in a schema-2 manifest. Schema-1 archives (written
 * by Dungeon Train before this library existed) carry no {@code modId}; they are owned by whichever
 * mod's prefix matches their name, which for those archives is {@code dungeontrain}. Two mods
 * sharing a folder can therefore list, prune and clear independently.</p>
 *
 * <p>Writes are atomic: the archive is built on {@code .tmp} and moved onto its real name only
 * once complete. A failure therefore leaves the previous backups byte-identical rather than
 * replacing a good archive with a truncated one.</p>
 *
 * <p>Free of {@code FMLPaths} and Minecraft types on purpose — every entry point takes explicit
 * roots so the whole class is drivable from JUnit with {@code @TempDir}.</p>
 */
public final class BackupArchiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("DungeonBackup");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Sits between the mod id and the timestamp in every archive name. */
    static final String NAME_MARKER = "-backup-";
    static final String SUFFIX = ".zip";
    static final String TMP_SUFFIX = ".tmp";
    public static final String MANIFEST_ENTRY = "manifest.json";

    /**
     * Schema of the manifest written into each archive. 2 adds {@code modId}. Readers accept 1
     * and 2 — bump only on an incompatible layout change, and keep the old reader.
     */
    static final int SCHEMA_VERSION = 2;

    /** Separates the timestamp from the mod version in an archive's name. */
    static final String VERSION_MARKER = "-v";

    /** Archives kept per mod version when no other figure is supplied. */
    public static final int DEFAULT_PER_VERSION = 5;

    /**
     * Ceiling on one mod's archives in a folder, applied after the per-version cap.
     *
     * <p>A backstop, not the rule: "N per version" is unbounded across many versions — twenty
     * versions at five each is a hundred archives — so a long-lived install would otherwise fill a
     * disk one release at a time. Enforced oldest-first, and the very oldest archive (the
     * pre-migration snapshot) is never dropped.</p>
     */
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /** What {@link #create} did. {@code archive} is empty when nothing needed backing up. */
    public record Result(Optional<Path> archive, int fileCount, long totalBytes, String digest) {
        public boolean wrote() { return archive.isPresent(); }
    }

    private BackupArchiver() {}

    /** {@code <modId>-backup-} — the filename prefix every archive of {@code modId} carries. */
    public static String prefix(String modId) {
        return modId + NAME_MARKER;
    }

    /**
     * Write a backup of {@code sources} into {@code backupsRoot}, unless nothing has changed since
     * the newest existing archive of this mod.
     *
     * <p>The change check is a digest over every file's archive path, size and last-modified time —
     * cheap (a directory walk, no file reads) and enough to tell an idle launch from one where the
     * player made something. An idle launch therefore costs a walk, not a zip.</p>
     *
     * @param reason recorded in the manifest, e.g. {@code "pre-migration"} or {@code "world-load"}
     * @param modVersion recorded in the manifest so a restore can tell which build wrote it
     */
    public static synchronized Result create(
            Path backupsRoot, String modId, List<Source> sources, String reason, String modVersion)
            throws IOException {
        return create(backupsRoot, modId, sources, reason, modVersion, DEFAULT_PER_VERSION);
    }

    /** As above, keeping at most {@code perVersion} archives per mod version. */
    public static synchronized Result create(
            Path backupsRoot, String modId, List<Source> sources, String reason, String modVersion,
            int perVersion) throws IOException {

        List<Entry> entries = collect(sources);
        if (entries.isEmpty()) {
            LOGGER.debug("[DungeonBackup] {}: nothing to back up.", modId);
            return new Result(Optional.empty(), 0, 0L, "");
        }

        String digest = digestOf(entries);
        String newestDigest = newestDigest(backupsRoot, modId);
        if (digest.equals(newestDigest)) {
            LOGGER.debug("[DungeonBackup] {}: unchanged since the newest archive — skipping.", modId);
            return new Result(Optional.empty(), entries.size(), totalBytes(entries), digest);
        }

        Files.createDirectories(backupsRoot);
        Path archive = uniquePath(backupsRoot, modId, LocalDateTime.now().format(STAMP), modVersion);
        Path tmp = archive.resolveSibling(archive.getFileName() + TMP_SUFFIX);
        try {
            writeZip(tmp, modId, entries, digest, reason, modVersion);
            replace(tmp, archive);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }

        long bytes = totalBytes(entries);
        LOGGER.info("[DungeonBackup] {}: wrote {} ({} file(s), {} KiB, reason={})",
            modId, archive.getFileName(), entries.size(), bytes / 1024, reason);
        prune(backupsRoot, modId, perVersion);
        return new Result(Optional.of(archive), entries.size(), bytes, digest);
    }

    /**
     * Copy {@code archive} into {@code externalRoot} and thin that folder with the same ladder.
     *
     * <p>Best-effort by design: the in-instance archive has already been written and verified by
     * the time this runs, so a read-only home directory, a full disk or a sandbox that forbids the
     * path must degrade to a warning rather than turn a successful backup into a failed one.</p>
     *
     * @return true when the copy landed
     */
    public static synchronized boolean mirror(Path archive, Path externalRoot, String modId, int perVersion) {
        if (archive == null || externalRoot == null || !Files.isRegularFile(archive)) return false;
        try {
            Files.createDirectories(externalRoot);
            Path target = externalRoot.resolve(archive.getFileName().toString());
            if (Files.exists(target)) return true; // already mirrored; nothing to redo
            Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
            try {
                Files.copy(archive, tmp, StandardCopyOption.REPLACE_EXISTING);
                replace(tmp, target);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            LOGGER.info("[DungeonBackup] {}: mirrored {} outside the instance to {}",
                modId, archive.getFileName(), externalRoot);
            prune(externalRoot, modId, perVersion);
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonBackup] {}: couldn't mirror to {} ({}). The in-instance backup "
                + "is unaffected.", modId, externalRoot, e.toString());
            return false;
        }
    }

    /**
     * Every archive of {@code modId} in {@code backupsRoot}, newest first. Never throws — an
     * unreadable folder yields an empty list, because "no backups" must not take the game down.
     */
    public static List<Path> listArchives(Path backupsRoot, String modId) {
        if (backupsRoot == null || !Files.isDirectory(backupsRoot)) return List.of();
        String prefix = prefix(modId);
        try (var stream = Files.list(backupsRoot)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(prefix) && n.endsWith(SUFFIX);
                })
                // Sort on the parsed timestamp, not the whole filename: the name also carries the
                // mod version, and version numbers do not sort chronologically. Unlike mtime, the
                // stamp survives a file copy — which the out-of-instance mirror depends on.
                .sorted(NEWEST_FIRST)
                .toList();
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonBackup] {}: couldn't list {}: {}", modId, backupsRoot, e.toString());
            return List.of();
        }
    }

    private static final Comparator<Path> NEWEST_FIRST =
        Comparator.comparing(BackupArchiver::stampOf)
            .thenComparing((Path p) -> p.getFileName().toString()).reversed();

    /**
     * Read one file out of an archive, without restoring anything.
     *
     * <p>For callers that want a single file back rather than a whole install — Dungeon Train's
     * build reconcile reads one template out of an archive, uploads it, and writes nothing to disk.
     * Deliberately not {@link #restore}: restoring is additive and lands files in the live store,
     * which is the wrong answer for a file the player may have deleted on purpose.</p>
     *
     * <p>Never throws. A missing archive, a missing entry, or an unreadable zip is empty — the caller
     * moves on to the next archive.</p>
     *
     * @param entryName the full entry name, {@code "<label>/<relative path>"} with {@code /}
     *                  separators, exactly as {@link #create} writes it
     */
    public static Optional<byte[]> readEntry(Path archive, String entryName) {
        if (archive == null || entryName == null || entryName.isEmpty()) return Optional.empty();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) return Optional.empty();
            try (var in = zip.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonBackup] Couldn't read {} from {}: {}",
                entryName, archive.getFileName(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Every file entry in an archive, by name.
     *
     * <p>What an archive <em>held</em> is evidence in its own right: a file that is in no backup and
     * not on disk was never here, while one that is in a backup and not on disk was. Never throws —
     * an unreadable archive is empty.</p>
     */
    public static List<String> listEntries(Path archive) {
        if (archive == null) return List.of();
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (!entry.isDirectory()) names.add(entry.getName());
            }
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonBackup] Couldn't list {}: {}", archive.getFileName(), e.toString());
            return List.of();
        }
        return List.copyOf(names);
    }

    /**
     * The mod version an archive's manifest records, or empty.
     *
     * <p>{@link #versionOf} reads the filename, which is enough to group archives for pruning but is
     * not evidence — a copied or renamed file carries whatever name it was given. This reads what the
     * writer actually stamped inside, which is what to show a player being told where a recovered
     * file came from.</p>
     */
    public static Optional<String> manifestVersionOf(Path archive) {
        return manifestString(archive, "modVersion");
    }

    /**
     * The mod id an archive's manifest records. Empty for a schema-1 archive, which predates the
     * field — those are owned by filename prefix instead.
     */
    public static Optional<String> manifestModIdOf(Path archive) {
        return manifestString(archive, "modId");
    }

    /** The manifest's {@code schemaVersion}, or empty when unreadable. */
    public static Optional<Integer> manifestSchemaOf(Path archive) {
        return readEntry(archive, MANIFEST_ENTRY)
            .map(bytes -> jsonInt(new String(bytes, StandardCharsets.UTF_8), "schemaVersion"))
            .filter(v -> v > 0);
    }

    private static Optional<String> manifestString(Path archive, String key) {
        return readEntry(archive, MANIFEST_ENTRY)
            .map(bytes -> jsonString(new String(bytes, StandardCharsets.UTF_8), key))
            .filter(v -> !v.isEmpty());
    }

    /**
     * Restore an archive into {@code targets}, keyed by the source labels it was written with.
     *
     * <p>Additive, like every other write in this area: a file that already exists on disk is left
     * alone. Restoring can therefore only ever put content back, never replace something the player
     * has since made. Entries whose label has no target, and any entry that tries to escape its
     * target directory, are skipped.</p>
     *
     * @return the number of files actually written
     */
    public static synchronized int restore(Path archive, List<Source> targets) throws IOException {
        return restore(archive, targets, RestoreMerger.Resolver.NONE);
    }

    /**
     * As above, but an entry whose file already exists is merged into it when {@code mergers} has
     * a merger for it (see {@link RestoreMerger}); otherwise it is skipped as before.
     */
    public static synchronized int restore(Path archive, List<Source> targets,
                                           RestoreMerger.Resolver mergers) throws IOException {
        int written = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory() || MANIFEST_ENTRY.equals(entry.getName())) continue;
                Path target = targetFor(entry.getName(), targets);
                if (target == null) continue;
                if (Files.exists(target)) {
                    if (mergeExisting(zip, entry, target, mergers)) written++;
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
                written++;
            }
        }
        LOGGER.info("[DungeonBackup] Restored {} file(s) from {}", written, archive.getFileName());
        return written;
    }

    /**
     * Where one archive entry lands on disk, or {@code null} when it should be skipped.
     *
     * <p>Entry names come from a zip file, which is player-supplied data — a crafted archive could
     * carry {@code ../} segments or an absolute path (zip-slip). The resolved path is therefore
     * required to stay inside its target directory.</p>
     */
    /** Merge one entry into its existing file, if a merger is registered for it. Never throws. */
    private static boolean mergeExisting(ZipFile zip, ZipEntry entry, Path target,
                                         RestoreMerger.Resolver mergers) {
        String name = entry.getName();
        int slash = name.indexOf('/');
        var merger = mergers.find(name.substring(0, slash), name.substring(slash + 1));
        if (merger.isEmpty()) return false;
        byte[] backedUp;
        try (var in = zip.getInputStream(entry)) {
            backedUp = in.readAllBytes();
        } catch (IOException e) {
            LOGGER.warn("[DungeonBackup] Couldn't read {} to merge: {}", name, e.toString());
            return false;
        }
        return FileTrees.mergeInto(target, backedUp, merger.get());
    }

    static Path targetFor(String entryName, List<Source> targets) {
        int slash = entryName.indexOf('/');
        if (slash <= 0 || slash == entryName.length() - 1) return null;
        String label = entryName.substring(0, slash);
        String relative = entryName.substring(slash + 1);
        for (Source target : targets) {
            if (!target.label().equals(label)) continue;
            Path root = target.dir().toAbsolutePath().normalize();
            Path resolved = root.resolve(relative).normalize();
            return resolved.startsWith(root) ? resolved : null;
        }
        return null;
    }

    // ---- Internals ----

    /** One file to archive: where it is now, and the name it takes inside the zip. */
    private record Entry(Path file, String entryName, long size, long modified) {}

    private static List<Entry> collect(List<Source> sources) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (Source source : sources) {
            Path dir = source.dir();
            if (dir == null || !Files.isDirectory(dir)) continue;
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (source.excludes(dir, file)) return FileVisitResult.CONTINUE;
                    String relative = dir.relativize(file).toString().replace('\\', '/');
                    entries.add(new Entry(file, source.label() + "/" + relative,
                        attrs.size(), attrs.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // An unreadable file must not abort the backup — the rest is still worth saving.
                    LOGGER.warn("[DungeonBackup] Skipping unreadable {}: {}", file, exc.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        entries.sort(Comparator.comparing(Entry::entryName));
        return entries;
    }

    private static long totalBytes(List<Entry> entries) {
        long total = 0;
        for (Entry e : entries) total += e.size();
        return total;
    }

    /** SHA-256 over each entry's name, size and mtime — a fingerprint of the tree, without reads. */
    static String digestOf(List<Entry> entries) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; if it ever isn't there, degrade to "always back up".
            return "";
        }
        for (Entry e : entries) {
            md.update(e.entryName().getBytes(StandardCharsets.UTF_8));
            md.update((e.size() + ":" + e.modified()).getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : md.digest()) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                     .append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    /** The {@code digest} recorded in the newest archive's manifest, or {@code ""} if unreadable. */
    static String newestDigest(Path backupsRoot, String modId) {
        List<Path> archives = listArchives(backupsRoot, modId);
        if (archives.isEmpty()) return "";
        Path newest = archives.get(0);
        try (ZipFile zip = new ZipFile(newest.toFile())) {
            ZipEntry manifest = zip.getEntry(MANIFEST_ENTRY);
            if (manifest == null) return "";
            String json = new String(zip.getInputStream(manifest).readAllBytes(), StandardCharsets.UTF_8);
            return jsonString(json, "digest");
        } catch (IOException e) {
            LOGGER.warn("[DungeonBackup] Couldn't read {}: {}", newest.getFileName(), e.toString());
            return "";
        }
    }

    /**
     * Pull one string value out of the manifest. The manifest is written by this class in a fixed
     * shape, so a two-line reader beats pulling in a JSON parse for one field.
     */
    static String jsonString(String json, String key) {
        String needle = "\"" + key + "\": \"";
        int start = json.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    static int jsonInt(String json, String key) {
        String needle = "\"" + key + "\": ";
        int start = json.indexOf(needle);
        if (start < 0) return 0;
        start += needle.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return end > start ? Integer.parseInt(json.substring(start, end)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code <modId>-backup-<stamp>-v<version>.zip}.
     *
     * <p>The version is a SUFFIX, deliberately. {@link #listArchives} and {@link #stampOf} read the
     * timestamp at a fixed offset after the name marker, and version numbers do not sort
     * lexicographically — {@code 0.10.0} sorts before {@code 0.9.0}. Putting the version first
     * would have silently scrambled chronological order the first time a minor rolled over ten.
     * As a suffix it is inert to both, and archives written before versioned names still parse.</p>
     */
    private static Path uniquePath(Path backupsRoot, String modId, String stamp, String modVersion) {
        String version = sanitiseVersion(modVersion);
        String tail = version.isEmpty() ? "" : VERSION_MARKER + version;
        String prefix = prefix(modId);
        Path candidate = backupsRoot.resolve(prefix + stamp + tail + SUFFIX);
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = backupsRoot.resolve(prefix + stamp + "-" + n + tail + SUFFIX);
        }
        return candidate;
    }

    /** Strip anything that would break a filename or the name grammar above. */
    private static String sanitiseVersion(String modVersion) {
        if (modVersion == null) return "";
        String cleaned = modVersion.trim().replaceAll("[^A-Za-z0-9._]", "");
        return cleaned.equals("?") ? "" : cleaned;
    }

    private static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeZip(Path zipPath, String modId, List<Entry> entries,
                                 String digest, String reason, String modVersion) throws IOException {
        try (OutputStream raw = Files.newOutputStream(zipPath);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            writeManifest(zip, modId, entries, digest, reason, modVersion);
            for (Entry e : entries) {
                ZipEntry entry = new ZipEntry(e.entryName());
                entry.setTime(e.modified());
                zip.putNextEntry(entry);
                try {
                    Files.copy(e.file(), zip);
                } catch (IOException io) {
                    // The file vanished or turned unreadable between the walk and the copy. Better
                    // an archive missing one file than no archive at all.
                    LOGGER.warn("[DungeonBackup] Couldn't archive {}: {}", e.file(), io.toString());
                }
                zip.closeEntry();
            }
        }
    }

    private static void writeManifest(ZipOutputStream zip, String modId, List<Entry> entries,
                                      String digest, String reason, String modVersion) throws IOException {
        zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
        sb.append("  \"modId\": \"").append(escape(modId)).append("\",\n");
        sb.append("  \"modVersion\": \"").append(escape(modVersion)).append("\",\n");
        sb.append("  \"createdAt\": \"")
            .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        sb.append("  \"reason\": \"").append(escape(reason)).append("\",\n");
        sb.append("  \"digest\": \"").append(escape(digest)).append("\",\n");
        sb.append("  \"fileCount\": ").append(entries.size()).append(",\n");
        sb.append("  \"totalBytes\": ").append(totalBytes(entries)).append("\n");
        sb.append("}\n");
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Thin one mod's archive set: at most {@code perVersion} archives per mod version, then the
     * byte cap. Only {@code modId}'s archives are touched — another mod's in the same folder are
     * invisible to this pass.
     *
     * <p>Version-scoped rather than time-scoped so the rule is one a player can predict — "five per
     * version" is inspectable from the filenames, where the previous count-plus-day ladder left no
     * way to tell why any particular archive had survived.</p>
     *
     * <p>The single oldest archive is kept whatever the rules say. It is the snapshot taken before
     * the very first migration, and the only record of what the install looked like before the mod
     * touched it.</p>
     */
    static void prune(Path backupsRoot, String modId, int perVersion) {
        int cap = Math.max(1, perVersion);
        List<Path> archives = listArchives(backupsRoot, modId); // newest first
        if (archives.size() <= 1) return;
        Path oldest = archives.get(archives.size() - 1);

        // Newest-first within each version, so the survivors of a group are its most recent.
        Map<String, Integer> keptPerVersion = new LinkedHashMap<>();
        List<Path> keep = new ArrayList<>();
        for (Path archive : archives) {
            String version = versionOf(archive);
            int kept = keptPerVersion.getOrDefault(version, 0);
            if (kept < cap) {
                keep.add(archive);
                keptPerVersion.put(version, kept + 1);
            }
        }
        if (!keep.contains(oldest)) keep.add(oldest);

        // Then the size backstop, dropping the oldest kept archive first but never the very oldest.
        long total = 0;
        List<Path> byNewest = new ArrayList<>(keep);
        byNewest.sort(NEWEST_FIRST);
        List<Path> finalKeep = new ArrayList<>();
        for (Path archive : byNewest) {
            long size = sizeOf(archive);
            if (archive.equals(oldest) || total + size <= MAX_TOTAL_BYTES) {
                finalKeep.add(archive);
                total += size;
            }
        }

        for (Path archive : archives) {
            if (finalKeep.contains(archive)) continue;
            delete(archive, "pruned");
        }
    }

    /** Total bytes of {@code modId}'s archives in {@code root}. Zero for a folder that isn't there. */
    public static long totalSize(Path root, String modId) {
        long total = 0;
        for (Path archive : listArchives(root, modId)) total += sizeOf(archive);
        return total;
    }

    /** What {@link #clear} did. {@code failures} names archives that could not be removed. */
    public record ClearResult(int deleted, long bytesFreed, List<String> failures) {
        public boolean clean() { return failures.isEmpty(); }
    }

    /**
     * Delete every archive of {@code modId} in {@code root}.
     *
     * <p>Only files matching the backup naming are touched — {@link #listArchives} is the same
     * filter used everywhere else — so pointing this at a folder holding anything of the player's
     * cannot take it with them. A file that will not delete is collected and the loop continues,
     * because stopping halfway would leave the player with neither the space nor a clear report.</p>
     */
    public static synchronized ClearResult clear(Path root, String modId) {
        int deleted = 0;
        long freed = 0;
        List<String> failures = new ArrayList<>();
        for (Path archive : listArchives(root, modId)) {
            long size = sizeOf(archive);
            if (delete(archive, "cleared")) {
                deleted++;
                freed += size;
            } else {
                failures.add(archive.getFileName().toString());
            }
        }
        return new ClearResult(deleted, freed, List.copyOf(failures));
    }

    /** Remove one archive, logging either way. {@code why} appears in the log line. */
    private static boolean delete(Path archive, String why) {
        try {
            Files.deleteIfExists(archive);
            LOGGER.info("[DungeonBackup] {} {}", why, archive.getFileName());
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonBackup] Couldn't remove {}: {}", archive.getFileName(), e.toString());
            return false;
        }
    }

    /**
     * A byte count as the player should read it — {@code "1.2 GB"}, {@code "512 KB"}, {@code "0 B"}.
     *
     * <p>Decimal MB/GB rather than the KiB/MiB used in the logs: this goes on a button, and the
     * figure a player compares against is the one their file manager shows.</p>
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * The {@code yyyyMMdd-HHmmss} stamp in an archive's name, or {@code ""} when it has none.
     *
     * <p>Sorting keys off this rather than the whole filename so that anything appended after the
     * stamp — the mod version, a de-duplication counter — cannot reorder history. Mod ids cannot
     * contain {@code -}, so the first {@code -backup-} in the name is always the marker.</p>
     */
    static String stampOf(Path archive) {
        String name = archive.getFileName().toString();
        int marker = name.indexOf(NAME_MARKER);
        if (marker < 0) return "";
        String rest = name.substring(marker + NAME_MARKER.length());
        return rest.length() >= 15 ? rest.substring(0, 15) : "";
    }

    /**
     * The mod version an archive was written by, read from its name. {@code ""} for archives from
     * before versioned names, which is a real group of its own rather than an error.
     */
    static String versionOf(Path archive) {
        String name = archive.getFileName().toString();
        if (!name.endsWith(SUFFIX)) return "";
        int nameMarker = name.indexOf(NAME_MARKER);
        int marker = name.lastIndexOf(VERSION_MARKER);
        if (nameMarker < 0 || marker < nameMarker + NAME_MARKER.length()) return "";
        return name.substring(marker + VERSION_MARKER.length(), name.length() - SUFFIX.length());
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
