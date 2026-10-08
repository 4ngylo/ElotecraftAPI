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
   After an update, settings, messages and menu buttons new in that version are added to these files with
   their comments; your values stay, and the file as it was is kept once as `<file>.bak`.

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
copies or FFA spawns need shift + right-click). Point buttons use the place you stand when you click; names and numbers are typed in chat. `/duels arena help` lists the commands.

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

**The party menu.** `/party` opens it: in a party, a head per member (the leader makes someone leader with a
left-click and kicks with shift + right-click) and buttons to invite (a name typed in chat), go public, start a
split or FFA, challenge another party, leave and disband (shift + right-click). Without a party it lists the
public parties, a click joining one, and has a button to make a party. A public party (`/party public`) takes
anyone with `/party join <leader>`; going public is announced to players not in a party who take party
invites, with a click to join.

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
The settings menu has 5 rows, so every rule fits on one page; a menus.yml from an older version keeps
`kit-settings.rows: 4` (two pages) until you change it.

Kits are stored in `kits.yml` with Paper's item format, so they survive server updates.

**Own layouts.** Players arrange a kit's items their way with `/duel editkit [kit]` (permission
`duels.kit.edit`): their inventory is saved, the kit is put in it, and `/duel editkit save` (or the
[SAVE] button) keeps the layout if it holds exactly the kit's items; `cancel` or a timeout
(`kit-editor.timeout`) changes nothing, and their own items always come back, even after a crash. Every
duel with the kit then uses their layout. When an admin changes a kit's items, old layouts are dropped;
`/duel editkit reset <kit>` drops one by hand.

**Default kits.** A first start (no `kits.yml` yet) adds 18 kits after the most played practice modes:
NoDebuff, Debuff, Gapple, BuildUHC, Classic, Archer, Sumo, Boxing, Combo, Vanilla (crystals and anchors), UHC,
Pot, NethOP, SMP, Sword, Axe, Mace and Spear. BuildUHC, Vanilla and UHC are build kits. Sumo hits only knock back
(`/duels kit damage <kit>` toggles that for any kit): falling off the arena loses a round of a best of 3, so give
it a small platform arena in category `sumo` (`/duels arena category <arena> add sumo`). Boxing hits only knock
back too: the first to land 100 hits wins. Combo has no hit delay. Edit them like any kit;
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
| `potions` | true | Potions can be drunk and thrown (splash and lingering) |
| `item-drops` | false | Fighters drop items (Q) |
| `block-drops` | false | Blocks broken in a build duel drop their item |
| `death-drops` | false | A knocked-out fighter drops their inventory where they fell (useful in party fights and events) |
| `pearl-cooldown` | vanilla (1s) | Seconds between ender pearls, 0 to 60 (0: none) |
| `hits-to-win` | off | Boxing: a fighter hit this many times by opponents is out, so the first to land them wins a duel; 1 to 1000 (0: off). The attacker sees the count in the action bar. Pair it with `/duels kit damage <kit>` off |
| `rounds-to-win` | 1 | Duels only: the first fighter to win this many rounds wins the duel; 2 to 10 (0 or 1: one round). Between rounds both are healed and re-kitted and go back to their spawns, and a build arena is put back. `match.max-duration` applies to each round; a round running out of time ends the duel in a draw, and quitting or `/duel leave` loses the whole duel. Stats, rating, rewards and history count the duel once |

| `max-health` | off | Fighters' maximum health in health points, 1 to 200 (20 is ten hearts; 0: off). They start the fight full; it is undone when they are sent back, and after a crash |
| `damage-multiplier` | off | Percent of the damage opponents deal, 1 to 500 (50 halves it, 200 doubles it; 0: off) |
| `saturation` | false | Food and saturation stay full (an endless saturation effect), so health comes back fast |
| `auto-ignite-tnt` | false | Build kits: placed TNT is lit at once, and counts as lit by its placer (self-damage rule) |

With any of the three drop rules on, fighters pick up items inside the arena.

**Effects.** `/duels kit effect <kit> <effect> <level>` gives fighters a potion effect for the whole fight,
without particles (`speed 2`, `jump_boost 1`, any Minecraft effect name); level 0 removes it, and
`/duels kit effect <kit>` lists them. They are stored under `effects` in `kits.yml`, e.g.
`effects: ["speed 2"]`, and taken away with everything else when the fight ends.

