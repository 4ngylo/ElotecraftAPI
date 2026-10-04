package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One duel between two players, with its spectators. Holds the state; {@link MatchManager} moves it
 * forward. Main thread only, except the final fields, which placeholders may read from any thread.
 */
public final class Match {

    /** Phases in order; a match only moves forward. */
    public enum State {
        /** Saving everyone's state and teleporting them in. */
        STARTING,
        /** Frozen in place while the countdown runs. */
        COUNTDOWN,
        FIGHTING,
        /** Result shown; nobody can be hurt until everyone is sent back. */
        ENDING
    }

    /** Why a match ended. */
    public enum EndReason {
        ELIMINATED, QUIT, FORFEIT, TIMEOUT
    }

    private final Arena arena;
    private final Kit kit;
    private final Player first;
    private final Player second;
    /** Everyone still to be restored, fighters and spectators, with their pre-duel state. */
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Player> spectators = new LinkedHashMap<>();
    /** Participants who reached the arena, so its bounds apply to them. */
    private final Set<UUID> arrived = new HashSet<>();
    /** Kept after release, for teleports into the arena that land after the player was sent back. */
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private State state = State.STARTING;
    private int secondsLeft;
    private int fightSeconds;
    private int maxFightSeconds;
    private BossBar bossBar;
    private BukkitTask task;
    private boolean over;

    Match(Arena arena, Kit kit, Player first, Player second) {
        this.arena = arena;
        this.kit = kit;
        this.first = first;
        this.second = second;
    }

    public Arena arena() {
        return arena;
    }

    public Kit kit() {
        return kit;
    }

    public Player first() {
        return first;
    }

    public Player second() {
        return second;
    }

    public State state() {
        return state;
    }

    public boolean isFighter(Player player) {
        return first.getUniqueId().equals(player.getUniqueId()) || second.getUniqueId().equals(player.getUniqueId());
    }

    public boolean isSpectator(Player player) {
        return spectators.containsKey(player.getUniqueId());
    }

    /** A fighter who may hit and be hit right now. */
    public boolean isFighting(Player player) {
        return state == State.FIGHTING && isFighter(player) && snapshots.containsKey(player.getUniqueId());
    }

    /** The other fighter. */
    public Player opponentOf(Player fighter) {
        return first.getUniqueId().equals(fighter.getUniqueId()) ? second : first;
    }

    /** Where fighter 1 or 2 starts. */
    public Location spawnOf(Player fighter) {
        return arena.spawn(first.getUniqueId().equals(fighter.getUniqueId()) ? 1 : 2);
    }

    /** Whether the arena's bounds and freeze apply to {@code player} yet. */
    public boolean hasArrived(Player player) {
        return arrived.contains(player.getUniqueId());
    }

    /** Fighters and spectators who are still part of this match. */
    public List<Player> participants() {
        List<Player> players = new ArrayList<>(2 + spectators.size());
        for (Player fighter : List.of(first, second)) {
            if (snapshots.containsKey(fighter.getUniqueId())) {
                players.add(fighter);
            }
        }
        players.addAll(spectators.values());
        return players;
    }

    public List<Player> spectators() {
        return List.copyOf(spectators.values());
    }

    boolean isOver() {
        return over;
    }

    void markOver() {
        over = true;
    }

    void state(State newState) {
        state = newState;
    }

    int secondsLeft() {
        return secondsLeft;
    }

    void secondsLeft(int seconds) {
        secondsLeft = seconds;
    }

    int fightSeconds() {
        return fightSeconds;
    }

    void fightSeconds(int seconds) {
        fightSeconds = seconds;
    }

    int maxFightSeconds() {
        return maxFightSeconds;
    }

    void maxFightSeconds(int seconds) {
        maxFightSeconds = seconds;
    }

    BossBar bossBar() {
        return bossBar;
    }

    void bossBar(BossBar bar) {
        bossBar = bar;
    }

    BukkitTask task() {
        return task;
    }

    void task(BukkitTask newTask) {
        task = newTask;
    }

    void addSnapshot(Player player, PlayerSnapshot snapshot) {
        snapshots.put(player.getUniqueId(), snapshot);
        returnLocations.put(player.getUniqueId(), snapshot.location());
    }

    void addSpectator(Player spectator, PlayerSnapshot snapshot) {
        spectators.put(spectator.getUniqueId(), spectator);
        addSnapshot(spectator, snapshot);
    }

    void arrive(Player player) {
        arrived.add(player.getUniqueId());
    }

    boolean isParticipant(Player player) {
        return snapshots.containsKey(player.getUniqueId());
    }

    /** Removes a participant and returns the state to restore, if they were still part of the match. */
    Optional<PlayerSnapshot> release(Player player) {
        UUID uuid = player.getUniqueId();
        spectators.remove(uuid);
        arrived.remove(uuid);
        return Optional.ofNullable(snapshots.remove(uuid));
    }

    /** Where {@code player} was sent back to, or will be. */
    Location returnLocation(Player player) {
        return returnLocations.get(player.getUniqueId());
    }
}
