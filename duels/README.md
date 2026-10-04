# ElotecraftDuels

1v1 duels for Paper 1.21.11, built on ElotecraftAPI: challenges with kit and arena menus, matchmaking
queues, spectators, rematches, statistics, Vault rewards and PlaceholderAPI placeholders. Several duels
run at once, one per arena. Nothing a duel touches can leak out: players are saved before a duel and
put back afterwards, even after a crash.

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
| `/duels arena ...` | `duels.admin.arena` | `create`, `delete`, `setspawn`, `setcorner`, `setspectator`, `seticon`, `setname`, `toggle`, `info`, `tp`, `list` |
| `/duels kit ...` | `duels.admin.kit` | `create`, `save`, `load`, `delete`, `seticon`, `setname`, `setpermission`, `list` |
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
menus, change blocks, use commands other than `/duel` and `rules.allowed-commands`, or teleport out of
the arena (pearls inside it work). Only the two fighters can hurt each other. Explosions never break
arena blocks.

A crash, kick or reload never leaves anyone stuck: on shutdown everyone is put back, and a duel that was
cut short by a crash is undone when the player next joins.

## Configuration

- `config.yml`: database, countdown, duration, end delay, boss bar, request expiry and cooldown, rematch
  window, hunger and regeneration rules, allowed commands, rewards, title timings, sounds and particles.
  Invalid values are logged and replaced by defaults.
- `messages.yml`: every text players see, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html).
  Add `messages_<language>.yml` (e.g. `messages_es.yml`) for players whose client uses that language.
- `menus.yml`: titles, sizes, filler and button items of the kit and arena menus.

Rewards run for wins and losses (not draws or cancelled duels): money through Vault and console commands
with `<winner>`, `<loser>`, `<kit>` and `<arena>`.

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

## Not included

Bets, ranked/ELO queues, team duels, build kits (need arena reset), own-inventory duels, per-kit rules,
kits limited to certain arenas, match history, a sidebar and leaderboard holograms.