## Kit rewards

A kit can pay its own rewards on top of config.yml's `rewards`, with the same rules: money needs Vault,
commands run from the console with `<winner>`, `<loser>`, `<kit>` and `<arena>`, and only a duel won by a
lethal hit pays (not forfeits, quits, draws, party fights or events). Money is added to the global
reward, so a player gets one deposit. Set them in `kits.yml` and run `/duels reload`; there is no
in-game command, as these commands run with console rights.

```yaml
kits:
  nodebuff:
    rewards:
      win:
        money: 50
        commands:
          - "give <winner> diamond 1"
      loss:
        commands:
          - "say <loser> lost a <kit> duel"
```

## Bets

`/duel <player> <kit> [arena] bet <amount>` challenges someone for money (Vault and an economy plugin).
The challenge shows each player's stake and what the winner takes; nothing is taken until it is accepted.
On accepting, both players must still have the money: it is taken from both and stored in the database
(`duels_bets`); if either cannot pay, the challenge stays open. The winner of the duel gets both stakes
less `bets.tax` percent. A forfeit or quit loses the stake, like any duel; a draw, a duel cancelled
before the fight, or `/duels stop` gives both stakes back. Stakes of duels a stop or crash cut short are
given back when the server starts again (logged), once the economy plugin is there. `bets.min` and
`bets.max` limit each stake, and `bets.enabled: false` turns bets off. Bets are for challenges only, not
queues, party fights or events.

## Build kits

