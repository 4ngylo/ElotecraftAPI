package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaInstance;
import me.angylo.elotecraftDuels.arena.ArenaInstances;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match.EndReason;
import me.angylo.elotecraftDuels.match.Match.State;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final long MILLIS_PER_TICK = 50;

    /** The last opponent and setup of a player, for {@code /duel rematch}. */
    public record Rematch(UUID opponent, String opponentName, String kit, String arena, long expiresAtTick) {

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
    private final StatsService stats;
    private final Rewards rewards;
    private final MatchDisplay display;
    /** Fighters and spectators; read by placeholders from other threads. */
    private final Map<UUID, Match> byPlayer = new ConcurrentHashMap<>();
    private final Set<Match> running = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Rematch> rematches = new HashMap<>();

    public MatchManager(Plugin plugin, Messages messages, Supplier<Settings> settings, ArenaRegistry arenas,
                        ArenaInstances instances, SnapshotStore snapshots, StatsService stats) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messages = messages;
        this.settings = settings;
        this.arenas = arenas;
        this.instances = instances;
        this.snapshots = snapshots;
        this.stats = stats;
        this.rewards = new Rewards(plugin, messages, settings);
        this.display = new MatchDisplay(messages, settings);
    }

    /** Whether {@code player} is fighting or spectating. */
    public boolean isBusy(Player player) {
        return byPlayer.containsKey(player.getUniqueId());
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

    /** Players fighting with {@code kit} right now. */
    public int fightingWith(String kit) {
        return 2 * (int) running.stream().filter(match -> match.kit().name().equals(kit)).count();
    }

    /** Whether a duel can start in {@code arena} now: it is ready and free, or has a copy to spare. */
    public boolean isArenaFree(Arena arena) {
        return instances.available(arena);
    }

    /** Whether any duel uses {@code arena}, or it is still being put back after one. */
    public boolean isArenaInUse(String arena) {
        return instances.inUse(arena) > 0;
    }

    /** A random arena that is ready and free. */
    public Optional<Arena> randomFreeArena() {
        List<Arena> free = arenas.all().stream().filter(instances::available).toList();
        return free.isEmpty() ? Optional.empty() : Optional.of(free.get(ThreadLocalRandom.current().nextInt(free.size())));
    }

    /** {@code player}'s last opponent, while the rematch window is open. */
    public Optional<Rematch> rematchOf(Player player) {
        Rematch rematch = rematches.get(player.getUniqueId());
        if (rematch == null || rematch.expired()) {
            rematches.remove(player.getUniqueId());
            return Optional.empty();
        }
        return Optional.of(rematch);
    }

    public void purgeExpiredRematches() {
        rematches.values().removeIf(Rematch::expired);
    }

    /**
     * Starts a duel. Their state is saved first; nothing about them changes unless that succeeds.
     *
     * @return false if either player is busy or the arena is not ready and free; callers check and explain first
     */
    public boolean start(Player first, Player second, Kit kit, Arena arena) {
        if (first.equals(second) || !available(first) || !available(second) || !instances.available(arena)) {
            return false;
        }
        Map<UUID, PlayerSnapshot> taken = new HashMap<>();
        for (Player fighter : List.of(first, second)) {
            // Closed before the snapshot, while drops are still allowed: a held cursor item goes back first.
            fighter.closeInventory();
            taken.put(fighter.getUniqueId(), PlayerSnapshot.capture(fighter));
        }
        // Reserved only now: nothing above may leave the arena reserved if it throws.
        ArenaInstance instance = instances.acquire(arena, kit.build()).orElse(null);
        if (instance == null) {
            messages.send(first, "match.arena-failed");
            messages.send(second, "match.arena-failed");
            return true;
        }
        Match match = new Match(instance, kit, first, second);
        for (Player fighter : List.of(first, second)) {
            match.addSnapshot(fighter, taken.get(fighter.getUniqueId()));
        }
        // Saved before anyone is registered: if serializing throws, nobody is left half in a duel.
        CompletableFuture<Void> saved;
        try {
            saved = snapshots.save(taken);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not save " + first.getName() + " and " + second.getName() + " before their duel", e);
            messages.send(first, "general.storage-error");
            messages.send(second, "general.storage-error");
            instances.release(instance);
            return true;
        }
        for (Player fighter : List.of(first, second)) {
            byPlayer.put(fighter.getUniqueId(), match);
        }
        running.add(match);
        display.starting(match);
        saved.whenComplete((ignored, error) -> guarded(match, () -> {
            if (match.isOver()) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Could not save " + first.getName() + " and " + second.getName()
                        + " before their duel, so it was cancelled", error);
                cancel(match, "general.storage-error");
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

    /** A lethal hit on a fighter: the opponent wins. */
    public void eliminate(Player loser) {
        Match match = byPlayer.get(loser.getUniqueId());
        if (match != null && match.isFighting(loser)) {
            end(match, match.opponentOf(loser), EndReason.ELIMINATED);
        }
    }

    /** A death that got past {@link #eliminate} (e.g. {@code /kill}). */
    public void handleDeath(Player player) {
        Match match = byPlayer.get(player.getUniqueId());
        if (match == null) {
            return;
        }
        if (match.isFighting(player)) {
            end(match, match.opponentOf(player), EndReason.ELIMINATED);
        } else if (match.isFighter(player) && match.state() != State.ENDING) {
            cancel(match, "match.cancelled");
        }
    }

    /** One tick after a player in a match respawned at its spectator spawn. */
    public void respawned(Player player) {
        Match match = byPlayer.get(player.getUniqueId());
        if (match != null && (match.isSpectator(player) || match.state() == State.ENDING)) {
            player.setGameMode(GameMode.SPECTATOR);
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
        release(match, player, true);
        if (!fighter) {
            return;
        }
        switch (state) {
            case STARTING, COUNTDOWN -> cancel(match, "match.cancelled-quit", Placeholder.unparsed("player", player.getName()));
            case FIGHTING -> end(match, match.opponentOf(player), EndReason.QUIT);
            case ENDING -> { }
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
        switch (match.state()) {
            case STARTING, COUNTDOWN -> cancel(match, "match.cancelled");
            case FIGHTING -> end(match, match.opponentOf(player), EndReason.FORFEIT);
            case ENDING -> release(match, player, false);
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
                if (match.bossBar() != null) {
                    spectator.showBossBar(match.bossBar());
                }
                messages.send(spectator, "spectate.started", Placeholder.unparsed("player", match.first().getName()));
                for (Player fighter : List.of(match.first(), match.second())) {
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
            display.removeBossBar(match);
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
        rematches.clear();
    }

    private void teleportFighters(Match match) {
        if (!match.arena().isReady()) {
            cancel(match, "match.teleport-failed");
            return;
        }
        CompletableFuture<Boolean> first = teleportIn(match, match.first(), match.instance().spawn(1));
        CompletableFuture<Boolean> second = teleportIn(match, match.second(), match.instance().spawn(2));
        first.thenCombine(second, Boolean::logicalAnd).whenComplete((arrived, error) -> guarded(match, () -> {
            if (match.isOver()) {
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
        for (Player fighter : List.of(match.first(), match.second())) {
            PlayerSnapshot.resetForDuel(fighter);
            match.kit().apply(fighter);
        }
        match.state(State.COUNTDOWN);
        match.secondsLeft(current.countdownSeconds());
        match.maxFightSeconds((int) Math.min(Integer.MAX_VALUE, current.maxDuration().toSeconds()));
        display.countdown(match, match.secondsLeft());
        match.task(Tasks.timer(plugin, () -> tick(match), SECOND_TICKS, SECOND_TICKS));
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
                    display.fightStarted(match);
                }
            }
            case FIGHTING -> {
                match.fightSeconds(match.fightSeconds() + 1);
                int left = match.maxFightSeconds() - match.fightSeconds();
                if (left <= 0) {
                    end(match, null, EndReason.TIMEOUT);
                } else {
                    display.timeLeft(match, left);
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

    /** @param winner null for a draw */
    private void end(Match match, Player winner, EndReason reason) {
        if (match.isOver() || match.state() == State.ENDING) {
            return;
        }
        match.state(State.ENDING);
        display.removeBossBar(match);
        Player loser = winner == null ? null : match.opponentOf(winner);
        if (winner != null) {
            stats.recordResult(winner, loser);
            // Only a real fight pays out, so two accounts cannot farm rewards by forfeiting to each other.
            if (reason == EndReason.ELIMINATED) {
                rewards.give(winner, loser, match);
            }
            display.result(match, winner, loser, reason);
            if (match.isParticipant(loser) && !loser.isDead()) {
                loser.setGameMode(GameMode.SPECTATOR);
            }
        } else {
            display.draw(match);
        }
        offerRematch(match);
        logResult(match, winner, loser, reason);
        match.secondsLeft(settings.get().endDelaySeconds());
        if (match.secondsLeft() <= 0) {
            finish(match);
        }
    }

    private void offerRematch(Match match) {
        Player first = match.first();
        Player second = match.second();
        if (!match.isParticipant(first) || !match.isParticipant(second)) {
            return;
        }
        long expiresAt = Bukkit.getCurrentTick() + settings.get().rematchWindow().toMillis() / MILLIS_PER_TICK;
        for (Player player : List.of(first, second)) {
            Player opponent = match.opponentOf(player);
            rematches.put(player.getUniqueId(), new Rematch(opponent.getUniqueId(), opponent.getName(),
                    match.kit().name(), match.arena().name(), expiresAt));
            display.rematchOffer(player, opponent);
        }
    }

    private void logResult(Match match, Player winner, Player loser, EndReason reason) {
        if (!settings.get().logResults()) {
            return;
        }
        String details = " (" + match.kit().name() + ", " + match.arena().name() + ", "
                + Durations.format(Duration.ofSeconds(match.fightSeconds())) + ")";
        if (winner == null) {
            logger.info("Duel " + match.first().getName() + " vs " + match.second().getName() + " was a draw" + details);
        } else {
            logger.info(winner.getName() + " beat " + loser.getName() + " by " + reason.name().toLowerCase(Locale.ROOT) + details);
        }
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
        display.removeBossBar(match);
        for (Player spectator : match.spectators()) {
            messages.send(spectator, "spectate.ended");
        }
        for (Player participant : match.participants()) {
            release(match, participant, false);
        }
        running.remove(match);
        instances.release(match.instance());
    }

    /** Takes {@code player} out of {@code match} and restores their snapshot. */
    private void release(Match match, Player player, boolean teleportNow) {
        byPlayer.remove(player.getUniqueId(), match);
        if (match.bossBar() != null) {
            player.hideBossBar(match.bossBar());
        }
        match.release(player).ifPresent(snapshot -> snapshots.restore(player, snapshot, teleportNow));
    }
}
