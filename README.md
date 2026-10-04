# ElotecraftAPI

Shared library plugin for Elotecraft Paper plugins (Paper 1.21.11, Java 21).

| Package | What |
|---|---|
| `util` | `Text` (MiniMessage), `Durations`, `Cooldowns`, `Tasks`, `Events`, `ItemBuilder`, `ConfigFile`, `Messages` |
| `menu` | `Button`, `Menu`, `PaginatedMenu`, `MenuListener` |
| `command` | `CommandBuilder`: subcommands, permissions, tab completion, no `plugin.yml` entry |
| `storage` | `Database`: async SQLite/MySQL (HikariCP), parameterized queries, transactions, results on the main thread |

The `example` module is a separate demo plugin (`/example`, `/countdown`) showing every feature.

## Server

Put `elotecraft-api-<version>.jar` in `plugins/`. Add `elotecraft-example-<version>.jar` only to try the demo.

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

`plugin.yml`:

```yaml
depend: [ElotecraftAPI]
```

Always pass your own plugin instance to the utils, so tasks, listeners and menus are cleaned up with your plugin.

## Build

```bash
mvn            # clean package, all modules
mvn verify     # what CI runs; coverage in <module>/target/site/jacoco/
```