`/duels kit build <kit>` turns a kit into a build kit (run it again to undo): while fighting, its
fighters may place blocks inside the arena box, use buckets and flint and steel, and break blocks placed
during the duel. With `build.break-arena-blocks: true` they may break the arena itself too. Broken blocks
drop nothing unless the kit has the `block-drops` rule. They can also use doors, levers and the like in
the arena; those are put back too. Beds and respawn anchors never set anyone's respawn point during a duel.

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
| `/duel <player> <kit> [arena] bet <amount>` | `duels.bet` | Challenge for money: each player stakes `<amount>`, the winner takes both ([bets](#bets)) |
| `/duel accept [player]`, `/duel deny [player]` | `duels.duel` | Answer a challenge (clickable in chat too) |
| `/duel rematch` | `duels.duel` | Challenge your last opponent again, or accept their rematch |
| `/duel queue [kit]` | `duels.queue` | Join or leave a kit's unranked queue (menu without a kit) |
| `/duel ranked [kit]` | `duels.queue.ranked` | Join or leave a kit's ranked queue (menu without a kit) |
| `/duel spectate [player]` | `duels.spectate` | Watch someone's duel; without a player, a menu of the fights you may watch (layout in menus.yml `spectate`). While watching (or knocked out), it takes you to a fighter of your fight; without a player, a menu of their heads (menus.yml `spectate-fighters`), also opened by clicking the compass in the middle of your inventory (E) while watching (`spectate-fighters.item`) |
| `/duel leave` | | Leave the queue, stop spectating, or forfeit |
| `/duel cancel [player]` | `duels.duel` | Take back a challenge you sent (also the [CANCEL] after sending) |
| `/duel toggle <option>` | | Turn an option off or on: `requests`, `party-invites`, `sidebar` or `sounds`; the same switches as `/duel options`. Alone, it lists the options |
| `/duel cosmetics [kill-effect\|kill-message]` | `duels.cosmetics` | Pick a kill effect and a kill message (see [Cosmetics](#cosmetics)) |
| `/duel options` | | A menu where players turn duel requests, party invites, the sidebar, duel sounds, spectators of their fights and other players in the lobby off or on, and set their ping range; kept in their player data across restarts (not across servers). Layout in menus.yml `options`; remove an option's section to stop offering it. See [Player options](#player-options) |
| `/duel stats [player]`, `/duel top [elo [kit]]` | `duels.stats`, `duels.top` | Statistics, and leaderboard by wins, overall rating or a kit's rating |
| `/duel top season <number>` | `duels.top` | An ended season's final overall ratings |
| `/duel history [player]` | `duels.history` | A player's latest 50 duels, online or not: opponent, kit, arena, how it ended, health left and rating change |
| `/duel inventory` | | Opened by clicking a fighter's name in a duel's result line, or in the "Inventories" line sent after party fights and events, once the fight is over: that fighter's items, health, food and effects as the fight left them, hits landed, longest combo and health potions thrown, missed and accuracy. Kept for 10 minutes |
| `/party` | `duels.party` | The party menu: your party's members and buttons, or the public parties to join (layouts in menus.yml `party` and `party-none`) |
| `/party <player>`, `/party accept\|deny [player]` | `duels.party` | Invite someone (makes a party if you have none) or answer an invite |
| `/party public`, `/party join <leader>` | `duels.party` | Leader: let anyone join without an invite (announced once a minute at most), or stop; join a public party |
| `/party chat [message]`, `/pc [message]` | `duels.party` | Talk to your party; the message shows as typed. Without a message, switches party chat mode: what you type in chat goes to your party until you run it again or leave the party |
| `/party kick\|promote <player>`, `/party disband` | `duels.party` | Manage the party (leader only) |
| `/party leave`, `/party info` | `duels.party` | Leave the party, list its members |
| `/party split [kit] [arena]`, `/party ffa [kit] [arena]` | `duels.party.fight` | Leader: two teams picked in a menu, or everyone for themselves |
| `/party duel <leader> [kit] [arena]`, `/party duelaccept\|dueldeny [leader]` | `duels.party.fight` | Leader: challenge another party, or answer a challenge |
| `/event` | `duels.event` | Events to join or watch; your event's settings while you host one |
| `/event join <host>`, `/event leave` | `duels.event` | Join or leave an event that has not started (`/duel leave` works too) |
| `/event host [kit]` | `duels.event.host` | Host an event (kit menu without a kit), then set it up in its menu |
| `/event settings\|start\|cancel`, `/event invite <player>` | `duels.event.host` | Run your event; invite players to a private one (`duels.event.host.private` makes it private) |
| `/duels arena ...` | `duels.admin.arena` | menus: no argument or an arena name; `help`, `create`, `delete`, `setspawn`, `setcorner`, `setbox`, `import`, `setspectator`, `setcenter`, `seticon`, `setname`, `category`, `buildlimit`, `toggle`, `info`, `tp`, `list`, `snapshot`, `reset`, `pregen` |
| `/duels kit ...` | `duels.admin.kit` | menus: no argument or a kit name; `help`, `create`, `save`, `load`, `delete`, `seticon`, `setname`, `setpermission`, `build`, `damage`, `rule`, `arenas`, `defaults`, `list` |
| `/duels hologram create <name> <wins\|elo> [kit]`, `delete <name>`, `list` | `duels.admin.hologram` | [Leaderboard holograms](#leaderboard-holograms) where you stand |
| `/duels season`, `/duels season end [confirm]` | `duels.admin.season` | The [season](#seasons) running; end it (asks for `confirm` within 30 seconds) |
| `/duels stop <player>` | `duels.admin.stop` | End a duel without a result |
| `/duels reload` | `duels.admin.reload` | Reload config, messages, menus, arenas and kits |

Choosing the arena needs `duels.select-arena`; without it arenas are random. `duels.player` (everyone by
default) grants all player permissions, hosting events included; `duels.admin` (operators) grants all
admin ones plus `duels.bypass.cooldown`, which also skips the event host cooldown, and can watch events
that forbid spectators. `/duels` itself needs `duels.admin`.

## How a duel runs

1. Both players' state (position, inventory, health, hunger, xp, effects, game mode, flight) is saved to
   the database. If that fails, nothing changes.
2. They are teleported in, given the kit and frozen during the countdown.
3. They fight until one would die: the lethal hit is cancelled instead, so there is no death screen and
   nothing drops (unless the kit has `death-drops`). A totem in hand still works. Quitting or `/duel leave` loses; running out of time
   (`match.max-duration`) is a draw. With the kit rule `rounds-to-win`, a lethal hit only wins the round:
   after `match.round-delay-seconds` the arena is put back and steps 2 and 3 repeat until someone has
   won enough rounds.
4. The result shows for `match.end-delay-seconds`, then everyone is put back exactly as they were.

While in a duel or spectating, players cannot drop, pick up (except fighters with a drop rule)
or store items, open containers or other menus, change blocks (except with a [build kit](#build-kits)), use commands other than `/duel` and
`rules.allowed-commands`, or teleport out of the arena (pearls inside it work). Among players only the
two fighters can hurt each other; mobs, fall damage and the like still apply (unless a
[game rule](#kit-game-rules) turns them off), so keep arenas mob-free and
protected (e.g. WorldGuard). Explosions never break arena blocks, and arrows, tridents, pearls, dropped
items and falling blocks left in an arena are removed when a duel ends. Items dropped in a duel can never
be picked up outside it, by players, mobs or hoppers.

A crash, kick or reload never leaves anyone stuck: on shutdown everyone is put back, and a duel that was
cut short by a crash is undone when the player next joins.

## Configuration

- `config.yml`: database, countdown, duration, end delay, boss bar, request expiry and cooldown, rematch
  window, archers seeing the health their arrow left (`match.arrow-health`), hunger, regeneration and void rules, allowed commands, build kit and arena regen rules, the
  arenas world and pregen spacing, ranked rating and queue range, party size and invite expiry, events,
  the sidebar, rewards, title timings, sounds and particles.
  Invalid values are logged and replaced by defaults.
- `messages.yml`: every text players see, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html).
  Add `messages_<language>.yml` (e.g. `messages_es.yml`) for players whose client uses that language.
- `menus.yml`: titles, sizes, filler and button items of the kit, arena, team, event and admin menus.
  Right-clicking a kit in the kits menu shows its items (`kit-preview`). Lore lines are only added to
  new files, so on an existing server add the "Right-click to preview" line to the `kits` lore yourself.
  `/duel ranked` and `/duel queue` have their own menus, `ranked-queue` and `unranked-queue`. They say which
  queue a click joins, show the player's rating and division per kit in the ranked one, and have a button
  to switch between them. `kits` is for challenges and the kit editor only, so `kits.queue-lore` and
  `kits.queued-lore` in an older file are no longer used; copy any custom lore into the new sections.

Rewards are paid when a duel ends with a lethal hit (not for forfeits, quits, draws or cancelled duels, so
accounts cannot farm them): money through Vault and console commands with `<winner>`, `<loser>`, `<kit>`
and `<arena>`. Commands are skipped for players whose name is not letters, digits and underscores
(offline-mode servers allow names such as `@a`). Wins and losses by forfeit or quit still count in the stats.

Every kit has an unranked queue (`/duel queue`, first come first served) and a ranked one (`/duel ranked`).
Ranked duels move ratings, one per kit: everyone starts at an Elo rating of 1000 in every kit and the
winner takes rating in the duel's kit from the loser, more for beating a higher-rated player
(`ranked.k-factor` caps it). Forfeits and quits count as losses; draws, unranked queue duels, challenges
and rematches leave ratings alone. The ranked queue pairs the longest-waiting player with the first
opponent rated within `ranked.range` in the queued kit; the range grows by `ranked.range-growth` every
second they wait, up to `ranked.range-max`.

A player's overall rating (`/duel top elo`, `%duels_elo%`, the lobby sidebar) is the average of their
ratings in the kits they played ranked; deleted kits keep their ratings but count nowhere. Every rating
shows the highest of `ranked.divisions` it reaches (Bronze to Master by default, `[]` for none), and a
ranked result that moves a player into another division says so. `/duel top elo <kit>` ranks one kit, and
`/duel stats` lists each kit's rating, division, ranked wins and losses and peak: the highest rating this
season (`%duels_peak_<kit>%`, and `%duels_peak%` for the best over all kits).

`ranked.daily-limit` caps the ranked duels a player may start a day (the server's date, counted in their player
data on each server); unranked queues stay open, and `duels.queue.ranked.unlimited` (operators) has no limit.

### Seasons

`/duels season end`, then `/duels season end confirm` within 30 seconds, ends the season running (the first is
season 1; `%duels_season%`). Back up the database first: it cannot be undone. In one transaction every kit
rating (with its wins, losses and peak) is copied into `duels_seasons` under the season's number, then all
ratings are reset, so everyone starts the next season at 1000 in every kit. Then each player whose overall
rating ended in a division with a `season-reward` (config.yml `ranked.divisions`) is paid it, online or not:
money through Vault and console commands with `<player>`, `<division>`, `<elo>` and `<season>`. Everyone online
is told. `/duel top season <number>` shows a past season's final overall ratings. Ranked duels still running
when a season ends count in the new season.

Updating from a version with one rating for all kits: back up `duels.db` first. Nothing is converted;
each player's first ranked duel in a kit starts from their old rating, which stays stored unchanged.

## Lobby items

For practice servers: with `lobby-items.enabled` in config.yml, players in the lobby worlds
(`lobby-items.worlds`, empty for every world except the arenas world) get hotbar items that run a command
when right-clicked. The defaults are unranked queue, ranked queue, party, cosmetics, events, options, edit kits and match history;
while queued or waiting for an event, a "Leave the queue" item takes the queue items' place. The items are
set in menus.yml `lobby-items`: slot, look, command, `show` (`idle`, `waiting`, `always`, or `never` to drop
a default) and an optional permission.

They are kept in line with each player's state every second. They can't be dropped, moved or swapped, and
they vanish in matches, outside the lobby worlds, when turned off and on shutdown. Only items the plugin
tagged are ever touched: a player's own item in a lobby item's slot stays, and that lobby item waits for
the slot to be free. Don't combine them with another hub-items plugin.

## Sidebar

`sidebar.match` (on by default) shows fighters and spectators the fight: time left, opponent, their
health and ping, ratings in ranked duels, who is left in party fights, kit and arena. `sidebar.lobby` (off
by default) shows everyone else their rating and leaderboard position (top 100), wins, losses, win rate,
streaks, queue and party, in `sidebar.lobby-worlds` (empty: every world except the arenas world). Both
refresh every second from cached stats, never the database, and only send the lines that changed.
With `sidebar.health-below-name` (on by default), the fight sidebar also shows every player's health under
their name, in health points (20 is full) followed by `sidebar.health-below-name` in messages.yml; players
who turned the sidebar off don't see it. Layouts are in `messages.yml` under `sidebar`, one row per line, at most 15. Match layouts may use
`<round>` and `<score>`, or `<rounds>`, which is `sidebar.rounds` in a kit with `rounds-to-win` and empty
otherwise.

A sidebar gives the player their own scoreboard while it shows, so other plugins' sidebars and nametag
teams (TAB, nametag colours) disappear for them; leave `sidebar.lobby` off if you use such a plugin. A
sidebar another plugin shows through ElotecraftAPI is never replaced.

## Player options

`/duel options` (or `/duel toggle <option>` for the switches) holds each player's own settings:

- **Duel requests**, **party invites**, **sidebar** and **sounds**: on or off.
- **Spectators**: off, nobody but staff (`duels.admin`) can watch that player's duels and party fights
  (events follow their own spectator setting).
- **Lobby players**: off, the player sees only their party members while in the lobby worlds
  (`lobby-items.worlds`); in fights, and outside those worlds, everyone shows. Other plugins' vanish is left alone.
- **Ping range**: the highest ping of opponents the queues pair them with, any or 50 to 300 ms; two players
  are paired only if each one's ping fits the other's range. Each click moves to the next choice.

## Cosmetics

Players pick one kill effect and one kill message in `/duel cosmetics` (click again to drop it). When they
knock out an opponent they hit last, with a lethal hit or into the void, in any fight, the kill effect plays
where the opponent fell and everyone in the fight reads the kill message (in place of "is out"). Quits,
forfeits and falls nobody caused have no killer.

They are listed in config.yml `cosmetics`: `kill-effects` with a harmless lightning strike and a sound and
particle like `effects`, and `kill-messages`, whose texts are messages.yml `kill-messages.<id>` with `<killer>`
and `<victim>`. Each has a menu icon and name, and an optional `permission`, e.g.
`permission: duels.cosmetic.royal`, to sell or reward it; without the permission it shows as locked, and a
player who loses it keeps nothing picked. Lightning thunder is heard by players in nearby arenas too (the
client plays it), so remove `lightning` if your arenas are close together. Menu layouts are menus.yml
`kill-effect` and `kill-message`.

## Leaderboard holograms

`/duels hologram create <name> wins` places the top players by wins where you stand (`holograms.lines`, 10 by
default); `... elo` the top overall
ratings and `... elo <kit>` one kit's. They use the lines of `/duel top` from messages.yml (`top.header`,
`top.line`, `top.elo-header`, `top.elo-kit-header`, `top.elo-line`) and are read from the database every
minute. Creating one with a name in use moves and replaces it; `/duels hologram delete <name>` removes it and
`list` lists them. They are stored in `holograms.yml` and come back after restarts and when their chunk loads
again. No hologram plugin is needed.

## Events

Players host events with `/event host [kit]`. The event is announced to everyone with a clickable
[JOIN] (again every `events.announce-interval`) and listed in `/event`. While players gather, the host
sets it up in the Event Settings menu (`/event settings`):

- **Kit** and **arena** (random by default; choosing one needs `duels.select-arena`).
- **Rules**: the kit's game rules for this event only (potions, hunger, fall damage...); the kit
  itself is not changed. Changing the kit resets them.
- **Mode**: free for all, team vs team, tournament or sumo. Teams are picked in the team menu when the
  host starts it, or split at random when it starts on its own. A [tournament](#tournaments) is 1v1
  knockout rounds; sumo is the same with one fight at a time.
- **Winners**: in a free for all, how many of the last players standing win.
- **Border**: closes in on the fighters (`events.border`): it starts around the arena, waits `delay`
  into the fight, then shrinks to `min-size` blocks across over `shrink-time`; fighters outside lose
  `damage` health a second. Each fighter is shown their own border, so the world border is untouched.
- **Public**: off makes it private, for players invited with `/event invite` only (not announced or listed).
- **Spectators**: off stops anyone but staff from watching it.

It starts when the host clicks Start (`/event start`), at once when `events.max-players` have joined, or
when `events.wait-time` runs out; with fewer than `events.min-players` then, or no free arena, it is
cancelled. The host leaving or quitting cancels it too. Joined players can do anything in the lobby, but
cannot queue, duel or spectate until it starts or they `/event leave`.

The fight runs like a party fight: knocked-out players watch until it is decided, then everyone is put
back. The result goes to the whole server (`events.broadcast-result`). With a lethal hit deciding it,
each winner gets `events.reward`: money through Vault and console commands with `<winner>`, `<host>`,
`<kit>` and `<arena>`. Leave it empty (the default) for a broadcast only. Events never change stats or
ratings, and a host waits `events.host-cooldown` between events.

### Tournaments

A tournament or sumo event pairs its players at random for 1v1 fights; the winner of each goes through
to the next round, an odd player out goes through without a fight, and the last player left is the
champion. A tournament runs each round's fights at once, as arenas are free; sumo runs one at a time, and
the other players watch it (with Spectators on). Players between fights may `/duel spectate` its fights
but cannot queue or duel, and `/event leave` takes them out. Each fight is a duel to its fighters: its
result is not broadcast and pays nothing. A draw or a fight cancelled before it started is fought again
(`events.tournament-replays` times, 1 by default); after that, one of the two goes through at random. The champion is announced like an event's result and gets `events.reward` if they
won the final by a lethal hit. A round waits 3 seconds after the last.

### Scheduled events

`events.schedule` lists events the server hosts every day at a server time, e.g.
`- {at: "20:00", kit: sumo, mode: sumo}` (mode `ffa`, `teams`, `tournament` or `sumo`). They gather
players like a player's event, with messages.yml `event.server-host` as the host (`/event join Server`),
and start when the wait ends or they are full. One that is still gathering players skips the next; a kit
without items or an arena is logged and skipped.

## Placeholders

With PlaceholderAPI: `%duels_wins%`, `%duels_losses%`, `%duels_win_streak%`, `%duels_best_win_streak%`,
`%duels_win_rate%`, `%duels_elo%` (overall), `%duels_elo_<kit>%`, `%duels_division%`, `%duels_division_<kit>%`, `%duels_peak%`, `%duels_peak_<kit>%`, `%duels_season%`, `%duels_in_match%`, `%duels_opponent%`, `%duels_kit%`, `%duels_arena%`,
`%duels_queue%`, `%duels_queue_type%` (`ranked` or `unranked`), `%duels_party_size%`, `%duels_party_leader%`, `%duels_active_matches%`. Stats placeholders are for online players.

## Testing on a server

The automated tests run on MockBukkit, which cannot click menus. Before a release, check on a real server:

- [ ] Challenge through the kit and arena menus, accept by clicking in chat
- [ ] Countdown freeze, fight, lethal hit, result, everyone back with their own items
- [ ] Quit mid-fight, `/duel leave`, a timeout draw, `/duels stop`
- [ ] Queue pairing and spectating; spectators cannot fly out of the arena
- [ ] Ranked: rating change shown after a `/duel ranked` duel and not after a `/duel queue` one, `/duel top elo [kit]`, a first ranked duel in a kit starts from the old rating, a division change is announced
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
- [ ] Sidebar: the duel layout during a fight (time counts down, opponent health), the team layout in a
  party fight, the spectator one; with `sidebar.lobby: true` the stats come back after the fight and
  `/duels reload` with it off removes them
- [ ] Cosmetics: pick a kill effect and a kill message in `/duel cosmetics`, kill someone: the effect plays
  where they fell (lightning flashes, harms nothing), both fighters read the message; a quit shows neither; a
  locked entry with a `permission` refuses the pick
- [ ] Health under names in a duel with the sidebar on, updating on hits and healing; gone back in the lobby
- [ ] `/duels hologram create top wins` and `... elo <kit>`: the boards show the leaderboard, update within a
  minute of a duel, come back after `/stop` and after walking away until the chunk unloads and coming back
- [ ] Options: spectators off refuses a watcher, staff still watch; lobby players off hides everyone but the party
  in the lobby and shows everyone in a fight; a ping range keeps a laggy player out of your queue
- [ ] `/duel spectate` without a player: the menu lists the fight, a click watches it; `/duel spectate` again while watching
  shows the fighters' heads, a click teleports to one; the compass in the middle of the inventory (E) opens the same menu,
  and is gone after the fight
- [ ] Kit rules on a real client: `max-health 40` shows 20 hearts and is back to 10 after the duel, `damage-multiplier 50`
  halves hits, `saturation` heals fast, `auto-ignite-tnt` lights TNT on placing; `/duels kit effect <kit> speed 2` shows
  the effect icon and it is gone after the duel
- [ ] Parties: `/party` menu with and without a party, every button; `/party public` announces once, another player
  joins with the [JOIN] click; `/party chat` and `/pc` reach members only; `/pc` alone, then typing in chat, reaches
  members only until `/pc` again
- [ ] Ranked: `/duel stats` shows the peak; with `ranked.daily-limit: 1` a second ranked queue is refused and an unranked
  one is not; `/duels season end` warns, `confirm` archives and resets, a division's `season-reward` command runs and
  `/duel top season 1` shows the old ratings; the same with MySQL
- [ ] Events: `/event host`, [JOIN] in chat and the `/event` list, every Event Settings button, Start in
  team mode opens the team menu, a free for all with 2 winners, a private event refusing an uninvited
  player, spectators refused when off, the border closing in and hurting fighters outside it, potions
  blocked by an event rule, the reward command once per winner, the host quitting cancels
- [ ] Tournaments: 4 players in tournament mode with 2 arenas fight round 1 at once, the final after it and one
  reward; sumo with the others spectating; 3 players with a bye; a quit mid-fight; an `events.schedule` entry
  a minute ahead hosting a Server event
- [ ] Bets (with an economy plugin): a challenge with `bet 100` shows the pot, accepting takes both stakes, the
  winner gets the pot less `bets.tax`; `/duels stop` gives both back; a server stop mid-duel gives both back on the
  next start; a stake above `bets.max` or more than you have is refused

## Not included

Own-inventory duels and double-elimination brackets. Arenas cannot span worlds, and without WorldEdit copies don't keep
chest contents or sign text.
