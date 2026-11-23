# ElotecraftAPI

Shared library plugin for Elotecraft Paper plugins. One source tree, two builds:

| Minecraft | Java | Version | Build |
|---|---|---|---|
| 1.21.11 | 21 | `1.6.0` | `mvn clean install` |
| 26.3 | 25 | `1.6.0-mc26.3` | `mvn clean install -Pmc26.3` |

| Package | What |
|---|---|
| `util` | `Text` (MiniMessage), `Durations`, `Cooldowns`, `Tasks`, `Events`, `ItemBuilder` (incl. skulls), `ConfigFile`, `LocalizedFile` (per-language overrides), `Messages` (prefix, per-player language, PlaceholderAPI) |
| `menu` | `Button`, `Menu` (incl. `refresh`), `PaginatedMenu`, `MenuConfig` (menus from YAML), `MenuListener` |
| `command` | `CommandBuilder`: nested subcommands, permissions, tab completion, unregister, no `plugin.yml` entry; `Args` parsers and suggestions |
| `input` | `ChatInput`: ask a player to type something in chat; `AnvilInput`: the same in an anvil's rename field, answered by clicking the result |
| `hud` | `Bossbars` (timed bars), `Sidebar` (per-player scoreboard) |
| `hologram` | `Hologram`: floating MiniMessage text (`TextDisplay`) |
| `storage` | `Database`: async SQLite/MySQL (HikariCP), parameterized queries, transactions, results on the main thread |

The `example` module is a separate demo plugin (`/example`, `/countdown`) showing every feature.

The `duels` module is ElotecraftDuels, a production 1v1 duels plugin built on the library; see [duels/README.md](duels/README.md).

## Server

Put `elotecraft-api-<version>.jar` in `plugins/`, the build matching the server (`-mc26.3` for Paper 26.3). Add `elotecraft-example-<version>.jar` only to try the demo.

## Using it in a plugin

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.4ngylo.ElotecraftAPI</groupId>
    <artifactId>elotecraft-api</artifactId>
    <version>TAG</version>
    <scope>provided</scope>
</dependency>
```

JitPack builds the 1.21.11 version only. For Paper 26.3, run `mvn clean install -Pmc26.3` here and depend on
`me.angylo:elotecraft-api:1.6.0-mc26.3` from your local Maven repository.

`plugin.yml`:

```yaml
depend: [ElotecraftAPI]
```

Always pass your own plugin instance to the utils, so tasks, listeners and menus are cleaned up with your plugin.

## Build

```bash
mvn                          # clean package, all modules, Minecraft 1.21.11
mvn clean verify             # what CI runs; coverage in <module>/target/site/jacoco/
mvn clean verify -Pmc26.3    # the same for Minecraft 26.3 (needs JDK 25)
```
