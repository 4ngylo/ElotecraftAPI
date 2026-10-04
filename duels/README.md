# ElotecraftDuels

1v1 duels for Paper 1.21.11, built on ElotecraftAPI: challenges with kit and arena menus, matchmaking
queues, spectators, rematches, statistics, build kits, Vault rewards and PlaceholderAPI placeholders.
Several duels run at once: one per arena, or several per arena on
[AdvancedSlimePaper](#advancedslimepaper). Nothing a duel touches can leak out: players are saved before a
duel and put back afterwards, and arenas are put back after build duels, even after a crash.

## Install

1. Put `elotecraft-api-<version>.jar` and `elotecraft-duels-<version>.jar` in `plugins/`.
2. Optional: [Vault](https://www.spigotmc.org/resources/vault.34315/) with an economy plugin for money
   rewards, and [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) for placeholders.
3. Start the server once; `plugins/ElotecraftDuels/` gets `config.yml`, `messages.yml` and `menus.yml`.

Statistics go to `duels.db` (SQLite) by default. For MySQL or MariaDB, set `database.type: mysql` and the
connection keys in `config.yml`, then restart.

## Setting up

Arenas and kits are made in game with `/duels` (operators by default).

**An arena** is a box with two fighter spawns:

1. Stand in the arena and run `/duels arena create desert`.
2. Stand where each fighter starts: `/duels arena setspawn desert 1`, then `... desert 2`.
3. Stand at two opposite corners of a box around the whole arena, including the air above it:
   `/duels arena setcorner desert 1` and `... desert 2`. Players cannot leave this box during a duel.
4. Optional: `/duels arena setspectator desert`, `/duels arena seticon desert` (item in hand) and
   `/duels arena setname desert <gold>Desert`.
5. `/duels arena info desert` says `ready`, or what is missing.

The arena must allow PvP: check the world's `pvp` setting and WorldGuard flags. If you use a combat-tag
or graves plugin, exclude the arena regions; duels never fire death events, but combat tags still apply.

**A kit** is a full inventory, armor and off hand included:

1. Put the items in your inventory and run `/duels kit create archer`. The item in your hand becomes its icon.
2. Edit it later: empty your inventory, `/duels kit load archer`, change it, `/duels kit save archer`.
3. Optional: `/duels kit setname archer <green>Archer`, `/duels kit setpermission archer duels.kit.archer`.

Kits are stored in `kits.yml` with Paper's item format, so they survive server updates.

## Build kits

`/duels kit build <kit>` turns a kit into a build kit (run it again to undo): while fighting, its
fighters may place blocks inside the arena box, use buckets and flint and steel, and break blocks placed
during the duel. With `build.break-arena-blocks: true` they may break the arena itself too. Broken blocks
drop nothing. They can also use doors, levers and the like in the arena; those are put back too. Beds and
respawn anchors never set anyone's respawn point during a duel.

Every block a build duel changes is recorded the first time it changes, whether a player, water, lava,
fire, falling sand or an explosion changed it, and put back after the duel exactly as it was (chests and
signs included), `regen.blocks-per-tick` blocks per tick. The arena takes no new duel until it is back.
Water, lava, fire, falling sand and explosions cannot reach out of the box, and explosions there drop
nothing. Pistons, dispensers, growing trees and sponges do not work in it.

**After a crash.** Save each arena once while it is intact with `/duels arena snapshot <arena>` (again
after rebuilding it). An arena that a crash left mid build duel is rebuilt from that snapshot on the next
start, and takes no duels until then; `/duels arena reset <arena>` does the same by hand. Without a
snapshot (or with one taken before the corners moved) the console says so: fix the arena by hand, then
`/duels arena snapshot` it, which also lets it take duels again. Snapshots keep blocks only: chests come
back empty and signs blank, so keep containers out of build arenas or refill them. Snapshots are deleted
with their arena and limited to 256 x 256 blocks across. Arenas on AdvancedSlimePaper need none of this.

## AdvancedSlimePaper

[AdvancedSlimePaper](https://github.com/InfernalSuite/AdvancedSlimePaper) (ASP) is a Paper fork that keeps
small worlds in memory and copies them in milliseconds. On an ASP server, arenas built in ASP worlds give
every duel its own copy of the world, thrown away afterwards: nothing to put back, nothing a crash can
leave behind, and up to `slime.copies-per-arena` duels at once in one arena. Arenas in normal worlds keep
working as above, and on plain Paper the plugin works without it.

1. Run the server on an ASP 1.21.11 build ([downloads](https://infernalsuite.com/download/asp)). ASP
   replaces the server jar, so check your other plugins on it first. Its 1.21.11 builds no longer get
   updates (ASP moved on to newer Minecraft versions), so you also stop getting Paper's 1.21.11 fixes.
2. `/duels arena world create desert` makes an empty world with one block to stand on and takes you
   there; `/duels arena world import <folder> desert` copies an existing world folder (not loaded, in the
   server directory) instead.
3. Build the arena, then set it up with the usual `/duels arena` commands while standing in `desert`.
4. `/duels arena world save desert`. New duels use the world as saved; save again after changes.

ASP worlds live in `plugins/ElotecraftDuels/slime-worlds/` and are loaded at start so admins can edit them.

Without ASP, `/duels arena world` explains that it is needed. `slime.enabled: false` turns the copies off.

## Commands

| Command | Permission | |
|---|---|---|
| `/duel <player> [kit] [arena]` | `duels.duel` | Challenge; without a kit the kit menu opens, then the arena menu |
| `/duel accept [player]`, `/duel deny [player]` | `duels.duel` | Answer a challenge (clickable in chat too) |
| `/duel rematch` | `duels.duel` | Challenge your last opponent again, or accept their rematch |
| `/duel queue [kit]` | `duels.queue` | Join or leave a kit's matchmaking queue (menu without a kit) |
| `/duel spectate <player>` | `duels.spectate` | Watch someone's duel |
| `/duel leave` | | Leave the queue, stop spectating, or forfeit |
| `/duel stats [player]`, `/duel top` | `duels.stats`, `duels.top` | Statistics and leaderboard |
| `/duels arena ...` | `duels.admin.arena` | `create`, `delete`, `setspawn`, `setcorner`, `setspectator`, `seticon`, `setname`, `toggle`, `info`, `tp`, `list`, `snapshot`, `reset` |
| `/duels arena world ...` | `duels.admin.arena` | `create`, `import`, `save`: arena worlds on AdvancedSlimePaper |
| `/duels kit ...` | `duels.admin.kit` | `create`, `save`, `load`, `delete`, `seticon`, `setname`, `setpermission`, `build`, `list` |
| `/duels stop <player>` | `duels.admin.stop` | End a duel without a result |
| `/duels reload` | `duels.admin.reload` | Reload config, messages, menus, arenas and kits |

Choosing the arena needs `duels.select-arena`; without it arenas are random. `duels.player` (everyone by
default) grants all player permissions; `duels.admin` (operators) grants all admin ones plus
`duels.bypass.cooldown`. `/duels` itself needs `duels.admin`.

## How a duel runs

1. Both players' state (position, inventory, health, hunger, xp, effects, game mode, flight) is saved to
   the database. If that fails, nothing changes.
2. They are teleported in, given the kit and frozen during the countdown.
3. They fight until one would die: the lethal hit is cancelled instead, so there is no death screen and
   nothing drops. A totem in hand still works. Quitting or `/duel leave` loses; running out of time
   (`match.max-duration`) is a draw.
4. The result shows for `match.end-delay-seconds`, then everyone is put back exactly as they were.

While in a duel or spectating, players cannot drop, pick up or store items, open containers or other
menus, change blocks (except with a [build kit](#build-kits)), use commands other than `/duel` and
`rules.allowed-commands`, or teleport out of the arena (pearls inside it work). Among players only the
two fighters can hurt each other; mobs, fall damage and the like still apply, so keep arenas mob-free and
protected (e.g. WorldGuard). Explosions never break arena blocks, and arrows, tridents, pearls, dropped
items and falling blocks left in an arena are removed when a duel ends.

A crash, kick or reload never leaves anyone stuck: on shutdown everyone is put back, and a duel that was
cut short by a crash is undone when the player next joins.

## Configuration

- `config.yml`: database, countdown, duration, end delay, boss bar, request expiry and cooldown, rematch
  window, hunger and regeneration rules, allowed commands, build kit and arena regen rules,
  AdvancedSlimePaper, rewards, title timings, sounds and particles.
  Invalid values are logged and replaced by defaults.
- `messages.yml`: every text players see, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html).
  Add `messages_<language>.yml` (e.g. `messages_es.yml`) for players whose client uses that language.
- `menus.yml`: titles, sizes, filler and button items of the kit and arena menus.

Rewards are paid when a duel ends with a lethal hit (not for forfeits, quits, draws or cancelled duels, so
accounts cannot farm them): money through Vault and console commands with `<winner>`, `<loser>`, `<kit>`
and `<arena>`. Commands are skipped for players whose name is not letters, digits and underscores
(offline-mode servers allow names such as `@a`). Wins and losses by forfeit or quit still count in the stats.

## Placeholders

With PlaceholderAPI: `%duels_wins%`, `%duels_losses%`, `%duels_win_streak%`, `%duels_best_win_streak%`,
`%duels_win_rate%`, `%duels_in_match%`, `%duels_opponent%`, `%duels_kit%`, `%duels_arena%`,
`%duels_queue%`, `%duels_active_matches%`. Stats placeholders are for online players.

## Testing on a server

The automated tests run on MockBukkit, which cannot click menus. Before a release, check on a real server:

- [ ] Challenge through the kit and arena menus, accept by clicking in chat
- [ ] Countdown freeze, fight, lethal hit, result, everyone back with their own items
- [ ] Quit mid-fight, `/duel leave`, a timeout draw, `/duels stop`
- [ ] Queue pairing and spectating; spectators cannot fly out of the arena
- [ ] `/stop` during a duel, then join again: items and position restored
- [ ] Ender pearl inside the arena works, out of it is blocked
- [ ] With MySQL: a duel's result appears in `/duel top`
- [ ] Build kit: place, break your own blocks, bucket water and lava, flint and steel, TNT; the arena is
  back after the duel and nothing flowed or burned outside the box
- [ ] `/duels arena snapshot`, break the arena by hand, `/duels arena reset`; `/stop` mid build duel, start
  again: the console says the arena was rebuilt
- [ ] On AdvancedSlimePaper: `/duels arena world create`, set up and save an arena, two duels in it at
  once, and their `duels_<arena>_<n>` copies unload afterwards

## Not included

Bets, ranked/ELO queues, team duels, own-inventory duels, per-kit rules, kits limited to certain arenas,
match history, a sidebar and leaderboard holograms. Arenas cannot span worlds or be loaded from
schematic files.
