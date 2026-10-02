# Dungeon Backup

Player-data protection for NeoForge 1.21.1 mods.

Modpack launchers replace `config/` when a pack updates. If your mod keeps anything a player
accumulates in there — builds, cross-world progress, stashes — one "Update Pack" click deletes
it. Dungeon Backup is the answer, extracted from [Dungeon Train](https://github.com/bh679/dungeon-train-mc)
where it was built against real player support incidents:

- **Relocation** — a one-shot migration of your legacy `config/` locations into
  `<gameDir>/<yourmod>/`, which launchers never touch. Cannot lose data by construction.
- **Restore points** — debounced zip snapshots of the whole data root at world load, player
  death, session end and whenever you say so; content-digest skip when nothing changed;
  atomic writes; `N` kept per mod version with a 512 MB ceiling; the oldest never dropped.
- **Off-instance mirror** — every archive is copied to the OS app-data folder, the only place
  a delete-and-reinstall can't reach.
- **Recovery** — an install that looks factory-fresh but shouldn't gets a title-screen card
  listing every place its data still exists (own backups, off-instance mirror, sibling instance
  folders on the same machine) and a one-click additive restore. A consumer can suppress the
  card and restore by itself instead. There are no commands: backups and restores are never run
  by a player.
- **Settings** — one client config (`dungeonbackup-client.toml`) for every consumer, an
  Options widget set you can host in your own screen, and `-Ddungeonbackup.backups=off` for
  dedicated servers.

## Using it

```java
// In your mod constructor:
DungeonBackup.register("mymod")
    .displayName("My Mod")
    .modVersion(() -> MY_VERSION)
    .dataRoot(FMLPaths.GAMEDIR.get().resolve("mymod"))
    .relocate("mymod-progress", "progress", Kind.DIRECTORY)   // legacy config/ locations
    .onRestored(MyStores::reload)
    .register();
```

Declare it in `neoforge.mods.toml` as `type="required"`, `ordering="AFTER"`, `side="BOTH"`.
A new sub-folder under your data root needs nothing further — the whole root is archived.

Full API: `games.brennan.dungeonbackup.api.Registration.Builder`.

## Building

```
./gradlew build test
```

Java 21, NeoForge 21.1.228. Tests are pure `@TempDir` JUnit — no Minecraft bootstrap.

## License

PolyForm Shield 1.0.0 — see `LICENSE`.
