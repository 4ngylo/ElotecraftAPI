# ElotecraftDuels

1v1 duels for Paper 1.21.11, built on ElotecraftAPI: challenges with kit and arena menus, matchmaking
queues, spectators, rematches, statistics, build kits, Vault rewards and PlaceholderAPI placeholders.
Several duels run at once, one per arena, and `/duels arena pregen` copies an arena as often as needed.
Nothing a duel touches can leak out: players are saved before a duel and put back afterwards, and arenas
are put back after build duels, even after a crash.

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
4. Optional: `/duels arena setspectator desert`, `/duels arena setcenter desert` (where spectators
   appear when no spectator spawn is set), `/duels arena seticon desert` (item in hand) and
   `/duels arena setname desert <gold>Desert`.
5. `/duels arena info desert` says `ready`, or what is missing.

**Or use the menus:** `/duels arena` lists the arenas built by hand (pregen copies are counted on their
source) with buttons for a new arena and a schematic import, and `/duels arena desert` opens one arena's
settings: status, enabled, spawns, corners, WorldEdit box, spectator, center, FFA spawns, icon, name,
categories, build limit, teleport, snapshot, pregen copies, reset and delete (reset, delete and clearing
copies or FFA spawns need shift + right-click). Point buttons use the place you stand when you click; names and numbers are typed in chat. `/duels arena help` lists the commands. After updating, delete
`menus.yml` (or copy in its new `arena-admin`, `arena-settings`, `kit-admin` and `kit-settings` sections)
so the menus exist.

A fighter who falls out of the bottom of the box loses, as in the void (`rules.void-eliminates`); leaving
it any other way sends them back to their spawn.

**Arena pools.** `/duels arena category desert add bridge` puts an arena in a category, and
`/duels kit arenas <kit> bridge` makes that kit's duels use only arenas in it (several categories are
allowed; `any` undoes it). Queues, challenges, rematches and the arena menu all follow it, and a kit with
no ready arena in its categories cannot be queued for or challenged with.

**Party fights.** A party leader starts `/party split` (two teams: a menu opens with a shuffled split; click a
member's head to move them to the other team, then Start), `/party ffa` (everyone for
themselves) or `/party duel <leader>` (party against party once the other leader accepts); without a kit
the kit menu opens. Every member must be online and free, and they leave their queues. Fighters who are
knocked out watch until one side is left. Party fights are casual: no stats, rating, rewards or rematch.
Teammates can't hurt each other unless `parties.friendly-fire` is on. A party FFA puts each player on one of
the arena's extra spawns (`/duels arena addspawn`, one per player) when there are enough; otherwise they are
spread from spawn 1 to spawn 2, or put on spawn 1 and 2 in turn where there is no floor.

**Build limit.** `/duels arena buildlimit desert 80` stops fighters placing blocks above Y 80 (`none`
removes it), so bridges and towers stay low.

**Copies.** One arena hosts one duel at a time; to host more, build it once and copy it:

1. `/duels arena snapshot desert` saves its blocks.
2. `/duels arena pregen desert 8` pastes `desert-1` to `desert-8` on a grid in the arenas world
   (`arenas.world`, an empty void world made at start), `arenas.pregen-spacing` blocks apart, a few blocks
   per tick. Each copy takes duels as soon as it is pasted and has the arena's spawns, center, categories
   and build limit.
3. Copies cannot be edited. To change them, change `desert`, snapshot it again, then
   `/duels arena pregen desert clear` (removes the copies and empties their space) and pregen again.
   `desert` cannot be deleted while it has copies.

A restart during a pregen stops it; the copies pasted so far keep working.

**With [FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit) or WorldEdit**
(optional, recommended):

- Pregen copies the arena as it stands, chest contents, sign text, banners and heads included. FAWE
  pastes off the main thread; plain WorldEdit pastes each copy at once on the main thread, which can lag
  for big arenas. Without either, copies are pasted from the snapshot, which keeps blocks only.
