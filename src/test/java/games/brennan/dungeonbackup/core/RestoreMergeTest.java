package games.brennan.dungeonbackup.core;

import games.brennan.dungeonbackup.TestRegistrations;
import games.brennan.dungeonbackup.api.Registration;
import games.brennan.dungeonbackup.api.RestoreMerger;
import games.brennan.dungeonbackup.api.Source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restores that combine a backed-up file with the one already on disk, for paths a consumer
 * registered with {@code mergeOnRestore}. The case this exists for: a progress file the consumer
 * regenerates on every world join, which a skip-if-exists restore would keep instead of the backup.
 */
class RestoreMergeTest {

    @TempDir
    Path tmp;

    /** Comma-separated ids, merged as a sorted union — a stand-in for an advancement list. */
    private static final RestoreMerger UNION = (live, backedUp) -> {
        TreeSet<String> ids = new TreeSet<>();
        for (byte[] side : List.of(live, backedUp)) {
            String text = new String(side, StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) ids.addAll(List.of(text.split(",")));
        }
        return Optional.of(String.join(",", ids).getBytes(StandardCharsets.UTF_8));
    };

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path data() { return tmp.resolve("data"); }

    private List<Source> rootSource() {
        return List.of(new Source("dungeontrain", data(), java.util.Set.of("backups")));
    }

    private Path archiveOf(String achievements, String build) throws IOException {
        write(data().resolve("achievements/uuid.json"), achievements);
        write(data().resolve("user/a.nbt"), build);
        Path archive = BackupArchiver.create(tmp.resolve("backups"), "dungeontrain", rootSource(), "t", "1")
            .archive().orElseThrow();
        Files.delete(data().resolve("achievements/uuid.json"));
        Files.delete(data().resolve("user/a.nbt"));
        return archive;
    }

    private static RestoreMerger.Resolver achievementsOnly(RestoreMerger merger) {
        return (label, path) -> label.equals("dungeontrain") && path.startsWith("achievements/")
            ? Optional.of(merger) : Optional.empty();
    }

    @Test
    void anExistingFileWithAMergerIsCombinedNotSkipped() throws IOException {
        Path archive = archiveOf("a,b,c", "old build");
        // The loss, then a world join: the consumer wrote a fresh, near-empty file first.
        write(data().resolve("achievements/uuid.json"), "d");
        write(data().resolve("user/a.nbt"), "new build");

        int written = BackupArchiver.restore(archive, rootSource(), achievementsOnly(UNION));

        assertEquals(1, written);
        assertEquals("a,b,c,d", Files.readString(data().resolve("achievements/uuid.json")));
        assertEquals("new build", Files.readString(data().resolve("user/a.nbt")),
            "files without a merger keep the never-overwrite rule");
    }

    @Test
    void aMergerThatThrowsLeavesTheLiveFileUntouched() throws IOException {
        Path archive = archiveOf("a,b,c", "build");
        write(data().resolve("achievements/uuid.json"), "d");
        RestoreMerger broken = (live, backedUp) -> { throw new IOException("bad json"); };

        int written = BackupArchiver.restore(archive, rootSource(), achievementsOnly(broken));

        assertEquals(1, written, "only the missing build is written");
        assertEquals("d", Files.readString(data().resolve("achievements/uuid.json")));
        assertFalse(Files.exists(data().resolve("achievements/uuid.json.merge.tmp")));
    }

    @Test
    void anEmptyOrUnchangedMergeIsNotCountedAsWritten() throws IOException {
        Path archive = archiveOf("a", "build");
        write(data().resolve("achievements/uuid.json"), "a");
        write(data().resolve("user/a.nbt"), "build");

        assertEquals(0, BackupArchiver.restore(archive, rootSource(), achievementsOnly(UNION)));
        assertEquals(0, BackupArchiver.restore(archive, rootSource(),
            achievementsOnly((live, backedUp) -> Optional.empty())));
    }

    @Test
    void theOldTwoArgumentRestoreStillOnlyAdds() throws IOException {
        Path archive = archiveOf("a,b,c", "build");
        write(data().resolve("achievements/uuid.json"), "d");

        BackupArchiver.restore(archive, rootSource());

        assertEquals("d", Files.readString(data().resolve("achievements/uuid.json")));
    }

    @Test
    void registrationGlobsMatchWithinAFolderOnly() {
        Registration r = Registration.builder("x").dataRoot(tmp).rootLabel("root")
            .mergeOnRestore("root", "achievements/*.json", UNION)
            .mergeOnRestore("root", "narrative/**", UNION)
            .build();

        assertTrue(r.mergerFor("root", "achievements/uuid.json").isPresent());
        assertTrue(r.mergerFor("root", "achievements\\uuid.json").isPresent(), "host separators normalise");
        assertFalse(r.mergerFor("root", "achievements/deep/uuid.json").isPresent());
        assertFalse(r.mergerFor("root", "achievements/uuid.jsonx").isPresent());
        assertFalse(r.mergerFor("other", "achievements/uuid.json").isPresent());
        assertTrue(r.mergerFor("root", "narrative/a/b.json").isPresent());
        assertFalse(r.mergerFor("root", "user/a.nbt").isPresent());
    }

    @Test
    void aSiblingRestoreMergesInBothLayouts() throws IOException {
        Path instance = tmp.resolve("instance");
        Registration reg = TestRegistrations.dungeonTrainLike(instance)
            .mergeOnRestore("dungeontrain", "achievements/*.json", UNION)
            .build();
        Path live = instance.resolve("dungeontrain/achievements");
        write(live.resolve("new.json"), "z");
        write(live.resolve("old.json"), "y");
        Path sibling = tmp.resolve("sibling");
        write(sibling.resolve("dungeontrain/achievements/new.json"), "a,b");
        write(sibling.resolve("config/dungeontrain-achievements/old.json"), "c");

        int written = DataRecovery.restore(reg,
            new DataRecovery.Candidate(DataRecovery.Kind.SIBLING_INSTANCE, sibling, "progress"));

        assertEquals(2, written);
        assertEquals("a,b,z", Files.readString(live.resolve("new.json")));
        assertEquals("c,y", Files.readString(live.resolve("old.json")));
    }
}
