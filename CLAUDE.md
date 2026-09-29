# Product Engineer — Dungeon Backup

Sibling mod of the Dungeon Train family (AIN / AIS / PlayerMob / ECP / TE / Keep Trim /
**Dungeon Backup**). Extracted from `bh679/dungeon-train-mc` in September 2026; DT is the first
consumer. See `EXTRACTION-MAP.md` for what moved, what stayed, and why.

## Quick Reference

| | |
|---|---|
| Mod id | `dungeonbackup` |
| Group | `games.brennan.dungeonbackup` |
| Loader | NeoForge 1.21.1 only (`neo_version` in `gradle.properties`), plain MDK — not Architectury |
| Version | `gradle.properties` → `mod_version` |
| Build | `./gradlew build` |
| Tests | `./gradlew test` — JUnit 5, all `@TempDir`, no Minecraft bootstrap |
| Jar | `build/libs/dungeonbackup-neoforge-<v>.jar` |
| Release | `gh workflow run release.yml -f tag=v<version>` (creates the tag; never tag manually) |
| Repo | `bh679/dungeonbackup-mc` |

## What it does

A general-purpose **player-data protection library + UI**. A mod registers a data root at
construction; the library migrates it out of `config/` if needed, takes debounced rolling zip
restore points at sensible moments, mirrors them outside the instance, prunes per mod version,
detects the "install looks factory-fresh but shouldn't" signature, and offers a title-screen
restore. The problem it exists for: modpack launchers replace `config/` on update.

## The one design rule

**A consumer must not need a code change when it adds a data folder.** Archives take the
whole registered root wholesale; the loss scan asks the consumer's probe; restores land where
the archive's labels say. The only enumeration a consumer supplies is `relocations()` — the
finite list of pre-move `config/` locations a one-shot migration drains.

## Structure

- `api/` — the consumer surface. `DungeonBackup.register(modId)…register()`, `Registration`
  (+ builder), `BackupSettings` (mode / per-version / clear, with the launch-flag override),
  `Relocation` / `Located` (the read-across-the-move model), `Source`, `Trigger`, `Kind`.
- `core/` — loader-free engine: `BackupArchiver` (zip + `manifest.json` schema 2, v1 reader
  kept), `DataMigration`, `DataRecovery`, `BackupScheduler`, `BackupMode`, `ExternalRoots`,
  `FileTrees`. Every entry point takes explicit roots — that is why the tests exist.
- `config/BackupClientConfig` — `dungeonbackup-client.toml`. Consumers do not re-declare this.
- `neoforge/BackupEvents` — the only Forge-event surface (+ command registration).
- `client/` — `DataRecoveryScreen` + `DataRecoveryPromptHandler` (title-screen card),
  `BackupOptionsWidgets` (the three controls, for a host screen).
- `command/BackupCommands` — `/dungeonbackup backup|restore [mod] [n]` + consumer aliases.

## Invariants worth knowing before touching anything

- **Never throws.** Every entry point logs and continues. A backup system that can crash the
  game is worse than no backup system.
- **Additive restore.** A file already on disk is never overwritten — unless the consumer registered a
  `RestoreMerger` for its path (`mergeOnRestore`), which combines the two; a merger failure leaves the
  file untouched. Zip-slip is guarded.
- **Archive names are the on-disk contract:** `<modId>-backup-<yyyyMMdd-HHmmss>-v<version>.zip`.
  Dungeon Train players have `dungeontrain-backup-*.zip` (schema 1) on disk in both roots;
  `V1ArchiveCompatTest` is the guard.
- **Unknown mode ⇒ default, never OFF.** Read on the server thread where nobody is watching.
- **External root:** `<AppData>/<externalDirName>/backups` — the only place no launcher
  operation on the instance can reach. Empty when no home dir resolves; that is not an error.
- **Lang:** 21 locales under `assets/dungeonbackup/lang/`, carried from Dungeon Train's
  translations with the mod name / data description parameterised. Any new key ⇒ all 21.

## Standards

SemVer in `gradle.properties`: PATCH every commit, MINOR on release. bh679 Gate workflow
applies (see `~/.claude/` rules/playbooks). Releases only via `release.yml` dispatch — it creates
the tag, GitHub Release, and publishes to Modrinth/CurseForge when `MODRINTH_PROJECT_ID` /
`CURSEFORGE_PROJECT_ID` vars + tokens are set.

Dungeon Train consumes the jar via its shared `bh679` Ivy repo: the release asset MUST stay
`dungeonbackup-neoforge-<v>.jar` (flat, no `+mc` suffix).
