package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaInstance;
import me.angylo.elotecraftDuels.arena.ArenaInstances;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitLayouts;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.kit.TeamColors;
import me.angylo.elotecraftDuels.match.Match.EndReason;
import me.angylo.elotecraftDuels.match.Match.State;
import me.angylo.elotecraftDuels.match.Match.Type;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import me.angylo.elotecraftDuels.stats.MatchHistory;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs every match: saves the players' state, teleports them in, counts down, tracks the fight, shows
 * the result and puts everyone back. Every way out of a match (win, draw, quit, forfeit, admin stop,
 * shutdown) ends in {@link #release}, which restores the player from their saved snapshot.
 * Main thread only, except {@link #matchOf(UUID)} and {@link #activeMatches()}.
 */
public final class MatchManager {

    private static final long SECOND_TICKS = 20;
    /** Hit delay of combo kits (vanilla 20): hits land almost every tick. Reset by {@link PlayerSnapshot}. */
    private static final int COMBO_NO_DAMAGE_TICKS = 2;

    /** The last opponent and setup of a player, for {@code /duel rematch}. */
    public record Rematch(UUID opponent, String opponentName, Kit kit, String arena, long expiresAtTick) {

        boolean expired() {
            return Bukkit.getCurrentTick() >= expiresAtTick;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final ArenaInstances instances;
    private final SnapshotStore snapshots;
    private final KitLayouts layouts;
    /** Players busy outside matches under match rules, such as in the kit editor. */
    private Predicate<Player> busyElsewhere = player -> false;
    /** Players waiting for something else, such as an event: busy, but free to do anything else. */
    private Predicate<Player> waitingElsewhere = player -> false;
    /** Told about every match once it is over and everyone was sent back, such as a tournament's fights. */
    private final List<Consumer<Match>> finished = new ArrayList<>();
    private final MatchDisplay display;
    /** Fighters and spectators; read by placeholders from other threads. */
    private final Map<UUID, Match> byPlayer = new ConcurrentHashMap<>();
    private final Set<Match> running = ConcurrentHashMap.newKeySet();
    /** Stats, ratings, rewards, rematches and kept inventories of fights with a result. */
    private final MatchResults outcomes;

    public MatchManager(Plugin plugin, Messages messages, Supplier<Settings> settings, ArenaRegistry arenas,
                        ArenaInstances instances, SnapshotStore snapshots, StatsService stats, MatchHistory history,
                        KitLayouts layouts) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messages = messages;
        this.settings = settings;
        this.arenas = arenas;
        this.instances = instances;
        this.snapshots = snapshots;
        this.layouts = layouts;
        this.display = new MatchDisplay(messages, settings);
        this.outcomes = new MatchResults(logger, settings, stats, history, new Rewards(plugin, messages, settings), display);
    }

    /** Whether {@code player} is fighting, spectating, editing a kit or waiting for an event. */
    public boolean isBusy(Player player) {
        return isRestricted(player) || waitingElsewhere.test(player);
    }

    /**
     * Whether match rules apply to {@code player}: fighting, spectating or editing a kit. No commands,
     * other menus, drops or block use.
     */
    public boolean isRestricted(Player player) {
        return byPlayer.containsKey(player.getUniqueId()) || busyElsewhere.test(player);
    }

    /** Players {@code busy} names are busy and restricted too: they cannot be queued, challenged or spectate. */
    public void busyElsewhere(Predicate<Player> busy) {
        this.busyElsewhere = busy;
    }

    /** Players {@code waiting} names are busy, but not {@link #isRestricted restricted}. */
    public void waitingElsewhere(Predicate<Player> waiting) {
        this.waitingElsewhere = waiting;
    }

    /** {@code listener} hears of every match once it is over, with or without a result; not on shutdown. */
    public void onFinish(Consumer<Match> listener) {
        finished.add(listener);
    }

    public Optional<Match> matchOf(Player player) {
        return matchOf(player.getUniqueId());
    }

    /** Safe from any thread. */
    public Optional<Match> matchOf(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    /** Safe from any thread. */
    public int activeMatches() {
        return running.size();
    }

    /** Every match that has not finished yet. */
    public List<Match> running() {
        return List.copyOf(running);
    }

    /** Players fighting with {@code kit} right now. */
    public int fightingWith(String kit) {
        return 2 * (int) running.stream().filter(match -> match.kit().name().equals(kit)).count();
    }

    /** Whether a duel can start in {@code arena} now: it is ready and free, or a copy of it can be had. */
    public boolean isArenaFree(Arena arena) {
        return instances.available(arena);
    }

    /** Whether any duel uses {@code arena} or a copy of it, or one is still being put back after a duel. */
    public boolean isArenaInUse(String arena) {
        return instances.inUse(arena) > 0;
    }

    /** A random arena {@code kit} accepts that is ready and free. */
    public Optional<Arena> randomFreeArena(Kit kit) {
        List<Arena> free = arenas.all().stream().filter(arena -> kit.accepts(arena) && instances.available(arena)).toList();
        return free.isEmpty() ? Optional.empty() : Optional.of(free.get(ThreadLocalRandom.current().nextInt(free.size())));
    }

    /** Whether any ready arena accepts {@code kit}, free or not; without one its duels could never start. */
    public boolean hasArenaFor(Kit kit) {
        return arenas.all().stream().anyMatch(arena -> kit.accepts(arena) && arena.isReady());
    }

    /** {@code player}'s last opponent, while the rematch window is open. */
    public Optional<Rematch> rematchOf(Player player) {
        return outcomes.rematchOf(player);
    }

    /** Drops rematch offers and fight inventories whose time is up. */
    public void purgeExpired() {
        outcomes.purgeExpired();
    }

    /** A fighter of a fight that ended in the last few minutes, by the id in its result message. */
    public Optional<FighterResult> fightResult(String id, String name) {
        return outcomes.fightResult(id, name);
    }

    /** Starts an unranked duel; see {@link #start(Player, Player, Kit, Arena, boolean)}. */
    public boolean start(Player first, Player second, Kit kit, Arena arena) {
        return start(first, second, kit, arena, false);
    }

    /**
     * Starts a duel; see {@link #start(List, Kit, Arena, Type, boolean)}.
     *
     * @param ranked whether the result moves the fighters' Elo ratings
     */
    public boolean start(Player first, Player second, Kit kit, Arena arena, boolean ranked) {
        return start(List.of(List.of(first), List.of(second)), kit, arena, Type.DUEL, ranked);
    }

    /**
     * Starts a fight between teams. Everyone's state is saved first; nothing about them changes unless that
     * succeeds.
     *
     * @param teams  the fighters of each team: at least two teams, nobody twice
     * @param ranked whether the result moves Elo ratings; duels only
     * @return false if anyone is busy or the arena is not ready and free; callers check and explain first
     */
    public boolean start(List<List<Player>> teams, Kit kit, Arena arena, Type type, boolean ranked) {
        return start(teams, kit, arena, type, ranked, Match.Options.DEFAULT);
    }

    /** Like {@link #start(List, Kit, Arena, Type, boolean)}, with how the fight ends and who may watch it. */
    public boolean start(List<List<Player>> teams, Kit kit, Arena arena, Type type, boolean ranked, Match.Options options) {
        List<Player> fighters = teams.stream().flatMap(List::stream).toList();
        if (teams.size() < 2 || teams.stream().anyMatch(List::isEmpty) || Set.copyOf(fighters).size() != fighters.size()
                || !fighters.stream().allMatch(this::available) || !instances.available(arena)) {
            return false;
        }
        String names = names(fighters, " and ");
        Map<UUID, PlayerSnapshot> taken = new HashMap<>();
        for (Player fighter : fighters) {
            // Closed before the snapshot, while drops are still allowed: a held cursor item goes back first.
            fighter.closeInventory();
            taken.put(fighter.getUniqueId(), PlayerSnapshot.capture(fighter));
        }
        // Reserved only now: nothing above may leave the arena reserved if it throws.
        Settings current = settings.get();
        ArenaInstance instance = instances.acquire(arena, kit.flag(KitRule.BUILD, current)).orElse(null);
        if (instance == null) {
            fighters.forEach(fighter -> messages.send(fighter, "match.arena-failed"));
            return true;
        }
        Match match = new Match(instance, kit, teams, type, ranked && type == Type.DUEL, options,
                kit.number(KitRule.ROUNDS_TO_WIN, current).orElse(1));
        for (Player fighter : fighters) {
            match.addSnapshot(fighter, taken.get(fighter.getUniqueId()));
        }
        // Saved before anyone is registered: if serializing throws, nobody is left half in a duel.
        CompletableFuture<Void> saved;
        try {
            saved = snapshots.save(taken);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not save " + names + " before their duel", e);
            fighters.forEach(fighter -> messages.send(fighter, "general.storage-error"));
            instances.release(instance);
            return true;
        }
        for (Player fighter : fighters) {
            byPlayer.put(fighter.getUniqueId(), match);
        }
        running.add(match);
        display.starting(match);
        if (!instance.ready().isDone()) {
            fighters.forEach(fighter -> messages.send(fighter, "match.preparing-arena"));
        }
        // A copy of the arena may still be pasting; the fighters wait for both.
        CompletableFuture.allOf(saved, instance.ready()).whenComplete((ignored, error) -> guarded(match, () -> {
            if (match.isOver()) {
                return;
            }
            if (saved.isCompletedExceptionally()) {
                logger.log(Level.SEVERE, "Could not save " + names + " before their duel, so it was cancelled", error);
                cancel(match, "general.storage-error");
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Could not prepare a copy of arena " + arena.name() + " for " + names, error);
                cancel(match, "match.arena-failed");
                return;
            }
            teleportFighters(match);
        }));
        return true;
    }

    /** Online, alive and not already fighting or spectating. */
    public boolean available(Player player) {
        return player.isOnline() && !player.isDead() && !isBusy(player);
    }

    /** A lethal hit on a fighter: they are out, and the last team with a fighter left wins. */
    public void eliminate(Player loser) {
        Match match = byPlayer.get(loser.getUniqueId());
        if (match != null && match.isFighting(loser)) {
            knockOut(match, loser, EndReason.ELIMINATED);
        }
    }

    /** A death that got past {@link #eliminate} (e.g. {@code /kill}). */
    public void handleDeath(Player player) {
        Match match = byPlayer.get(player.getUniqueId());
        if (match == null) {
            return;
        }
        if (match.isFighting(player)) {
            knockOut(match, player, EndReason.ELIMINATED);
        } else if (match.isFighter(player) && (match.state() == State.STARTING || match.state() == State.COUNTDOWN)) {
            cancel(match, "match.cancelled");
        }
    }

    /** One tick after a player in a match respawned: at its spectator spawn, or a fighter still in it at their spawn. */
    public void respawned(Player player) {
        Match match = byPlayer.get(player.getUniqueId());
        if (match != null && (match.isSpectator(player) || match.state() == State.ENDING
                || (match.isFighter(player) && !match.isAlive(player)))) {
            player.setGameMode(GameMode.SPECTATOR);
        } else if (match != null && match.isFighting(player)) {
            // A bridge or bed fight fighter who really died is back at their spawn.
            equip(match, player, settings.get());
        }
    }

    /** Restores a quitting player at once; a fighter who quits mid-fight loses. */
    public void handleQuit(Player player) {
        Match match = byPlayer.get(player.getUniqueId());
        if (match == null) {
            return;
        }
        State state = match.state();
        boolean fighter = match.isFighter(player);
        boolean betweenRounds = match.betweenRounds();
        release(match, player, true);
        if (!fighter) {
            return;
        }
        if (betweenRounds) {
            leftBetweenRounds(match, player, EndReason.QUIT);
            return;
        }
        switch (state) {
            case STARTING, COUNTDOWN -> cancel(match, "match.cancelled-quit", Placeholder.unparsed("player", player.getName()));
            case FIGHTING -> knockOut(match, player, EndReason.QUIT);
            case ROUND_OVER, ENDING -> { }
        }
    }

    /**
     * {@code /duel leave}: a spectator stops watching, a fighter forfeits (or cancels before the fight).
     *
     * @return false if {@code player} is not in a match
     */
    public boolean leave(Player player) {
        Match match = byPlayer.get(player.getUniqueId());
        if (match == null) {
            return false;
        }
        if (match.isSpectator(player)) {
            release(match, player, false);
            messages.send(player, "spectate.stopped");
            return true;
        }
        if (match.betweenRounds()) {
            leftBetweenRounds(match, player, EndReason.FORFEIT);
            return true;
        }
        switch (match.state()) {
            case STARTING, COUNTDOWN -> cancel(match, "match.cancelled");
            // A fighter already out of a team fight just goes home.
            case FIGHTING -> {
                if (match.isAlive(player)) {
                    knockOut(match, player, EndReason.FORFEIT);
                } else {
                    release(match, player, false);
                }
            }
            case ROUND_OVER, ENDING -> release(match, player, false);
        }
        return true;
    }

    /**
     * Admin stop: the match ends without a result.
     *
     * @return false if {@code player} is not fighting
     */
    public boolean stop(Player player) {
        Match match = byPlayer.get(player.getUniqueId());
        if (match == null || !match.isFighter(player)) {
            return false;
        }
        cancel(match, "match.cancelled-admin");
        return true;
    }

    /**
     * Makes {@code spectator} watch {@code match}. Callers check that they are free and that the match is
     * running.
     */
    public void spectate(Player spectator, Match match) {
        spectator.closeInventory();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(spectator);
        CompletableFuture<Void> saved;
        try {
            saved = snapshots.save(Map.of(spectator.getUniqueId(), snapshot));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not save " + spectator.getName() + " before spectating", e);
            messages.send(spectator, "general.storage-error");
            return;
        }
        match.addSpectator(spectator, snapshot);
        byPlayer.put(spectator.getUniqueId(), match);
        saved.whenComplete((ignored, error) -> guardedSpectator(match, spectator, () -> {
            if (match.isOver() || !match.isSpectator(spectator)) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Could not save " + spectator.getName() + " before spectating", error);
                release(match, spectator, false);
                messages.send(spectator, "general.storage-error");
                return;
            }
            teleportIn(match, spectator, match.spectatorSpawn()).whenComplete((arrived, teleportError) -> guardedSpectator(match, spectator, () -> {
                if (match.isOver() || !match.isSpectator(spectator)) {
                    return;
                }
                if (teleportError != null || !arrived) {
                    release(match, spectator, false);
                    messages.send(spectator, "spectate.failed");
                    return;
                }
                spectator.setGameMode(GameMode.SPECTATOR);
                messages.send(spectator, "spectate.started", Placeholder.unparsed("player", match.first().getName()));
                for (Player fighter : match.fighters()) {
                    if (match.isParticipant(fighter)) {
                        messages.send(fighter, "spectate.joined", Placeholder.unparsed("player", spectator.getName()));
                    }
                }
            }));
        }));
    }

    /** Like {@link #guarded}, for a spectator joining: only they are sent back. */
    private void guardedSpectator(Match match, Player spectator, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, spectator.getName() + " could not start spectating", e);
            release(match, spectator, false);
            messages.send(spectator, "spectate.failed");
        }
    }

    /**
     * Ends every match without a result and restores everyone right away. For {@code onDisable}, before
     * {@link ArenaInstances#shutdown()} puts the arenas back.
     */
    public void shutdown() {
        for (Match match : List.copyOf(running)) {
            match.markOver();
            if (match.task() != null) {
                match.task().cancel();
            }
            for (Player participant : match.participants()) {
                messages.send(participant, "match.cancelled");
                // No respawn listener after disable: bring a dead player back now so they can be restored.
                if (participant.isDead()) {
                    participant.spigot().respawn();
                }
                release(match, participant, true);
            }
        }
        byPlayer.clear();
        running.clear();
        outcomes.clear();
    }

    private void teleportFighters(Match match) {
        if (!match.arena().isReady()) {
            cancel(match, "match.teleport-failed");
            return;
        }
        List<CompletableFuture<Boolean>> teleports = match.fighters().stream()
                .map(fighter -> teleportIn(match, fighter, match.spawnOf(fighter))).toList();
        CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> teleports.stream().allMatch(CompletableFuture::join))
                .whenComplete((arrived, error) -> guarded(match, () -> {
            // Over, or a fighter left between rounds.
            if (match.isOver() || match.state() == State.ENDING) {
                return;
            }
            if (error != null || !arrived) {
                if (error != null) {
                    logger.log(Level.WARNING, "Could not teleport a duel into arena " + match.arena().name(), error);
                }
                cancel(match, "match.teleport-failed");
                return;
            }
            beginCountdown(match);
        }));
    }

    /**
     * Runs a step that continues a match after the database or a teleport. An exception there would
     * otherwise vanish inside the future and leave the match stuck, so it is logged and the match cancelled.
     */
    private void guarded(Match match, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "A duel in arena " + match.arena().name() + " failed and was cancelled", e);
            cancel(match, "match.cancelled");
        }
    }

    /**
     * Teleports a participant in. One who was released while the teleport was on its way is sent back
     * again, so a late teleport can never strand anyone in the arena.
     */
    private CompletableFuture<Boolean> teleportIn(Match match, Player player, Location destination) {
        CompletableFuture<Boolean> teleport;
        try {
            teleport = player.teleportAsync(destination, TeleportCause.PLUGIN);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return teleport.thenApply(arrived -> {
            if (!match.isParticipant(player) || match.isOver()) {
                if (arrived && player.isOnline()) {
                    player.teleportAsync(match.returnLocation(player), TeleportCause.PLUGIN);
                }
                return false;
            }
            if (arrived) {
                match.arrive(player);
            }
            return arrived;
        });
    }

    private void beginCountdown(Match match) {
        Settings current = settings.get();
        if (match.mode() == Kit.Mode.BRIDGE && current.modes().goalHologram()) {
            GoalHolograms.show(plugin, match, messages.get("match.goal-hologram"));
        }
        for (Player fighter : match.fighters()) {
            equip(match, fighter, current);
        }
        match.state(State.COUNTDOWN);
        match.secondsLeft(current.countdownSeconds());
        match.maxFightSeconds((int) Math.min(Integer.MAX_VALUE, current.maxDuration().toSeconds()));
        display.countdown(match, match.secondsLeft());
        // Later rounds keep the timer of the first.
        if (match.task() == null) {
            match.task(Tasks.timer(plugin, () -> tick(match), SECOND_TICKS, SECOND_TICKS));
        }
    }

    /** Heals {@code fighter} and gives them the kit, its rules and its effects. */
    private void equip(Match match, Player fighter, Settings current) {
        PlayerSnapshot.resetForDuel(fighter);
        if (!match.kit().flag(KitRule.HIT_DELAY, current)) {
            fighter.setMaximumNoDamageTicks(COMBO_NO_DAMAGE_TICKS);
        }
        layouts.apply(fighter, match.kit());
        if (match.mode() != Kit.Mode.NORMAL) {
            TeamColors.apply(fighter.getInventory(), match.teamOf(fighter.getUniqueId()));
        }
        match.kit().applyStatus(fighter, current);
    }

    private void tick(Match match) {
        switch (match.state()) {
            case COUNTDOWN -> {
                match.secondsLeft(match.secondsLeft() - 1);
                if (match.secondsLeft() > 0) {
                    display.countdown(match, match.secondsLeft());
                } else {
                    match.state(State.FIGHTING);
                    match.fightSeconds(0);
                    match.fighters().stream().filter(match::isFighting).forEach(match.kit()::applyTimedEffects);
                    display.fightStarted(match);
                    if (match.options().border()) {
                        FightBorder border = FightBorder.around(match.arena().bounds(), settings.get().events().border());
                        match.border(border);
                        match.fighters().stream().filter(match::isFighting).forEach(border::show);
                    }
                }
            }
            case FIGHTING -> {
                match.fightSeconds(match.fightSeconds() + 1);
                int left = match.maxFightSeconds() - match.fightSeconds();
                if (left <= 0) {
                    end(match, List.of(), EndReason.TIMEOUT);
                } else {
                    if (match.border() != null) {
                        match.border().tick(match, match.fightSeconds());
                    }
                }
            }
            case ROUND_OVER -> {
                // Below 0 while the arena is put back and the fighters brought in.
                match.secondsLeft(match.secondsLeft() - 1);
                if (match.secondsLeft() == 0) {
                    nextRound(match);
                }
            }
            case ENDING -> {
                match.secondsLeft(match.secondsLeft() - 1);
                if (match.secondsLeft() <= 0) {
                    finish(match);
                }
            }
            case STARTING -> { }
        }
    }

    /**
     * Takes {@code fighter} out of the fight; once only as many teams as win are left, they win. In a fight of
     * rounds a knockout wins the round, and the fight once a team has won enough of them.
     */
    private void knockOut(Match match, Player fighter, EndReason reason) {
        if (reason == EndReason.ELIMINATED && match.isFighting(fighter) && match.respawns(match.teamOf(fighter.getUniqueId()))) {
            display.knockedOut(match, fighter, killer(match, fighter), true);
            respawn(match, fighter);
            return;
        }
        match.recordFinal(fighter);
        match.knockOut(fighter);
        if (match.isParticipant(fighter) && !fighter.isDead()) {
            fighter.setGameMode(GameMode.SPECTATOR);
        }
        List<Integer> left = match.teamsLeft();
        boolean fightGoesOn = left.size() > match.options().winners();
        display.knockedOut(match, fighter, reason == EndReason.ELIMINATED ? killer(match, fighter) : null, fightGoesOn);
        if (fightGoesOn) {
            return;
        }
        if (reason == EndReason.ELIMINATED && left.size() == 1 && match.winRound(left.getFirst()) < match.roundsToWin()) {
            roundOver(match, left.getFirst());
        } else {
            end(match, left, reason);
        }
    }

    /**
     * Bridge and bed fight: a knocked-out fighter whose side still respawns is healed, given the kit again and sent
     * back to their spawn, a tick later as this may run inside a move event.
     */
    private void respawn(Match match, Player fighter) {
        display.respawned(match, fighter);
        // A real death (e.g. /kill): they respawn at their spawn, and respawned() equips them.
        if (fighter.isDead()) {
            return;
        }
        equip(match, fighter, settings.get());
        Tasks.later(plugin, () -> {
            if (match.isFighting(fighter)) {
                // Not PLUGIN: Essentials' teleport-invulnerability would stop them hitting or being hit for seconds.
                fighter.teleportAsync(match.spawnOf(fighter), TeleportCause.UNKNOWN);
            }
        }, 1);
    }

    /** Bridge: {@code scorer} walked into the other side's goal, so their side wins the round, or the fight. */
    public void score(Player scorer) {
        Match match = byPlayer.get(scorer.getUniqueId());
        if (match == null || !match.isFighting(scorer) || match.mode() != Kit.Mode.BRIDGE) {
            return;
        }
        int team = match.teamOf(scorer.getUniqueId());
        display.scored(match, scorer);
        // Watches from the middle until the next round brings everyone back, or the fight ends.
        scorer.setGameMode(GameMode.SPECTATOR);
        Tasks.later(plugin, () -> {
            if (byPlayer.get(scorer.getUniqueId()) == match) {
                scorer.teleportAsync(match.instance().middle(), TeleportCause.UNKNOWN);
            }
        }, 1);
        if (match.winRound(team) < match.roundsToWin()) {
            roundOver(match, team);
        } else {
            end(match, List.of(team), EndReason.ELIMINATED);
        }
    }

    /**
     * Bed fight: {@code breaker} breaks the bed of {@code team}. Their own side's bed is refused; an enemy bed breaks,
     * and that side's knocked-out fighters no longer come back this round.
     *
     * @return whether the bed may break
     */
    public boolean breakBed(Player breaker, int team) {
        Match match = byPlayer.get(breaker.getUniqueId());
        if (match == null || !match.isFighting(breaker) || match.mode() != Kit.Mode.BED_FIGHT || !match.hasBed(team)) {
            return false;
        }
        if (match.teamOf(breaker.getUniqueId()) == team) {
            messages.send(breaker, "match.own-bed");
            return false;
        }
        match.breakBed(team);
        display.bedBroken(match, team, breaker);
        return true;
    }

    /** The opponent who last hit {@code fighter} this round, if they are still online: they get the kill. */
    private static Player killer(Match match, Player fighter) {
        return match.fightStats().lastHitBy(fighter).map(Bukkit::getPlayer)
                .filter(killer -> match.isFighter(killer) && !match.sameTeam(killer, fighter)).orElse(null);
    }

    /** {@code team} won a round but not the fight yet: a pause, then {@link #nextRound}. */
    private void roundOver(Match match, int team) {
        match.state(State.ROUND_OVER);
        display.roundWon(match, team);
        match.secondsLeft(settings.get().roundDelaySeconds());
    }

    /** Puts the arena back, then brings the fighters in again, healed and re-kitted, for the next countdown. */
    private void nextRound(Match match) {
        // A fighter killed outright respawned a tick after: round-delay-seconds is at least 1.
        match.nextRound();
        // Bridge keeps the blocks placed so far.
        CompletableFuture<Void> reset = match.mode() == Kit.Mode.BRIDGE ? CompletableFuture.completedFuture(null)
                : instances.resetRound(match.instance());
        reset.whenComplete((ignored, error) -> guarded(match, () -> {
            if (match.isOver() || match.state() != State.ROUND_OVER) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Could not put arena " + match.arena().name() + " back between rounds", error);
                cancel(match, "match.cancelled");
                return;
            }
            teleportFighters(match);
        }));
    }

    /** A fighter quit or forfeited between rounds: the other fighter wins the duel. */
    private void leftBetweenRounds(Match match, Player fighter, EndReason reason) {
        match.recordFinal(fighter);
        int team = match.teamOf(fighter.getUniqueId());
        end(match, List.of(team == 0 ? 1 : 0), reason);
    }

    /** @param winnerTeams empty for a draw */
    private void end(Match match, List<Integer> winnerTeams, EndReason reason) {
        if (match.isOver() || match.state() == State.ENDING) {
            return;
        }
        match.state(State.ENDING);
        match.result(winnerTeams, reason);
        outcomes.record(match, winnerTeams, reason);
        match.secondsLeft(settings.get().endDelaySeconds());
        if (match.secondsLeft() <= 0) {
            finish(match);
        }
    }

    static String names(List<Player> players, String separator) {
        return String.join(separator, players.stream().map(Player::getName).toList());
    }

    /** Sends everyone a message and puts them back, without a result. */
    private void cancel(Match match, String messageKey, TagResolver... tags) {
        if (match.isOver()) {
            return;
        }
        for (Player participant : match.participants()) {
            messages.send(participant, messageKey, tags);
        }
        finish(match);
    }

    private void finish(Match match) {
        if (match.isOver()) {
            return;
        }
        match.markOver();
        if (match.task() != null) {
            match.task().cancel();
        }
        GoalHolograms.remove(match);
        for (Player spectator : match.spectators()) {
            messages.send(spectator, "spectate.ended");
        }
        List<Player> participants = match.participants();
        for (Player participant : participants) {
            release(match, participant, false);
        }
        if (match.resultsId() != null && !match.isDuel()) {
            display.inventories(participants.stream().filter(Player::isOnline).toList(), match.resultsId(),
                    outcomes.kept(match.resultsId()));
        }
        running.remove(match);
        instances.release(match.instance());
        finished.forEach(listener -> listener.accept(match));
    }

    /** Takes {@code player} out of {@code match} and restores their snapshot. */
    private void release(Match match, Player player, boolean teleportNow) {
        byPlayer.remove(player.getUniqueId(), match);
        if (match.border() != null && match.isFighter(player)) {
            FightBorder.hide(player);
        }
        match.release(player).ifPresent(snapshot -> snapshots.restore(player, snapshot, teleportNow));
    }
}