- `/duels arena setbox desert` sets both corners from your WorldEdit selection (`//wand`).
- `/duels arena import desert desert.schem` pastes `plugins/ElotecraftDuels/schematics/desert.schem` at a
  free place in the arenas world (lowest layer at Y 64) and makes it arena `desert` with its corners set;
  set its spawns, then snapshot it.

The arena must allow PvP: check the world's `pvp` setting and WorldGuard flags. If you use a combat-tag
or graves plugin, exclude the arena regions; duels never fire death events, but combat tags still apply.

**A kit** is a full inventory, armor and off hand included:

1. Put the items in your inventory and run `/duels kit create archer`. The item in your hand becomes its icon.
2. Edit it later: empty your inventory, `/duels kit load archer`, change it, `/duels kit save archer`.
3. Optional: `/duels kit setname archer <green>Archer`, `/duels kit setpermission archer duels.kit.archer`.

**Or use the menus:** `/duels kit` lists every kit (with buttons for a new kit and the default kits),
and `/duels kit <kit>` opens one kit's settings: building, damage, icon, name, permission, arena
categories, save or load its items, delete (shift + right-click) and every game rule (left-click
switches, right-click resets). Names and values are typed in chat. Each button runs the matching
command, so the same permission and checks apply. `/duels kit help` lists the commands.

Kits are stored in `kits.yml` with Paper's item format, so they survive server updates.

**Own layouts.** Players arrange a kit's items their way with `/duel editkit [kit]` (permission
`duels.kit.edit`): their inventory is saved, the kit is put in it, and `/duel editkit save` (or the
[SAVE] button) keeps the layout if it holds exactly the kit's items; `cancel` or a timeout
(`kit-editor.timeout`) changes nothing, and their own items always come back, even after a crash. Every
duel with the kit then uses their layout. When an admin changes a kit's items, old layouts are dropped;
`/duel editkit reset <kit>` drops one by hand.

