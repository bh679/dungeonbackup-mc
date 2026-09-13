# Extraction map — Dungeon Train → Dungeon Backup

Every file named in the extraction brief, its tier, and the decision taken. Paths on the left
are relative to `dungeon-train-mc/src/main/java/games/brennan/dungeontrain/`; on the right to
`dungeonbackup-mc/src/main/java/games/brennan/dungeonbackup/`.

**Tier A** — moved and genericised. **Tier B** — stays in Dungeon Train, calls the new API.
**Tier C** — judgement call; the decision is recorded.

## Core

| DT file | Tier | Became | Notes |
|---|---|---|---|
| `data/PlayerDataPaths` | A/B split | `api/Relocation`, `api/Kind`, `api/Located`, `core/ExternalRoots` (Tier A); the class itself stays in DT slimmed (Tier B) | DT keeps its folder-name constants, `RELOCATIONS`, `root()`/`dir()`/`locate*()`; `backupsRoot()`/`externalBackupsRoot()` delegate to the registration. The `RELOCATIONS` table is passed to the registration, not hardcoded in the library. |
| `data/PlayerDataBackup` | A | `core/BackupArchiver` | Archive prefix is `<modId>-backup-`; manifest schema 2 adds `modId`; schema-1 reader kept (`V1ArchiveCompatTest`). Read side (`readEntry`/`listEntries`/`manifestVersionOf`) is public API. |
| `data/PlayerDataBackupHook` | A | `core/BackupScheduler` (debounce, per registration) + `neoforge/BackupEvents` (events) | `sources()` is gone — `Registration.sources()` derives it from the root + extra roots. Operator override moved to `api/BackupSettings`, with the consumer's legacy names honoured (`legacyOverrideNames`). |
| `data/BackupMode` | A | `core/BackupMode` | Verbatim. |
| `data/PlayerDataMigration` | A | `core/DataMigration` | Takes the registration's relocation list; the hard `UserContentMigration.runOnce()` call became the `beforeMigration` hook. Pre-migration snapshot preserved. |
| `data/PlayerDataRecovery` | A | `core/DataRecovery` | `hasLiveData` → the registration's `livenessProbe`; `dataHeldBy` → `siblingProbe`; `backupTargets` → `Registration.restoreTargets()` (same object as `sources()`, so they cannot drift). Sibling restore now copies the whole `<rootLabel>/` tree minus `backups/` (previously only relocated directories — loose root files were missed). |
| `data/CrashRunState` | C → **stays in DT** | — | Generic in shape but its only consumer is DT's crash-recovery flow, whose copy is DT's death-loop. Nothing else needs it; no public API surface with zero consumers. Can move later with the flow. |
| `data/ExperimentalWarningSeal` | C → **stays in DT** | — | Same reasoning. |
| `data/SaveFreePlayProbe` | C → **stays in DT** | — | Not portable as written (DT attachment names). |

## Tests

| DT test | Tier | Became |
|---|---|---|
| `PlayerDataBackupTest` (357) | A | `core/BackupArchiverTest` — every case, modId-aware signatures |
| `PlayerDataRecoveryTest` (258) | A | `core/DataRecoveryTest` — every case via a DT-shaped registration, plus default-probe cases and the other-mod-invisibility case |
| `PlayerDataMigrationTest` (155) | A | `core/DataMigrationTest` — every case, plus `runOnce` snapshot/once-per-session |
| `BackupModeTest` (65) | A | `core/BackupModeTest` — verbatim |
| `PlayerDataPathsTest` (115) | B | Stays in DT; its `RELOCATIONS`-vs-store-constants assertion now also checks the registration carries the same list |
| `CrashRunStateTest`, `SaveFreePlayProbeTest`, `ExperimentalWarningSealTest` | C → stay | With their classes |
| — | new | `core/V1ArchiveCompatTest` (fixture v1 archives: list / restore / prune / digest-skip), `api/RegistrationTest`, `api/BackupSettingsOverrideTest` |

## Config

