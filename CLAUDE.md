# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ElotecraftAPI is a PaperMC (Minecraft server) plugin targeting Paper API `1.21.11-R0.1-SNAPSHOT` on Java 21. It is a shared library plugin: other Elotecraft plugins declare `depend: [ElotecraftAPI]` and compile against it with `provided` scope.

Multi-module Maven build (parent `pom.xml` holds versions, Paper/JUnit/MockBukkit dependencies, compiler, surefire, JaCoCo):

- `api/` — artifact `elotecraft-api`, the library plugin (package `me.angylo.elotecraftAPI`). Only library code goes here.
- `example/` — artifact `elotecraft-example`, a separate demo plugin (package `me.angylo.elotecraftExample`) that depends on `elotecraft-api` with `provided` scope, exactly like a consumer plugin. Demo code, `messages.yml` and `example.yml` live here so they never ship in the library jar.
- `duels/` — artifact `elotecraft-duels`, ElotecraftDuels (package `me.angylo.elotecraftDuels`), a real plugin consuming the API like `example/`; its notes are in `duels/CLAUDE.md`.

Library packages (under `api/src/main/java/me/angylo/elotecraftAPI/`):

- `menu/` — `Button` (item + `(player, click)` handler), `Menu` (an `InventoryHolder` GUI of buttons, `refresh` for live menus), `PaginatedMenu` (content rows + arrows in the bottom row; `centered()` keeps items in columns 1-7 below an empty top row, centering a partial row, and leaves those margins to fixed buttons), `MenuConfig` (menus from YAML; `MenuConfig.item` builds one item with placeholders for menus built in code) and `MenuListener`, which `ElotecraftAPI.onEnable` must register; without it menus do not cancel clicks.
- `CleanupListener` (root package, registered by `ElotecraftAPI`) removes a plugin's commands, chat and anvil prompts, boss bars, sidebars and holograms when it disables. New features that create per-plugin state must hook in there.
- `input/` — `ChatInput` (answer typed in chat) and `AnvilInput` (answer typed in an anvil's rename field, sent by clicking the result slot; every other click is cancelled and the anvil is emptied before it closes, so its item never reaches the player), both routed by `InputListener`.
- Every util that owns a resource (tasks, listeners, files, menus) takes the caller's `Plugin`, never the ElotecraftAPI instance, so resources die with the consumer plugin.
- `util/ConfigFile` — a YAML file in the data folder with the jar's copy as defaults. On load, keys the jar's copy has but the file lacks (a file from an older version) are copied in with their comments and the file is written once, the original kept as `<name>.bak`; user values are never changed or removed. So code can read sections with plain `getConfigurationSection` (Bukkit returns an empty section for a key that exists only in the defaults, and `getInt(path, fallback)` ignores defaults). Files the jar has no copy of (data files) are left alone.
- `storage/` — `Database`: SQLite or MySQL over HikariCP (shaded, relocated to `me.angylo.elotecraftAPI.libs.hikari`). Queries run on its own threads with `?` parameters and complete on the main thread; `runBlocking` (refused on the main thread) is for `AsyncPlayerPreLoginEvent` loads. The JDBC drivers and slf4j are **not** shaded: Paper 1.21.11 bundles `sqlite-jdbc` 3.49.1.0, `mysql-connector-j` 9.2.0 and `slf4j-api` 2.0.17 (checked in the server jar's `META-INF/libraries.list`). Never expose HikariCP types in the public API.
- `command/` — `CommandBuilder` builds a Bukkit `Command` with nested subcommands, permissions and tab completion, registered at runtime via `Bukkit.getCommandMap()` (no `plugin.yml` entry); routing lives in `CommandNode`; `Args` has parsers and suggestions. The `executes` handler runs whenever no subcommand matches, including a first argument that is not a subcommand (`/duel <player>`). `messages(Function, Function)` builds the error texts per sender (e.g. from `Messages`); nested groups inherit their parent's. Unregister by label: Paper's known-commands map forwards to Brigadier and ignores `values().remove`.
- PlaceholderAPI is an optional `provided` dependency (`softdepend` in `plugin.yml`); only `util/PlaceholderHook`'s nested class touches it, so the library loads without it.
- Experimental Paper API (`@ApiStatus.Experimental`) is used only where agreed, isolated in one class and listed here: `MenuType` in `input/AnvilInput` (the stable `HumanEntity.openAnvil` is deprecated). Propose an experimental route when it is the better way; otherwise avoid it. In 1.21.11 that is the whole `io.papermc.paper.datacomponent` package and `CustomModelDataComponent`, plus individual members elsewhere (e.g. `Commands.getDispatcher()`, some `ArgumentTypes` methods, `LifecycleEvents.TAGS`). Check with IntelliJ's "Unstable API usage" inspection; a grep cannot see member-level annotations. Use `ItemMeta.setItemModel` for custom models.
- Do not use Paper's command API (Brigadier `Commands`, `BasicCommand`, `LifecycleEvents.COMMANDS`, `JavaPlugin.registerCommand`). Commands use `CommandBuilder`.

## Build

- `mvn test` — JUnit 6 + MockBukkit (`mockbukkit-v1.21` 4.116.3, built for Paper 1.21.11). MockBukkit gaps: `Inventory#getHolder(boolean)` is unimplemented (so `MenuListener` is untested) `ItemMetaMock` drops `itemModel` when copied, `DisplayMock.setBillboard` and `ObjectiveMock.numberFormat` are unimplemented (so `Hologram` and `Sidebar` tests are reported as skipped), and `SkullMetaMock` returns an offline-mode UUID for owners. `BukkitSchedulerMock.waitAsyncTasksFinished()` keeps ticking until every scheduled task (including delayed sync ones) has run, so do not call it before asserting that a delayed task has not run yet. `Entity.teleportAsync` and `Player.Spigot.respawn` are unimplemented (duels tests use `TestPlayer`), so is `World.strikeLightningEffect`, `ServerMock.addPlayer` blocks the main thread on `AsyncPlayerPreLoginEvent` (load there with `Database.runBlocking`, never a main-thread future), `getOpenInventory().getTopInventory()` can be null, `PlayerCommandPreprocessEvent` from `dispatchCommand` has no leading `/`, `simulatePlayerMove` ignores `setTo` (assert on the returned event), and `Bukkit.getWorldContainer()` is unimplemented, `MenuType.ANVIL` builds no anvil view (`AnvilInputTest` swaps `AnvilInput.viewFactory` for a `Proxy` view), and `PlayerInventoryMock.addItem` does not fill slots holding `ItemStack.empty()` (as `Kit.apply` leaves them; Paper treats them as air), so code that must work in tests uses `firstEmpty` and `setItem`. MockBukkit does not model that on Paper `PlayerInteractEvent.setUseInteractedBlock(DENY)` also stops placing blocks against the clicked block; check block placing with real clients (e.g. mineflayer bots on a local Paper server).

## How the pieces connect

- Resource filtering is enabled, so `${version}` in `plugin.yml` is replaced with the Maven project version at build time. Any `${...}` placed in files under `src/main/resources` will be substituted the same way.
- `paper-api` is `provided` scope (the server supplies it). Any new runtime library must use the default `compile` scope so `maven-shade-plugin` (enabled in `api/pom.xml`) bundles it into the jar; consider relocating shaded packages to avoid clashes with other plugins.
- The package name is `me.angylo.elotecraftAPI` (camelCase `API`) — keep new classes under it.

## Prompt

Senior Minecraft plugin developer. Bukkit/Spigot/Paper.

Inspect existing project before changing code. Treat project code as source of truth. Never assume APIs, classes, dependencies, versions, or files exist; verify them.

Identify Minecraft version and platform. Use only compatible APIs. Avoid NMS/reflection unless required. Never invent APIs.

Reuse existing systems. Avoid duplicate implementations. Make minimal targeted changes. Don't modify unrelated code.

Use clean Java, clear separation of responsibilities, safe input/error handling. Follow existing architecture and conventions.

Keep config in config.yml when appropriate. Implement commands, permissions, tab completion, messages consistently.

Never block main thread with DB, HTTP, file I/O, or heavy work. Use async only when thread-safe; run Bukkit/Paper API operations on main thread when required. Clean up tasks/listeners/resources.

Use existing storage/database/dependencies when possible. Don't add unnecessary dependencies.

Validate input. Never hardcode secrets/API keys/passwords. Prevent injection, unsafe deserialization, command abuse, and data leaks.

For bugs: find root cause, then fix it. Check references/usages before removing or renaming code.

For new plugins: determine requirements, architecture, APIs, structure, config, commands, permissions, error handling, then implement incrementally.

Before finishing: verify references/imports, API compatibility, config, dependencies, compile/build when possible, and fix introduced errors.

Be concise. Prefer complete working code over fragments.

Core rule: act as a senior developer modifying an existing Minecraft plugin, not a code generator. Inspect first, integrate with existing code, make minimal safe changes, verify, and leave the project working.