**Default kits.** A first start (no `kits.yml` yet) adds 15 kits after the most played practice modes:
NoDebuff, Debuff, Gapple, BuildUHC, Classic, Archer, Sumo, Vanilla (crystals and anchors), UHC, Pot, NethOP,
SMP, Sword, Axe and Mace. BuildUHC, Vanilla and UHC are build kits. Sumo hits only knock back
(`/duels kit damage <kit>` toggles that for any kit): falling off the arena loses, so give it a small
platform arena in category `sumo` (`/duels arena category <arena> add sumo`). Edit them like any kit;
`/duels kit defaults` adds the ones that are missing on an existing server and never overwrites a kit.
UHC and BuildUHC drain hunger without natural regeneration; NoDebuff, Debuff and Pot have a 15 second
pearl cooldown (see [game rules](#kit-game-rules)). Kits added before game rules existed keep the
defaults: set them by hand, e.g. `/duels kit rule uhc natural-regeneration false`.

## Kit game rules

`/duels kit rule <kit>` lists a kit's game rules; `/duels kit rule <kit> <rule> <value>` sets one, and
`default` as the value unsets it. They are saved under `rules` in `kits.yml`.

| Rule | Default | |
|---|---|---|
| `hunger` | `rules.hunger` | true: food drains during the fight |
| `natural-regeneration` | `rules.natural-regeneration` | false: a full hunger bar no longer heals (UHC); potions and golden apples still do |
| `friendly-fire` | `parties.friendly-fire` | Teammates hurt each other in party fights |
| `void-eliminates` | `rules.void-eliminates` | Falling out of the bottom of the arena loses |
| `fall-damage` | true | Falling hurts, and so does landing an ender pearl |
| `fire-damage` | true | Fire, lava, magma blocks and campfires hurt |
| `explosion-damage` | true | TNT, crystals and anchors hurt |
| `self-damage` | true | Your own arrows and TNT hurt you (bow boosting) |
| `item-durability` | true | false: armor, weapons and tools never wear out |
| `hit-delay` | true | false: combo mode, hits land almost every tick instead of twice a second |
| `arrow-pickup` | true | Shot arrows can be picked back up (tridents always can) |
| `crafting` | true | The 2x2 crafting grid works |
| `pearl-cooldown` | vanilla (1s) | Seconds between ender pearls, 0 to 60 (0: none) |

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
with their arena and limited to 256 x 256 blocks across.

## Commands

| Command | Permission | |
|---|---|---|
| `/duel <player> [kit] [arena]` | `duels.duel` | Challenge; without a kit the kit menu opens, then the arena menu |
| `/duel accept [player]`, `/duel deny [player]` | `duels.duel` | Answer a challenge (clickable in chat too) |
| `/duel rematch` | `duels.duel` | Challenge your last opponent again, or accept their rematch |
| `/duel queue [kit]` | `duels.queue` | Join or leave a kit's unranked queue (menu without a kit) |
| `/duel ranked [kit]` | `duels.queue.ranked` | Join or leave a kit's ranked queue (menu without a kit) |
| `/duel spectate <player>` | `duels.spectate` | Watch someone's duel |
| `/duel leave` | | Leave the queue, stop spectating, or forfeit |
| `/duel stats [player]`, `/duel top [elo]` | `duels.stats`, `duels.top` | Statistics and leaderboard by wins or rating |
| `/party <player>`, `/party accept\|deny [player]` | `duels.party` | Invite someone (makes a party if you have none) or answer an invite |
| `/party kick\|promote <player>`, `/party disband` | `duels.party` | Manage the party (leader only) |
| `/party leave`, `/party info` | `duels.party` | Leave the party, list its members |
| `/party split [kit] [arena]`, `/party ffa [kit] [arena]` | `duels.party.fight` | Leader: two teams picked in a menu, or everyone for themselves |
| `/party duel <leader> [kit] [arena]`, `/party duelaccept\|dueldeny [leader]` | `duels.party.fight` | Leader: challenge another party, or answer a challenge |
| `/duels arena ...` | `duels.admin.arena` | menus: no argument or an arena name; `help`, `create`, `delete`, `setspawn`, `setcorner`, `setbox`, `import`, `setspectator`, `setcenter`, `seticon`, `setname`, `category`, `buildlimit`, `toggle`, `info`, `tp`, `list`, `snapshot`, `reset`, `pregen` |
| `/duels kit ...` | `duels.admin.kit` | menus: no argument or a kit name; `help`, `create`, `save`, `load`, `delete`, `seticon`, `setname`, `setpermission`, `build`, `damage`, `rule`, `arenas`, `defaults`, `list` |
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
two fighters can hurt each other; mobs, fall damage and the like still apply (unless a
[game rule](#kit-game-rules) turns them off), so keep arenas mob-free and
protected (e.g. WorldGuard). Explosions never break arena blocks, and arrows, tridents, pearls, dropped
items and falling blocks left in an arena are removed when a duel ends.

A crash, kick or reload never leaves anyone stuck: on shutdown everyone is put back, and a duel that was
cut short by a crash is undone when the player next joins.

## Configuration

- `config.yml`: database, countdown, duration, end delay, boss bar, request expiry and cooldown, rematch
  window, hunger, regeneration and void rules, allowed commands, build kit and arena regen rules, the
  arenas world and pregen spacing, ranked rating and queue range, party size and invite expiry, rewards, title timings, sounds and
  particles.
  Invalid values are logged and replaced by defaults.
- `messages.yml`: every text players see, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html).
  Add `messages_<language>.yml` (e.g. `messages_es.yml`) for players whose client uses that language.
- `menus.yml`: titles, sizes, filler and button items of the kit, arena, team and admin menus.

Rewards are paid when a duel ends with a lethal hit (not for forfeits, quits, draws or cancelled duels, so
accounts cannot farm them): money through Vault and console commands with `<winner>`, `<loser>`, `<kit>`
and `<arena>`. Commands are skipped for players whose name is not letters, digits and underscores
(offline-mode servers allow names such as `@a`). Wins and losses by forfeit or quit still count in the stats.

Every kit has an unranked queue (`/duel queue`, first come first served) and a ranked one (`/duel ranked`).
Ranked duels move ratings: everyone starts at an Elo rating of 1000 and the winner takes rating from the
loser, more for beating a higher-rated player (`ranked.k-factor` caps it). Forfeits and quits count as
losses; draws, unranked queue duels, challenges and rematches leave ratings alone. The ranked queue pairs the longest-waiting player
with the first opponent rated within `ranked.range`; the range grows by `ranked.range-growth` every
second they wait, up to `ranked.range-max`. Updating from an older version adds the rating to the
existing stats table on startup.

## Placeholders

With PlaceholderAPI: `%duels_wins%`, `%duels_losses%`, `%duels_win_streak%`, `%duels_best_win_streak%`,
`%duels_win_rate%`, `%duels_elo%`, `%duels_in_match%`, `%duels_opponent%`, `%duels_kit%`, `%duels_arena%`,
`%duels_queue%`, `%duels_queue_type%` (`ranked` or `unranked`), `%duels_party_size%`, `%duels_party_leader%`, `%duels_active_matches%`. Stats placeholders are for online players.

## Testing on a server

The automated tests run on MockBukkit, which cannot click menus. Before a release, check on a real server:

- [ ] Challenge through the kit and arena menus, accept by clicking in chat
- [ ] Countdown freeze, fight, lethal hit, result, everyone back with their own items
- [ ] Quit mid-fight, `/duel leave`, a timeout draw, `/duels stop`
- [ ] Queue pairing and spectating; spectators cannot fly out of the arena
- [ ] Ranked: rating change shown after a `/duel ranked` duel and not after a `/duel queue` one, `/duel top elo`, an older database gains the rating
- [ ] `/stop` during a duel, then join again: items and position restored
- [ ] Ender pearl inside the arena works, out of it is blocked
- [ ] With MySQL: a duel's result appears in `/duel top`
- [ ] Build kit: place, break your own blocks, bucket water and lava, flint and steel, TNT; the arena is
  back after the duel and nothing flowed or burned outside the box
- [ ] `/duels arena snapshot`, break the arena by hand, `/duels arena reset`; `/stop` mid build duel, start
  again: the console says the arena was rebuilt
- [ ] The arenas world is created empty; `/duels arena pregen` of a real arena, several duels at once on
  its copies, `pregen clear` empties their space; `/stop` mid build duel on a copy rebuilds the copy
- [ ] With FAWE: pregen keeps a chest's contents and a sign's text in every copy with no lag spike;
  `/duels arena setbox` from a `//wand` selection; `/duels arena import` of a `.schem`. Plain WorldEdit too
- [ ] A kit limited to a category only gets those arenas; the build limit stops towering; falling off the
  bottom of the arena loses the duel
- [ ] Parties: invite by clicking [ACCEPT] in chat, `/party split` (move heads in the team menu, Start),
  `/party ffa` with 3+ players on extra spawns and without them, `/party duel` between two parties;
  knocked-out fighters watch until one side is left, teammates can't hurt each other, everyone gets their
  own items back; the leader quitting mid-fight hands the party over
- [ ] `/duel editkit`: rearrange, [SAVE], the next duel uses the layout; [CANCEL] and `/stop` while
  editing give your own items back
- [ ] Kit rules on a real client: `hit-delay false` combos, `pearl-cooldown 15` shows the cooldown on the
  pearl, `natural-regeneration false` stops healing on a full hunger bar, `crafting false` blocks the 2x2 grid
- [ ] `/duels arena` and `/duels kit` menus: every button, chat prompts for names and numbers,
  shift + right-click on delete and reset

## Not included

Bets, per-kit ratings and rating seasons, team duels, own-inventory duels, per-kit rules, match history,
a sidebar and leaderboard holograms. Arenas cannot span worlds, and without WorldEdit copies don't keep
chest contents or sign text.