| DT | Tier | Decision |
|---|---|---|
| `config/ClientDisplayConfig` `BACKUP_MODE`, `BACKUPS_PER_VERSION` + getters/setters | A | `config/BackupClientConfig` → `dungeonbackup-client.toml`. **Existing players' stored values reset to defaults (EXTERNAL / 5)** — accepted: the default is the safest rung, a reset can only turn backups *on*. |
| `CONFIRM_BUILD_RESTORE` | B | Stays in DT (it governs DT's build reconcile). |
| `-Ddungeontrain.backups` / `DUNGEONTRAIN_BACKUPS` | A | Still honoured via `Registration.legacyOverrideNames`; the library's own names (`dungeonbackup.backups` / `DUNGEONBACKUP_BACKUPS`) win when both are set. |

## UI

| DT file | Tier | Became |
|---|---|---|
| `client/ClientOptionsTab` backup rows | B | New `BACKUPS` tab in DT's options screen hosts the rows; `BACKUPS_HEADING` dropped (the tab is the heading). |
| `client/DungeonTrainClientOptionsScreen` backup widgets + `confirmClearBackups` | A | `client/BackupOptionsWidgets` — mode button, per-version slider, clear button + confirm card naming every root. |
| `client/DataRecoveryScreen` | A | `client/DataRecoveryScreen` — per registration; copy parameterised (mod name, data description, restore command). |
| `client/DataRecoveryPromptHandler` | A | `client/DataRecoveryPromptHandler` — queue across registrations; `isDevBuild()` → `promptSuppressed`. |
| `client/crash/*` (4 files) | C → stay in DT | With `CrashRunState`. |
| `client/BuilderReconcileScreen` + `PromptHandler` | B | Stay. |

## Lang

`gui.dungeontrain.data_recovery.*`, `gui.dungeontrain.options.backups*`,
`gui.dungeontrain.options.clear_backups*`, `command.dungeontrain.backup.*`,
`command.dungeontrain.restore.*` → `gui.dungeonbackup.*` / `command.dungeonbackup.*`, all 21
locales. Twelve strings per locale were hand-edited to take the mod name / data description as
placeholders and to stop naming "builds and progress" (the library does not know what the data
is) — **translator review requested** on those; the rest is a mechanical rekey.
`gui.dungeontrain.crash_recovery.*` and `options.confirm_build_restore*` stay in DT. DT adds one
key, `gui.dungeontrain.backup.data_description` ("builds and progress"), fed to the card.

## Commands

| DT | Tier | Became |
|---|---|---|
| `command/BackupCommand` (`/dtbackup`) | A | `command/BackupCommands` — `/dungeonbackup backup [mod]`, plus `/dtbackup` as DT's registered alias |
| `command/RestoreCommand` (`/dtrestore [n]`) | A | `/dungeonbackup restore [mod] [n]`, plus `/dtrestore`; `TemplateStores.reloadAll(true)` became DT's `onRestored` hook |
| `event/CommandEvents` registration lines | B | Removed from DT; the library registers on `RegisterCommandsEvent` |

## Consumers that stay in DT (Tier B)

`advancement/Global*` stores, `AdvancementRequirementOverrides`, `client/reset/ProfileItem` +
`ProfileWipe`, `player/LoadoutStore`, `DifficultyPartition`, `compat/PlayerMobLivesReset`,
`builder/BuilderSave` (trigger → `DungeonBackup.requestBackup`), `builder/relay/BuilderTemplateSource`
and `client/builder/BuilderReconcile*` (→ `DungeonBackup.archives` + `BackupArchiver.readEntry`),
`BuilderRelayReconcile`, `BuilderReconcileStartPacket`, `RebuildCommand`, `editor/PackageRegistry`,
`UserContentPaths`, `UserContentMigration` (→ `beforeMigration`), `DtpacksMigration`,
`compat/EnderChest*Bridge`.

## Ender Chest Persistence (Tier C → **included**)

ECP's per-player stash lives at **`config/enderchestpersistence/<uuid>.dat`** — inside the very
folder launchers wipe, which is worse than the brief assumed ("not in any DT backup" understated
it). DT registers that folder as an extra root (`alsoBackUp("enderchestpersistence", …)`, path
via `EnderChestStore.file(...)`'s parent behind `catch (Throwable)` like the existing bridges).
The right long-term fix is ECP registering itself with Dungeon Backup and relocating out of
`config/`; that is ECP's change, not DT's.

## Deviations from the brief, called out

1. **Crash-recovery flow stays in DT** (brief §3 listed `CrashRunState`/`ExperimentalWarningSeal` as Tier A). Decided with Brennan at plan approval.
2. **jarJar-bundled into DT first**, not external-required with a `_min_version` floor (brief §4.8). Matches DT's KeepTrim/EdibleBackpacks precedent: an external required dep that isn't listed on Modrinth/CurseForge yet would break launcher auto-install for every player. The un-bundle is a follow-up once listings are approved.
3. **Plain NeoForge MDK**, not the siblings' Architectury layout. Decided with Brennan.
4. **Config values reset** for existing players (see Config above).
5. **Recovery card title** changed from "Missing Builds and Progress" to "Missing Dungeon Train Data" (the title takes the mod name; the body keeps "builds and progress").
6. **Sibling-instance restore is broader** (whole root, not just relocated dirs) — strictly more data recovered, no less.
