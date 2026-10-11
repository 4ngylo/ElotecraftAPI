# ElotecraftDuels

1v1 duels for Paper 1.21.11, built on ElotecraftAPI: challenges with kit and arena menus, matchmaking
queues, spectators, rematches, statistics, build kits, Vault rewards and PlaceholderAPI placeholders.
Several duels run at once: while an arena is busy, WorldEdit pastes copies of it for the next duels, and
copies nobody needs any more are cleared.
Nothing a duel touches can leak out: players are saved before a duel and put back afterwards, and arenas
are put back after build duels, even after a crash.

## Install

1. Put `elotecraft-api-<version>.jar` and `elotecraft-duels-<version>.jar` in `plugins/`, with
   [FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit) (recommended) or WorldEdit:
   ElotecraftDuels needs one of them to start.
2. Optional: [Vault](https://www.spigotmc.org/resources/vault.34315/) with an economy plugin for money
   rewards, and [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) for placeholders.
3. Start the server once; `plugins/ElotecraftDuels/` gets `config.yml`, `messages.yml` and `menus.yml`.
   After an update, settings, messages and menu buttons new in that version are added to these files with
   their comments; your values stay, and the file as it was is kept once as `<file>.bak`. A `menus.yml`
   from before the current layout (`version: 3`) is moved to `menus.v<its version>.yml` and the new one written.

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

**Or use the menus:** `/duels arena` lists the arenas (copies are counted on their arena) with buttons for a new arena and a schematic import, and `/duels arena desert` opens one arena's
settings: status, enabled, icon, name, categories, build limit, teleport and delete, with submenus for its
points (spawns, spectator, center, corners, WorldEdit box, FFA spawns), its bridge goals and beds, and its
snapshot, reset and copies. Delete, reset and clearing copies or FFA spawns ask to confirm. Point buttons use the place you stand when you click; names and numbers are typed in chat. `/duels arena help` lists the commands.

A fighter who falls out of the bottom of the box loses, as in the void (kit rule `void-eliminates`); leaving
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
Teammates can't hurt each other unless the kit rule `friendly-fire` is on. A party FFA puts each player on one of
the arena's extra spawns (`/duels arena addspawn`, one per player) when there are enough; otherwise they are
spread from spawn 1 to spawn 2, or put on spawn 1 and 2 in turn where there is no floor.

**Free-for-all arenas.** `/duels arena ffa pit nodebuff` makes arena `pit` the free-for-all arena of kit
`nodebuff` (`none` undoes it); it then takes no duels and is never copied by the pool. Build kits are refused, as
nothing would put the blocks back while players stay. Players join with `/duel ffa [kit]` (the kit menu without a
kit), the `ffa` lobby item or the play menu, and leave with `/duel leave`. They come in at a random spawn (spawn 1,
spawn 2 or an extra spawn), with the kit and its rules, and may hit and be hit once they are there. A death brings
them back at a random spawn with the kit; the last hitter gets the kill (and a kill message cosmetic shows). Leaving
or quitting within 15 seconds of a hit counts as a death too. Their state is saved first and restored on leaving,
as in a duel. The fight opens with the first player and closes with the last. Kills, deaths and the best kill
streak per kit are kept (`/duel ffa stats [player]`); the sidebar shows those since joining (messages.yml
`sidebar.ffa`), and `/duel leaderboard` has free-for-all boards (most kills in every kit, or in one: `/duel
leaderboard ffa` or `ffa:<kit>`). The arena admin menu's Modes submenu sets the free-for-all kit too. Free-for-alls pay no rewards and fire no `MatchStartEvent`/`MatchEndEvent`.

**2v2 queues.** `/duel 2v2 [kit]` (unranked) and `/duel 2v2ranked [kit]` (`duels.queue.ranked`) queue the party
of two you lead as a team, or you alone outside a party: solo players are paired into teams in the order they
joined. Only the leader queues a party, and only a party of exactly two. Teams pair only when every player fits every
opponent's ping range (`/duel options`). Ranked teams also need average ratings within the range of the team waiting
longer, which widens as in solo ranked queues; the daily ranked limit and `ranked.required-wins` apply to every
member. Two teams fight a team duel in a random free arena of the kit. Each winner gets a win and each loser a loss
(forfeits and quits count, as in duels); ranked, each winner takes the same rating from a loser, worked out from the
teams' average ratings. Rewards and rematches are left alone. 2v2 duels have their own history: the history menu's
button (or `/duel history [player] 2v2`) switches between 1v1 and 2v2 duels.
A team loses its place once a member goes offline, gets busy, joins a solo queue or the party changes.

**The party menu.** `/party` opens it: in a party, a head per member (the leader clicks one to make them
leader or kick them, which asks to confirm), a button to invite (a name typed in chat), and two submenus:
fights (split, FFA, challenge another party) and settings (public or private, leave, disband, which asks to
confirm). Without a party it lists the
public parties, a click joining one, and has a button to make a party. A public party (`/party public`) takes
anyone with `/party join <leader>`; going public is announced to players not in a party who take party
invites, with a click to join.

**Build limit.** `/duels arena buildlimit desert 80` stops fighters placing blocks above Y 80 (`none`
removes it), so bridges and towers stay low.

**Copies.** An arena hosts one duel at a time. Once it has a snapshot (`/duels arena snapshot desert`, taken
while the arena is as it should be), a duel that finds it busy gets a copy instead: WorldEdit pastes one in the
arenas world (`arenas.world`, an empty void world made at start), chest contents, sign text, banners and heads
included. The fighters wait for the paste ("Preparing a copy of the arena..."), then the duel starts. A copy has
the arena's spawns, center, goals, beds, categories and build limit, and players see the arena's name.

- `arenas.pool.warm` (default 1) places are kept ready per arena, the arena itself counting while free: while
  `desert` is busy, one copy is pasted ahead, so the next duel starts at once.
- A copy free for `arenas.pool.idle-timeout` (default 2 minutes) is cleared, unless it is one of the warm places.
- `arenas.pool.max-copies` (default 32) caps the copies of one arena; `0` turns copies off. Copies are
  `arenas.pool.spacing` blocks apart.
- `/duels arena pool desert` shows how many copies are in use and free; `/duels arena pool desert clear` clears
  the free ones now.
- After changing `desert`'s blocks, snapshot it again: free copies of the old snapshot are cleared, copies in use
  once their duel ends. Changing its corners drops its snapshot.
- Copies only live while the server runs: a restart clears the copies left in the arenas world.
- FAWE pastes and clears off the main thread; plain WorldEdit does it on the main thread, which can lag for big
  arenas.

Copies made by `/duels arena pregen` in earlier versions are removed at the first start and their space is
cleared. An arena with an older snapshot gets the WorldEdit one at that start, from its blocks as they stand.

**WorldEdit tools:**

- `/duels arena setbox desert` sets both corners from your WorldEdit selection (`//wand`).
- `/duels arena import desert desert.schem` pastes `plugins/ElotecraftDuels/schematics/desert.schem` (the folder
  is made at start) at a free place in the arenas world (lowest layer at Y 64) and makes it arena `desert` with
  its corners set; set its spawns, then snapshot it.

The arena must allow PvP: check the world's `pvp` setting and WorldGuard flags. If you use a combat-tag
or graves plugin, exclude the arena regions; duels never fire death events, but combat tags still apply.

**A kit** is a full inventory, armor and off hand included:

1. Put the items in your inventory and run `/duels kit create archer`. The item in your hand becomes its icon.
2. Edit it later in the kit editor menu (`/duels kit edit archer`), or: empty your inventory, `/duels kit load archer`,
   change it, `/duels kit save archer` (for items the editor does not offer, such as named or custom items).
3. Optional: `/duels kit setname archer <green>Archer`, `/duels kit setpermission archer duels.kit.archer`.
4. `/duels kit toggle archer [on|off]` turns a kit off (or the "Players can use it" button in `/duels kit archer`):
   it disappears from every menu and tab completion, its queues empty, and nobody, staff included, can queue,
   challenge, fight parties or host events with it. Fights already running finish, and its ratings stay.

**Or use the menus:** `/duels kit` lists every kit (with buttons for a new kit and the default kits),
and `/duels kit <kit>` opens one kit's settings: icon, name, permission, arena categories, mode, on/off,
edit its items in the kit editor, save or load its items, delete (asks to confirm), a game rules submenu with every rule, `build` and
`damage` included (left-click switches, right-click resets), and a potion effects submenu. Names and
values are typed in chat, effect amplifiers and seconds in anvils. Each button runs the
matching command, so the same permission and checks apply. `/duels kit help` lists the commands.

Kits are stored in `kits.yml` with Paper's item format, so they survive server updates.

**The kit editor.** A chest menu laid out like an inventory (menus.yml `kit-editor`): armor in the second row
(helmet to boots, then the off hand), storage in rows 3 to 5, the hotbar at the bottom, and buttons for the map,
rules, info, reset and the kit's name. The player's own inventory is never touched, and closing the editor saves
it (also on quit or shutdown). It edits three things:

- **Own layouts.** `/duel editkit [kit]` (permission `duels.kit.edit`): a click picks an item up, the next puts it
  in the clicked slot (swapping); the armor stays where it is. Every duel with the kit then uses the layout, which
  may also get a name. When an admin changes a kit's items, old layouts are dropped; `/duel editkit reset <kit>`
  drops one by hand.
- **Custom kits.** With config.yml `custom-kits.base-kit` set to a kit, players build their own kits with
  `/duel customkit` (permission `duels.kit.custom`): a menu of their `custom-kits.slots` kits (3 by default); a
  click opens one in the editor. Left-click a slot to pick an item (armor slots show that slot's armor; the others
  item categories: menus.yml `kit-editor-categories`), shift-left-click to empty it, right-click to enchant it
  (items enchantable in survival: a row of books per enchantment, plus mending and the curses, unbreakable and
  durability) or change its count (presets or a typed number), shift-right-click to copy the last item. The map
  button picks one arena the base kit takes (or Random), the rules button the kit's own game rules. Only the
  offered items, with fair enchantments, counts and durability, can be saved (an empty kit is deleted). Players
  challenge with the kit `custom:<number>` (`custom` alone is the first), or pick it in the kit menu when
  challenging; both fighters get the builder's items, with their rules and arena, and the base kit's arena
  categories, permission, rewards, effects, mode and other rules (the base kit may hold no items). Custom kits are
  for challenges and party fights only: not for queues, events or bets. A custom kit holding an item the editor
  no longer offers is not offered until it is built again. They are stored with the kit layouts. Spawn eggs
  from the editor (horses with armor, a charged creeper) carry their mob, and mobs hatched in a fight are
  removed with the arena's leftovers.
- **Admin kits.** `/duels kit edit <kit>` or the Edit items button: the same clicks on the kit itself; the rules
  button opens the kit's rules menu.

**Default kits.** A first start (no `kits.yml` yet) adds 20 kits after the most played practice modes:
NoDebuff, Debuff, Gapple, BuildUHC, Classic, Archer, Sumo, Boxing, Combo, Vanilla (crystals and anchors), UHC,
Pot, NethOP, SMP, Sword, Axe, Mace, Spear, Bridge and Bed Fight (see [kit modes](#kit-modes); their arena
categories are `bridge` and `bedfight`). BuildUHC, Vanilla and UHC are build kits. Sumo hits only knock back
(the kit rule `damage`, `/duels kit rule <kit> damage false` for any kit): falling off the arena loses a round of a best of 3, so give
it a small platform arena in category `sumo` (`/duels arena category <arena> add sumo`). Boxing hits only knock
back too: the first to land 100 hits wins. Combo has no hit delay. Edit them like any kit;
`/duels kit defaults` adds the ones that are missing on an existing server and never overwrites a kit.
UHC and BuildUHC drain hunger without natural regeneration; NoDebuff, Debuff and Pot have a 15 second
pearl cooldown (see [game rules](#kit-game-rules)). Kits added before game rules existed keep the
defaults: set them by hand, e.g. `/duels kit rule uhc natural-regeneration false`.

## Kit game rules

`/duels kit rule <kit>` lists a kit's game rules; `/duels kit rule <kit> <rule> <value>` sets one, and
`default` as the value unsets it. They are saved under `rules` in `kits.yml`. A rule a kit does not set takes
its value from the list `rules.kit-defaults` in `config.yml`, which holds every rule; the Default column is the
value it ships with. Changing that list changes every kit that does not set the rule. A number default may be
`vanilla`, leaving the game as it is. Older `config.yml` files (`rules.hunger`, `rules.natural-regeneration`,
`rules.void-eliminates`, `parties.friendly-fire`) and `kits.yml` files (`build` and `damage` beside `rules`)
are moved to the new places at start.

| Rule | Default | |
|---|---|---|
| `build` | false | Fighters place blocks and break the ones placed during the duel ([build kits](#build-kits)) |
| `damage` | true | false: knockback only (Sumo), hits never hurt and falling off the arena decides |
| `hunger` | false | true: food drains during the fight |
| `natural-regeneration` | true | false: a full hunger bar no longer heals (UHC); potions and golden apples still do |
| `friendly-fire` | false | Teammates hurt each other in party fights |
| `void-eliminates` | true | Falling out of the bottom of the arena loses |
| `arena-bounds` | true | Fighters leaving the arena's box by the sides or the top go back to their spawn; false lets them out (kits played over the void, e.g. bridge), and they fall out of the bottom. Spectators always stay in the box. The default Bridge and Bed Fight kits have it off |
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
| `arrow-cooldown` | vanilla (none) | Seconds a bow or crossbow cannot be used after a shot, 0 to 60 |
| `gapple-cooldown` | vanilla (none) | Seconds between golden apples, 0 to 60 |
| `cooldown-bar` | true | The experience bar counts the pearl, arrow and golden apple cooldowns down (the longest running): the level is the seconds left. Fighters start every duel without experience |
| `hits-to-win` | off | Boxing: a fighter hit this many times by opponents is out, so the first to land them wins a duel; 1 to 1000 (0: off). The attacker sees the count in the action bar. Pair it with `damage` false |
| `rounds-to-win` | 1 | Duels only: the first fighter to win this many rounds wins the duel; 2 to 10 (0 or 1: one round). Between rounds both are healed and re-kitted and go back to their spawns, and a build arena is put back. `match.max-duration` applies to each round; a round running out of time ends the duel in a draw, and quitting or `/duel leave` loses the whole duel. Stats, rating, rewards and history count the duel once |
| `max-health` | off | Fighters' maximum health in health points, 1 to 200 (20 is ten hearts; 0: off). They start the fight full; it is undone when they are sent back, and after a crash |
| `damage-multiplier` | off | Percent of the damage opponents deal, 1 to 500 (50 halves it, 200 doubles it; 0: off) |
| `saturation` | false | Food and saturation stay full (an endless saturation effect), so health comes back fast |
| `auto-ignite-tnt` | false | Build kits: placed TNT is lit at once, and counts as lit by its placer (self-damage rule) |

With any of the three drop rules on, fighters pick up items inside the arena.

**Effects.** `/duels kit effect <kit> <effect> <amplifier> [seconds]` gives fighters a potion effect without
particles (any Minecraft effect name): amplifier 0 to 2 (0 is level I, 2 is level III), and 0 to 9999 seconds,
0 or none for the whole fight. `/duels kit effect <kit> <effect> remove` takes it away, and
`/duels kit effect <kit>` lists them. In the menus, the kit's **Potion effects** button lists every effect,
the kit's own first: a click asks the amplifier and then the seconds in two anvils, a right-click on a given
one removes it. Whole-fight effects are given at the countdown, timed ones when the fight starts (again each
round), so the countdown does not use them up. They are stored under `effects` in `kits.yml`, e.g.
`effects: {speed: {amplifier: 1, seconds: 0}}`, and taken away with everything else when the fight ends.
The older list (`effects: ["speed 2"]`, a level) is read as amplifier level - 1, at most 2, for the whole
fight, and rewritten at start.

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

## Kit modes

`/duels kit mode <kit> [normal|bridge|bed-fight|mlg-rush|spleef]` (or the Mode button in `/duels kit <kit>`) changes
how a fight with two sides is won; every mode but normal also makes the kit a build kit. Fights with more sides
(a party FFA) play them as normal kits.

- **Bridge.** Each side has a goal: `/duels arena setgoal <arena> <1|2>` where you stand (side 1 starts at
  spawn 1). Passing through the other side's goal, a flat ring at the goal point's block layer
  (`modes.bridge.goal-radius` blocks across in x and z, 2 by default; a fall through it counts, going over or
  beside it does not), or into an end portal nearer the other side's goal than your own (goals are often end portal pits of any size), wins
  the round; the kit rule `rounds-to-win` is the number of goals to win (5 for the default Bridge kit). Portals
  never take anyone anywhere and say nothing. The scorer watches as a spectator from the arena's center (or
  halfway between the spawns) until the next round. A `match.goal-hologram` ("JUMP") floats above each goal
  (`modes.bridge.goal-hologram`). A golden apple heals a fighter fully at once, on top of its own effects.
  Knocked-out fighters come back at their spawn with the kit at once. Blocks placed stay from round to
  round and are put back when the duel ends, and nobody can place blocks within
  `modes.bridge.protect-radius` (3) of a spawn or goal.
- **Bed fight.** Each side has a bed: look at it and run `/duels arena setbed <arena> <1|2>`. Knocked-out
  fighters come back at their spawn while their bed stands; fighters may break the other side's bed (not
  their own), after which that side is out once knocked out. The bed is put back after each round and the duel.
- **MLG Rush.** Beds as in a bed fight, but breaking the other side's bed wins the round (`rounds-to-win`
  beds win the duel, 5 for the default kit) and knocked-out fighters always come back. The arena is put back
  between rounds.
- **Spleef.** Fighters may break the arena's own blocks, not only those placed during the duel; falling out of
  the bottom of the box loses (kit rule `void-eliminates`). The arena is put back between rounds.

The kit rule `fireballs` lets fighters throw a fireball by right-clicking a fire charge (one per half second;
it sets nothing on fire and breaks only what the fight may break), and `auto-ignite-tnt` lights placed TNT at
once. The default kits use them: **Fireball Fight** (bed fight, fireballs and TNT, arena category `bedfight`),
**BattleRush** (bridge with knockback sticks, 3 goals, category `bridge`), **MLG Rush** (category `mlgrush`),
**Spleef** (an Efficiency V shovel, category `spleef`), **TNT Sumo** and **Pearl Fight** (knockback only, category
`sumo`). On a server from before them, `/duels kit defaults` adds the missing ones.

In every mode side 1 is red and side 2 blue: leather armor is dyed, and wool and terracotta (not glazed) in the
kit become the side's color, again on every respawn and round.

The `/duels arena <arena>` menu has a button for each goal and bed too (for a bed, look at it before opening
the menu). A bridge kit only uses arenas with both goals, a bed fight kit only arenas with both beds. Copies take the
points of their arena when they are pasted.

## Build kits

The kit rule `build` (`/duels kit rule <kit> build true`, or `default` to undo) makes a build kit: while fighting, its
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
| `/duel` | | The duels menu: play (queues, events, spectating), profile, party, kits, cosmetics, options and admin (for `duels.staff`); the help for the console |
| `/duel menu [name]` | | A menu of menus.yml `hub`, e.g. `play` or `profile`; add your own there |
| `/duel help` | | The command list |
| `/duel ratings` | `duels.stats` | Your rating, peak, division and ranked record in each kit |
| `/duel leaderboard [wins\|elo\|kit]` | `duels.top` | A menu of leaderboards (most wins, overall rating, each kit's rating; menus.yml `leaderboard`), or one of them: the best 28 players as heads (`leaderboard-board`). Opening a board counts as a lookup, like `/duel top` |
| `/duel <player> [kit] [arena]` | `duels.duel` | Challenge; without a kit the kit menu opens, then the arena menu |
| `/duel <player> <kit> [arena] bet <amount>` | `duels.bet` | Challenge for money: each player stakes `<amount>`, the winner takes both ([bets](#bets)) |
| `/duel accept [player]`, `/duel deny [player]` | `duels.duel` | Answer a challenge (clickable in chat too) |
| `/duel customkit [number]` | `duels.kit.custom` | Your [custom kits](#setting-up): a menu of them, or one in the kit editor |
| `/duel rematch` | `duels.duel` | Challenge your last opponent again, or accept their rematch |
| `/duel playagain` | `duels.queue` | Join the queue your last duel came from again (`duels.queue.ranked` too for a ranked one), within `requests.rematch-window` of its end |
| `/duel queue [kit]` | `duels.queue` | Join or leave a kit's unranked queue (menu without a kit) |
| `/duel ranked [kit]` | `duels.queue.ranked` | Join or leave a kit's ranked queue (menu without a kit) |
| `/duel 2v2 [kit]` | `duels.queue` | Join or leave a kit's 2v2 queue with your party of two, or alone (menu without a kit) |
| `/duel 2v2ranked [kit]` | `duels.queue.ranked` | The same for the ranked 2v2 queue |
| `/duel ffa [kit]` | `duels.ffa` | Join a kit's free-for-all arena (menu without a kit) |
| `/duel ffa stats [player]` | `duels.ffa` | Free-for-all kills, deaths, kills per death and best streak in each kit |
| `/duel spectate [player]` | `duels.spectate` | Watch someone's duel; without a player, a menu of the fights you may watch (layout in menus.yml `spectate`). While watching (or knocked out), it takes you to a fighter of your fight; without a player, a menu of their heads (menus.yml `spectate-fighters`), also opened by clicking the compass in the middle of your inventory (E) while watching (`spectate-fighters.item`) |
| `/duel leave` | | Leave the queue, stop spectating, leave a free-for-all, or forfeit |
| `/duel cancel [player]` | `duels.duel` | Take back a challenge you sent (also the [CANCEL] after sending) |
| `/duel toggle <option>` | | Turn an option off or on: `requests`, `party-invites`, `sidebar` or `sounds`; the same switches as `/duel options`. Alone, it lists the options |
| `/duel cosmetics [kill-effect\|kill-message]` | `duels.cosmetics` | Pick a kill effect and a kill message (see [Cosmetics](#cosmetics)) |
| `/duel options` | | A menu where players turn duel requests, party invites, the sidebar, duel sounds, spectators of their fights and other players in the lobby off or on, and set their ping range; kept in their player data across restarts (not across servers). Layout in menus.yml `options`; remove an option's section to stop offering it. See [Player options](#player-options) |
| `/duel stats [player]`, `/duel top [elo [kit]]` | `duels.stats`, `duels.top` | Statistics, and leaderboard by wins, overall rating or a kit's rating |
| `/duel top season <number>` | `duels.top` | An ended season's final overall ratings |
| `/duel season` | `duels.stats` | The season running: its name, how long it has run and has left, and your rating |
| `/duel history [player]` | `duels.history` | A player's latest 50 duels, online or not: opponent, kit, arena, how it ended, health left and rating change |
| `/duel inventory` | | Opened by clicking a fighter's name in a duel's result line, or in the "Inventories" line sent after party fights and events, once the fight is over: that fighter's items, health, food and effects as the fight left them, hits landed, longest combo and health potions thrown, missed and accuracy. Kept for 10 minutes |
| `/party` | `duels.party` | The party menu: your party's members and buttons, or the public parties to join (layouts in menus.yml `party` and `party-none`) |
| `/party <player>`, `/party accept\|deny [player]` | `duels.party` | Invite someone (makes a party if you have none) or answer an invite |
| `/party public` | `duels.party.public` | Leader: let anyone join without an invite (announced once a minute at most), or stop |
| `/party join <leader>` | `duels.party` | Join a public party |
| `/party chat [message]`, `/pc [message]` | `duels.party.chat` | Talk to your party; the message shows as typed. Without a message, switches party chat mode: what you type in chat goes to your party until you run it again or leave the party |
| `/party kick\|promote <player>`, `/party disband` | `duels.party` | Manage the party (leader only) |
| `/party leave`, `/party info` | `duels.party` | Leave the party, list its members |
| `/party split [kit] [arena]`, `/party ffa [kit] [arena]` | `duels.party.fight` | Leader: two teams picked in a menu, or everyone for themselves |
| `/party duel <leader> [kit] [arena]`, `/party duelaccept\|dueldeny [leader]` | `duels.party.fight` | Leader: challenge another party, or answer a challenge |
| `/event` | `duels.event` | Events to join or watch; your event's settings while you host one |
| `/event join <host>`, `/event leave` | `duels.event` | Join or leave an event that has not started (`/duel leave` works too) |
| `/event host [kit]` | `duels.event.host` | Host an event (kit menu without a kit), then set it up in its menu |
| `/event settings\|start\|cancel`, `/event invite <player>` | `duels.event.host` | Run your event; invite players while it is private |
| `/duels arena ...` | `duels.admin.arena` | menus: no argument or an arena name; `help`, `create`, `delete`, `setspawn`, `setcorner`, `setgoal`, `setbed`, `setbox`, `import`, `setspectator`, `setcenter`, `seticon`, `setname`, `category`, `buildlimit`, `toggle`, `info`, `tp`, `list`, `snapshot`, `reset`, `pool` |
| `/duels kit ...` | `duels.admin.kit` | menus: no argument or a kit name; `help`, `create`, `save`, `edit`, `load`, `delete`, `seticon`, `setname`, `setpermission`, `mode`, `toggle`, `effect`, `rule`, `arenas`, `defaults`, `list`, `goldenhead` |
| `/duels hologram create <name> <wins\|elo> [kit]`, `delete <name>`, `list` | `duels.admin.hologram` | [Leaderboard holograms](#leaderboard-holograms) where you stand |
| `/duels` | `duels.staff` | The admin menu: arenas, kits, season, holograms, ratings, reload (asks to confirm); the help for the console. `/duels help` shows the help |
| `/duels season [list \| info <n> \| top [n] [kit] \| player <player> [n] \| divisions \| compare <a> <b> \| kits [n]]` | `duels.admin.season` | The [season](#seasons) running, ended ones, leaderboards, a player's ratings, divisions, two seasons side by side, the kits played |
| `/duels season schedule <days\|yyyy-MM-dd\|off>`, `auto <on\|off\|toggle>`, `name <n> <name\|off>`, `export <n>` | `duels.admin.season.manage` | Plan the end, make it end by itself, name a season, write `seasons/season-<n>.csv` |
| `/duels season end [preview \| confirm]` | `duels.admin.season.manage` | What ending would archive and pay; end it (asks for `confirm` within 30 seconds) |
| `/duels elo <player>` | `duels.admin.elo` | A player's ratings, online or not |
| `/duels elo set\|add <player> <kit\|all> <value>`, `elo reset <player> [kit\|all]` | `duels.admin.elo.edit` | Change them (0 to 10000, logged); reset asks for `confirm` |
| `/duels stop <player>` | `duels.admin.stop` | End a duel without a result |
| `/duels reload` | `duels.admin.reload` | Reload config, messages, menus, arenas and kits |

Choosing the arena needs `duels.select-arena`; without it arenas are random. `duels.player` (everyone by
default) grants all player permissions, hosting events included; `duels.admin` (operators) grants all
admin ones plus `duels.bypass.cooldown`, which also skips the event host cooldown, and
`duels.bypass.commands`, which allows every command during a duel, and can watch events that forbid spectators. `/duels` itself needs `duels.staff`
(in `duels.admin`), and each part of it its own permission. `duels.admin.season.manage` includes `duels.admin.season`,
and `duels.admin.elo.edit` includes `duels.admin.elo`; the season warnings go to `duels.admin.season.manage`.

### Permissions for ranks

Every feature above has its own permission, so a permissions plugin such as LuckPerms can give each rank
its own set. Player permissions are on for everyone by default: to keep one for a rank, take it from
`default` and give it to the rank, whose own permission wins over the one it inherits:

```
lp group default permission set duels.bet false
lp group vip permission set duels.bet true
lp group vip permission set duels.select-arena true
lp group mod permission set duels.staff true
lp group mod permission set duels.admin.stop true
lp group mod permission set duels.admin.season true
```

Some limits from config.yml can be raised per rank with a number at the end of a permission; the
highest a player has counts, and never less than config.yml:

| Permission | Raises | Up to |
|---|---|---|
| `duels.party.size.<n>` | `parties.max-size`, by the party leader's permission | 100 |
| `duels.kit.custom.slots.<n>` | `custom-kits.slots`; kits in slots a player loses are kept, not offered | 9 |
| `duels.queue.ranked.limit.<n>` | `ranked.daily-limit` (no effect while it is 0, no limit) | 1000 |

For example `lp group vip permission set duels.party.size.12 true`.

## How a duel runs

1. Both players' state (position, inventory, health, hunger, xp, effects, game mode, flight) is saved to
   the database. If that fails, nothing changes.
2. They are teleported in, given the kit (experience emptied) and frozen during the countdown. They can still arrange their
   inventory, draw a bow and load a crossbow (an arrow released before the fight starts is refused).
3. They fight until one would die: the lethal hit is cancelled instead, so there is no death screen and
   nothing drops (unless the kit has `death-drops`). A totem in hand still works. Quitting or `/duel leave` loses; running out of time
   (`match.max-duration`) is a draw. With the kit rule `rounds-to-win`, a lethal hit only wins the round:
   after `match.round-delay-seconds` the arena is put back and steps 2 and 3 repeat until someone has
   won enough rounds.
4. The result shows for `match.end-delay-seconds`, then everyone is put back exactly as they were.

While in a duel or spectating, players cannot drop, pick up (except fighters with a drop rule)
or store items, open containers or other menus, change blocks (except with a [build kit](#build-kits)), use commands other than `/duel` and
`rules.allowed-commands` (`duels.bypass.commands`, operators by default, allows all), or teleport out of the arena (pearls inside it work). Nether and end portals take
nobody and nothing out of a duel or the arenas world, and none can be lit there. Among players only the
two fighters can hurt each other; mobs, fall damage and the like still apply (unless a
[game rule](#kit-game-rules) turns them off), so keep arenas mob-free and
protected (e.g. WorldGuard). Explosions never break arena blocks, and arrows, tridents, pearls, dropped
items and falling blocks left in an arena are removed when a duel ends. Items dropped in a duel can never
be picked up outside it, by players, mobs or hoppers.

A crash, kick or reload never leaves anyone stuck: on shutdown everyone is put back, and a duel that was
cut short by a crash is undone when the player next joins.

## Configuration

- `config.yml`: database, countdown, duration, end delay, request expiry and cooldown, rematch
  window, archers seeing the health their arrow left (`match.arrow-health`), hunger, regeneration and void rules, allowed commands, build kit and arena regen rules, the
  arenas world and arena copies, ranked rating and queue range, party size and invite expiry, events,
  the sidebar, rewards, title timings, sounds and particles.
  Invalid values are logged and replaced by defaults.
- `messages.yml`: every text players see, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html).
  Add `messages_<language>.yml` (e.g. `messages_es.yml`) for players whose client uses that language.
- `menus.yml`: titles, sizes and button items of every menu. Add `menus_<language>.yml` (e.g. `menus_es.yml`) with
  only the texts to translate (`title`, `name`, `lore`, `values`) at the same paths, e.g.
  `hub: {main: {title: "Duelos"}}`; slots, items and commands come from `menus.yml`. Lists such as `lore` are
  replaced whole. `/duels reload` picks up new files. Give a translation `version: <n>` only if it copies whole
  menus: one older than the current layout is then moved aside like `menus.yml`. List menus (kits, arenas, history...)
  center their entries below an empty top row, between empty side columns, with no filler; their
  bottom row has the page arrows, back (where there is a menu to go back to) and close. Each title is a
  plain-text breadcrumb (`Play › Ranked`; kit and arena names in titles lose their colors). The `hub` menus
  (`/duel`, `/duel menu <name>`, and `/duels` for admins) are buttons that run a command; a button or a whole
  menu may need a `permission`, and a button with `confirm: true` asks first, so you can add buttons or whole
  menus there; their texts may use the player's stats, the queue, fight and event counts and the season
  (`<season_name>`, `<season_days>`, `<season_ends>`, `<season_auto>`). Changes that are hard to undo open the
  `confirm` menu. Right-clicking a kit in a kit menu shows its items (`kit-preview`). `/duel ranked` and
  `/duel queue` have their own menus, `ranked-queue` and `unranked-queue`, with a button to switch between them.

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
`ranked.required-wins` (0, none, by default) is the number of duels a player must have won, of any kind, before
joining a ranked queue.

### Seasons

`/duels season end`, then `/duels season end confirm` within 30 seconds, ends the season running (the first is
season 1; `%duels_season%`). Back up the database first: it cannot be undone. In one transaction every kit
rating (with its wins, losses and peak) is copied into `duels_seasons` under the season's number, then all
ratings are reset, so everyone starts the next season at 1000 in every kit. Then each player whose overall
rating ended in a division with a `season-reward` (config.yml `ranked.divisions`) is paid it, online or not:
money through Vault and console commands with `<player>`, `<division>`, `<elo>` and `<season>`. Everyone online
is told. `/duel top season <number>` shows a past season's final overall ratings. Ranked duels still running
when a season ends count in the new season.

`duels_season_info` keeps when each season started, its planned end, whether it ends by itself and its name.
On a database from before it, the season running started when the last one ended, or, if none has, when the
update started (`/duels season` says so).

**Planning the end.** `/duels season schedule 30` plans the end 30 days from now (or a date, `2026-12-31`;
`off` for none), and `seasons.default-length-days` in config.yml plans each new season. It only informs until
`/duels season auto on`: then the season ends by itself at its planned end, exactly like `end confirm`, and so
does each next one (auto end carries over). Auto end is off until you turn it on, and stays as you leave it
across restarts; `/duels season auto off` stops it any time. A server that was down at the planned end ends
the season once it starts. Auto end waits for ranked duels being fought to finish (up to 10 minutes), so they
count in the season they started in; a failed end is tried again every 5 minutes. Before the end, players with `duels.admin.season` are told
(`seasons.admin-warnings`, 24h and 1h by default) and, when the season will end by itself, everyone is
(`seasons.player-warnings`, 10m). Past a planned end without auto end, admins are told when they join.
`/duels season end preview` shows what an end would archive and pay without ending anything.

**Looking at seasons.** `/duels season` shows the running season (name, start, how long it has run, the planned
end, auto end, rated players, ranked duels and its top 3); `list` the ended ones with their dates; `info <n>`
one of them with its top 5; `top [n] [kit]` a leaderboard; `player <player> [n]` a player's rating, peak and
record per kit; `divisions` how many players each division holds and how many rewards an end would pay;
`compare <a> <b>` two seasons' players, duels, average rating and most played kit; `kits [n]` the kits played
ranked most. `name <n> <name>` names a season (MiniMessage, up to 64 characters), shown in these and in
`%duels_season_name%`. `export <n>` writes every rating of a season to `seasons/season-<n>.csv`.

**Ratings.** `/duels elo <player>` shows a player's ratings; `set` and `add` change them in one kit or `all`
(a missing rating starts from the player's starting one; the peak rises with it), `reset` puts them back to 1000
with the peak and asks for `confirm`. It works for offline players, updates online players at once, logs each
change with who made it, and refuses players in a duel or a ranked queue.

Updating from a version with one rating for all kits: back up `duels.db` first. Nothing is converted;
each player's first ranked duel in a kit starts from their old rating, which stays stored unchanged.

## Lobby items

For practice servers: with `lobby-items.enabled` in config.yml, players in the lobby worlds
(`lobby-items.worlds`, empty for every world except the arenas world) get hotbar items that run a command
when right-clicked. The defaults are unranked queue, ranked queue, party, cosmetics, events, free-for-all (slot 5, the
rematch item's while it shows), 2v2 queue, edit kits and match history; options is there but off;
while queued or waiting for an event, a "Leave the queue" item takes the queue items' place. After a duel, while
`requests.rematch-window` is open, "Rematch" shows (with an offer to answer or send) and, after a queue duel, "Play
again" takes the unranked queue item's place. The items are
set in menus.yml `lobby-items`: slot, look, command, `show` (`idle`, `waiting`, `always`, `rematch`, `play-again`, or
`never` to drop a default), `enabled` (`false` turns an item off; a menus.yml from before gets every item's
`enabled` added, options with `false`, as the 2v2 item takes its slot 6) and an optional permission. Of items sharing a slot, `waiting` ones show first, then
`rematch` and `play-again` ones.

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
otherwise. In a [bridge or bed fight](#kit-modes), one more line goes under the first: the goals of each side
(`sidebar.goals`, which takes the place of `<rounds>`) or whether each bed stands (`sidebar.beds`), from the
fighter's side, or side 1 then side 2 for spectators (`goals-spectating`, `beds-spectating`).

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

Players pick one kill effect and one kill message in `/duel cosmetics` (a menu with one button for each, or
`/duel cosmetics kill-effect|kill-message`; click again to drop it). When they
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

Four more kinds work the same way (`/duel cosmetics death-message|win-sound|armor-trim|shield-pattern`, menu
layouts named after the kind):

- **Death messages** (`death-messages`, texts in messages.yml `death-messages.<id>`): shown when the player is
  knocked out and the killer has no kill message.
- **Win sounds** (`win-sounds`, a sound and optional particle like `effects`): played to everyone in the fight
  when the player wins.
- **Armor trims** (`armor-trims`: `pattern` and `material`, vanilla trim names): put on every trimmable armor piece
  of the player's kit at each equip.
- **Shield patterns** (`shield-patterns`: a `base` dye color and `patterns`, layers of `"<pattern> <COLOR>"` with
  vanilla banner pattern names, bottom first): put on every shield of the player's kit.

An entry with a wrong pattern, material or color is logged and left out.

## Levels

Duels and 2v2 duels count kills and deaths (the last opponent to hit gets the kill, as for kill effects) and give
experience: config.yml `progression` `win-xp` (20), `loss-xp` (5) and `kill-xp` (5 per kill). Level n needs
`level-xp` * n * (n - 1) / 2 experience in all (100: level 2 at 100, 3 at 300, 4 at 600). `/duel stats` shows kills,
deaths, K/D, level and experience; the lobby sidebar shows the level (`<level>`, also `<kills>` and `<deaths>`).

## Leaderboard holograms

`/duels hologram create <name> wins` places the top players by wins where you stand (`holograms.lines`, 10 by
default); `... elo` the top overall
ratings and `... elo <kit>` one kit's. They use the lines of `/duel top` from messages.yml (`top.header`,
`top.line`, `top.elo-header`, `top.elo-kit-header`, `top.elo-line`) and are read from the database every
minute. Creating one with a name in use moves and replaces it; `/duels hologram delete <name>` removes it and
`list` lists them. They are stored in `holograms.yml` and come back after restarts and when their chunk loads
again. No hologram plugin is needed.

## Events

Players host events with `/event host [kit]`. A new event is private: only players the host invites can
join. Once the host makes it public it is announced to everyone with a clickable [JOIN] (again every
`events.announce-interval`) and listed in `/event`. Scheduled events start public. While players gather, the host
sets it up in the event settings menu (`/event settings`):

- **Kit** and **arena** (random by default; choosing one needs `duels.select-arena`).
- **Rules**: the kit's game rules for this event only (potions, hunger, fall damage...); the kit
  itself is not changed. Changing the kit resets them.
- **Mode**: free for all, team vs team, a game (below), tournament, sumo or double elimination. Teams are picked in the team
  menu when the host starts it, or split at random when it starts on its own. A [tournament](#tournaments) is
  1v1 knockout rounds; sumo is the same with one fight at a time; double elimination puts a player out after
  two losses.
- **Winners**: in a free for all, how many of the last players standing win.
- **Border**: closes in on the fighters (`events.border`): it starts around the arena, waits `delay`
  into the fight, then shrinks to `min-size` blocks across over `shrink-time`; fighters outside lose
  `damage` health a second. Each fighter is shown their own border, so the world border is untouched.
- **Players and spectators** (a submenu): **Public** (off at first) lets anyone join, announced and listed;
  off, only players invited with `/event invite` (or the Invite button) can; **Spectators** off stops anyone but staff from
  watching it.

Cancelling it from the menu asks to confirm. It starts when the host clicks Start (`/event start`), at once when `events.max-players` have joined, or
when `events.wait-time` runs out; with fewer than `events.min-players` then, or no free arena, it is
cancelled. The host leaving or quitting cancels it too. Joined players can do anything in the lobby, but
cannot queue, duel or spectate until it starts or they `/event leave`.

The fight runs like a party fight: knocked-out players watch until it is decided, then everyone is put
back. The result goes to the whole server (`events.broadcast-result`). With a lethal hit deciding it,
each winner gets `events.reward`: money through Vault and console commands with `<winner>`, `<host>`,
`<kit>` and `<arena>`. Leave it empty (the default) for a broadcast only. Events never change stats or
ratings, and a host waits `events.host-cooldown` between events.

### Event games

Modes that play a game on top of the event's kit, everyone for themselves unless said:

- **Juggernaut**: one random player against everyone else, with Health Boost IV, Resistance I and Strength I.
- **One in the chamber**: a wooden sword, a bow and one arrow; an arrow kills, a kill gives an arrow, and each player
  has 3 lives (knocked out, they come back at their spawn).
- **King of the hill**: standing alone within 3 blocks of the arena's middle (its center, else halfway between the
  spawns) earns a second; the first to 60 wins. Knocked-out players come back. The action bar shows who holds it.
- **TNT tag**: a random player carries TNT on their head; their hit passes it on, nobody is hurt, and after 20
  seconds it blows its carrier out of the event. The next carrier is picked at random.
- **Splegg**: an iron shovel shoots eggs that break the arena block they hit (the arena is put back afterwards, as
  in a build fight); falling out of the box loses. Use a knockback-only kit such as Spleef's.

### Tournaments

A tournament or sumo event pairs its players at random for 1v1 fights; the winner of each goes through
to the next round, an odd player out goes through without a fight, and the last player left is the
champion. A tournament runs each round's fights at once, as arenas are free; sumo runs one at a time, and
the other players watch it (with Spectators on). Players between fights may `/duel spectate` its fights
but cannot queue or duel, and `/event leave` takes them out. Each fight is a duel to its fighters: its
result is not broadcast and pays nothing. A draw or a fight cancelled before it started is fought again
(`events.tournament-replays` times, 1 by default); after that, one of the two goes through at random. The
champion is announced like an event's result and gets `events.reward` if they won the final by a lethal hit.
A round waits 3 seconds after the last.

In double elimination a loss only puts a player out the second time. Each round pairs players with as many
losses together (all unbeaten players, then those with one loss), crossing over when a group is odd, and the
odd player out is one with the most losses. A final where the unbeaten player loses is played again, as both
then have one loss.

### Scheduled events

`events.schedule` lists events the server hosts every day at a server time, e.g.
`- {at: "20:00", kit: sumo, mode: sumo}` (mode `ffa`, `teams`, `tournament`, `sumo` or `double`). They gather
players like a player's event, with messages.yml `event.server-host` as the host (`/event join Server`),
and start when the wait ends or they are full. One that is still gathering players skips the next; a kit
without items or an arena is logged and skipped.

## Golden heads

The UHC healing item, with the usual golden head skin: the default UHC and BuildUHC kits hold 3, `/duels kit goldenhead
[amount]` gives you some (1 to 64) to put in a kit's items before `/duels kit save <kit>`, and the kit editor offers them
in the Food category (menus.yml `kit-editor-categories` entry `GOLDEN_HEAD`; add it to a `menus.yml` from an older
version), so custom kits may hold them. A fighter right-clicks one to eat it: Regeneration II for 10 seconds (twice a
golden apple's healing) and Absorption I for 2 minutes. They are never placed during a fight; outside fights they are
ordinary heads. Heads given before the skin was added keep a plain head's look: give new ones and save the kit again.

## For developers

Other plugins can listen to these events (package `me.angylo.elotecraftDuels.api`, main thread):

- `MatchStartEvent`: a fight's first countdown ended (duels, party fights, event fights): teams, kit, arena, type, ranked.
- `MatchEndEvent`: a fight ended with a result or a draw, after stats, ratings and rewards, before the fighters are
  sent back: the same plus winners, reason and fight time. Fights cancelled without a result do not fire it.
- `QueueJoinEvent` (cancellable): a player is about to join a queue, after every check passed. Tell them why when
  you cancel it.

Compile against `elotecraft-duels` with `provided` scope and add `depend: [ElotecraftDuels]` to your `plugin.yml`.

## Placeholders

With PlaceholderAPI: `%duels_wins%`, `%duels_losses%`, `%duels_win_streak%`, `%duels_best_win_streak%`,
`%duels_win_rate%`, `%duels_kills%`, `%duels_deaths%`, `%duels_xp%`, `%duels_level%`, `%duels_elo%` (overall), `%duels_elo_<kit>%`, `%duels_division%`, `%duels_division_<kit>%`, `%duels_peak%`, `%duels_peak_<kit>%`, `%duels_season%`, `%duels_season_name%` (or the number without one), `%duels_season_days%`, `%duels_season_started%` (yyyy-MM-dd), `%duels_season_ends_in%` (empty without a planned end), `%duels_in_match%`, `%duels_opponent%`, `%duels_kit%`, `%duels_arena%`,
`%duels_queue%`, `%duels_queue_type%` (`ranked` or `unranked`), `%duels_party_size%`, `%duels_party_leader%`, `%duels_active_matches%`. Stats placeholders are for online players.

## Testing on a server

The automated tests run on MockBukkit, which cannot click menus. Before a release, check on a real server:

- [ ] Challenge through the kit and arena menus, accept by clicking in chat
- [ ] Countdown freeze, fight, lethal hit, result, everyone back with their own items
- [ ] Quit mid-fight, `/duel leave`, a timeout draw, `/duels stop`
- [ ] Queue pairing and spectating; spectators cannot fly out of the arena
- [ ] Ranked: rating change shown after a `/duel ranked` duel and not after a `/duel queue` one, `/duel top elo [kit]`, a first ranked duel in a kit starts from the old rating, a division change is announced
- [ ] `/stop` during a duel, then join again: items and position restored
- [ ] Ender pearl inside the arena works, out of it is blocked; a lit nether portal in the arena takes nobody out
- [ ] With MySQL: a duel's result appears in `/duel top`
- [ ] Build kit: place, break your own blocks, bucket water and lava, flint and steel, TNT; the arena is
  back after the duel and nothing flowed or burned outside the box
- [ ] `/duels arena snapshot`, break the arena by hand, `/duels arena reset`; `/stop` mid build duel, start
  again: the console says the arena was rebuilt
- [ ] The arenas world is created empty; with one snapshotted arena, several duels at once get copies of it;
  2 minutes after they end, the copies are cleared; `/stop` mid duel on a copy, start again: the copy is cleared
- [ ] With FAWE: copies keep a chest's contents and a sign's text with no lag spike; `/duels arena setbox` from
  a `//wand` selection; `/duels arena import` of a `.schem`. Plain WorldEdit too
- [ ] A kit limited to a category only gets those arenas; the build limit stops towering; falling off the
  bottom of the arena loses the duel
- [ ] Parties: invite by clicking [ACCEPT] in chat, `/party split` (move heads in the team menu, Start),
  `/party ffa` with 3+ players on extra spawns and without them, `/party duel` between two parties;
  knocked-out fighters watch until one side is left, teammates can't hurt each other, everyone gets their
  own items back; the leader quitting mid-fight hands the party over
- [ ] `/duel editkit`: swap two items, close the menu, the next duel uses the layout; your own inventory never
  changes
- [ ] Bridge (`smoke.js bridgeExtras`): JUMP over the goals, red and blue armor and terracotta, a golden apple
  heals fully, an end portal goal scores once with no message, the scorer watches from the middle until the next round
- [ ] Kit modes in an arena with goals and beds: a bridge goal wins the round and placed blocks stay, `/kill`
  brings a fighter back at their spawn with the kit; in a bed fight your own bed can't be broken, the enemy's
  can (both halves come back after the duel), and that side is out the next time
- [ ] Custom kits (`custom-kits.base-kit` set): `/duel customkit`, build kit 1 in the editor (every category,
  the potion form buttons, enchant a sword, change a count, anvils for durability, counts and the name, map and
  rules), close it; `/duel <player> custom:1` gives both fighters those items in the picked arena; `... bet 100` is
  refused; a horse egg spawns a horse with its armor, gone after the duel
- [ ] Kit rules on a real client: `hit-delay false` combos, `pearl-cooldown 15` shows the cooldown on the
  pearl, `natural-regeneration false` stops healing on a full hunger bar, `crafting false` blocks the 2x2 grid
- [ ] `/duel`: every hub button and back button, the profile head and stats, `/duel ratings`; menus look centered
  with no filler on a real client
- [ ] `/duels arena` and `/duels kit` menus and their submenus: every button, chat prompts for names and numbers,
  the confirm menu on delete, reset and clearing
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
  halves hits, `saturation` heals fast, `auto-ignite-tnt` lights TNT on placing; `/duels kit effect <kit> speed 1 10` shows
  the effect icon for 10 seconds from the fight start; the kit menu's effect anvils take typed numbers
- [ ] Parties: `/party` menu with and without a party, every button; `/party public` announces once, another player
  joins with the [JOIN] click; `/party chat` and `/pc` reach members only; `/pc` alone, then typing in chat, reaches
  members only until `/pc` again
- [ ] Ranked: `/duel stats` shows the peak; with `ranked.daily-limit: 1` a second ranked queue is refused and an unranked
  one is not; `/duels season end` warns, `confirm` archives and resets, a division's `season-reward` command runs and
  `/duel top season 1` shows the old ratings; the same with MySQL
- [ ] `/duels` admin menu and its season menu; `/duels season schedule 1` with `auto on` warns and ends by itself
  (shorten with a date), `auto off` only tells admins; `/duels elo set|add|reset` on an online and an offline
  player; menu titles are plain text
- [ ] Events: `/event host`, [JOIN] in chat and the `/event` list, every event settings and access button, Start in
  team mode opens the team menu, a free for all with 2 winners, a private event refusing an uninvited
  player, spectators refused when off, the border closing in and hurting fighters outside it, potions
  blocked by an event rule, the reward command once per winner, the host quitting cancels
- [ ] Tournaments: 4 players in tournament mode with 2 arenas fight round 1 at once, the final after it and one
  reward; sumo with the others spectating; 3 players with a bye; a quit mid-fight; an `events.schedule` entry
  a minute ahead hosting a Server event; double elimination with 2 players: the first loser wins the second
  fight and the third decides it
- [ ] Bets (with an economy plugin): a challenge with `bet 100` shows the pot, accepting takes both stakes, the
  winner gets the pot less `bets.tax`; `/duels stop` gives both back; a server stop mid-duel gives both back on the
  next start; a stake above `bets.max` or more than you have is refused

## Not included

Own-inventory duels. Arenas cannot span worlds.
