# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ElotecraftAPI is a PaperMC (Minecraft server) plugin targeting Paper API `1.21.11-R0.1-SNAPSHOT` on Java 21. It is a shared library plugin: other Elotecraft plugins declare `depend: [ElotecraftAPI]` and compile against it with `provided` scope.

Multi-module Maven build (parent `pom.xml` holds versions, Paper/JUnit/MockBukkit dependencies, compiler, surefire, JaCoCo):

- `api/` — artifact `elotecraft-api`, the library plugin (package `me.angylo.elotecraftAPI`). Only library code goes here.
- `example/` — artifact `elotecraft-example`, a separate demo plugin (package `me.angylo.elotecraftExample`) that depends on `elotecraft-api` with `provided` scope, exactly like a consumer plugin. Demo code, `messages.yml` and `example.yml` live here so they never ship in the library jar.

Library packages (under `api/src/main/java/me/angylo/elotecraftAPI/`):

- `util/` — static helpers: `Text`, `Durations`, `Cooldowns`, `Tasks`, `Events`, `ItemBuilder`, `ConfigFile`, `Messages`.
- `menu/` — `Button` (item + `(player, click)` handler), `Menu` (an `InventoryHolder` GUI of buttons), `PaginatedMenu` (content rows + arrows in the bottom row) and `MenuListener`, which `ElotecraftAPI.onEnable` must register; without it menus do not cancel clicks.
- Every util that owns a resource (tasks, listeners, files, menus) takes the caller's `Plugin`, never the ElotecraftAPI instance, so resources die with the consumer plugin.
- `command/` — `CommandBuilder` builds a Bukkit `Command` with subcommands, permissions and tab completion, registered at runtime via `Bukkit.getCommandMap()` (no `plugin.yml` entry).
- Do not use experimental Paper API (`@ApiStatus.Experimental`). In 1.21.11 that is the whole `io.papermc.paper.datacomponent` package and `CustomModelDataComponent`, plus individual members elsewhere (e.g. `Commands.getDispatcher()`, some `ArgumentTypes` methods, `LifecycleEvents.TAGS`). Check with IntelliJ's "Unstable API usage" inspection; a grep cannot see member-level annotations. Use `ItemMeta.setItemModel` for custom models.
- Do not use Paper's command API (Brigadier `Commands`, `BasicCommand`, `LifecycleEvents.COMMANDS`, `JavaPlugin.registerCommand`). Commands use `CommandBuilder`.

## Build

- `mvn` — default goal is `clean package` for all modules; jars land in `api/target/elotecraft-api-1.0-SNAPSHOT.jar` and `example/target/elotecraft-example-1.0-SNAPSHOT.jar`. Drop them into a Paper server's `plugins/` folder.
- `mvn compile` — fast compile check. `mvn -pl api test` — one module only.
- `mvn verify` — what CI (`.github/workflows/build.yml`) runs. JaCoCo reports land in `<module>/target/site/jacoco/index.html`.
- Publishing: JitPack builds tags (`jitpack.yml`); consumers use `com.github.4ngylo.ElotecraftAPI:elotecraft-api:<tag>`.
- `mvn test` — JUnit 6 + MockBukkit (`mockbukkit-v1.21` 4.116.3, built for Paper 1.21.11). MockBukkit gaps: `Inventory#getHolder(boolean)` is unimplemented (so `MenuListener` is untested) and `ItemMetaMock` drops `itemModel` when copied.

## How the pieces connect

- `api/src/main/resources/plugin.yml` is the Paper plugin descriptor (the example has its own). Its `main:` must match the fully qualified name of the `JavaPlugin` subclass (`me.angylo.elotecraftAPI.ElotecraftAPI`) — update it if the class is renamed or moved. Commands and permissions declared for Bukkit-style registration go here too.
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