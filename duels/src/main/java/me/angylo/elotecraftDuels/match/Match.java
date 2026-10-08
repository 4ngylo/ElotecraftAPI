package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftDuels.PlayerOptions;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaInstance;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
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
 * One fight between teams of players, with its spectators: a duel is two teams of one. Fighters who are
 * knocked out stay in the arena as spectators until it ends; the last team with a fighter left wins.
 * Holds the state; {@link MatchManager} moves it forward. Main thread only, except the final fields,
 * which placeholders may read from any thread.
 */
public final class Match {

    /** Phases in order; a match only moves forward, except from {@link #ROUND_OVER} back to {@link #COUNTDOWN}. */
    public enum State {
        /** Saving everyone's state and teleporting them in. */
        STARTING,
        /** Frozen in place while the countdown runs. */
        COUNTDOWN,
        FIGHTING,
        /** A round of a {@link KitRule#ROUNDS_TO_WIN} duel was won; nobody can be hurt until the next countdown. */
        ROUND_OVER,
        /** Result shown; nobody can be hurt until everyone is sent back. */
        ENDING
    }

    /** Why a match ended. */
    public enum EndReason {
        ELIMINATED, QUIT, FORFEIT, TIMEOUT
    }

    /** What kind of fight: only duels count in the stats, pay rewards and offer rematches; events pay their own. */
    public enum Type {
        DUEL, PARTY, EVENT
    }

    /**
     * How a fight ends and who may watch it; duels and party fights use {@link #DEFAULT}.
     *
     * @param winners     how many teams win: the fight ends once this many or fewer have a fighter left
     * @param spectatable whether outsiders may watch
     * @param border      whether a border closes in on the fighters (config.yml {@code events.border})
     * @param host        the name of the player hosting an event, or null
     * @param bracket     a fight of a tournament: no event reward, and the result goes to its fighters only
     */
    public record Options(int winners, boolean spectatable, boolean border, String host, boolean bracket) {

        public static final Options DEFAULT = new Options(1, true, false, null, false);

        public Options {
            if (winners < 1) {
                throw new IllegalArgumentException("At least one winner: " + winners);
            }
        }
    }

    private final ArenaInstance instance;
    private final Kit kit;
    /** Fighters by team, in spawn order. */
    private final List<List<Player>> teams;
    private final Type type;
    /** Whether the result moves the fighters' Elo ratings: duels from the queue. */
    private final boolean ranked;
    private final Options options;
    /** Fighters knocked out of the fight. */
    private final Set<UUID> knockedOut = new HashSet<>();
    /** Everyone still to be restored, fighters and spectators, with their pre-duel state. */
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Player> spectators = new LinkedHashMap<>();
    /** Participants who reached the arena, so its bounds apply to them. */
    private final Set<UUID> arrived = new HashSet<>();
    /** Kept after release, for teleports into the arena that land after the player was sent back. */
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    /** Hits each fighter took from opponents, for {@code hits-to-win} kits. */
    private final Map<UUID, Integer> hitsTaken = new HashMap<>();
    private final FightStats fightStats = new FightStats();
    /** Rounds won by each team, for {@link KitRule#ROUNDS_TO_WIN} duels. */
    private final int[] roundWins;
    private int round = 1;
    /** The id of this fight's kept inventories once it ended with a result, for the links sent when it is over. */
    private UUID resultsId;
    /** Each fighter as they left the fight, in the order they left it. */
    private final Map<UUID, FighterResult> finals = new LinkedHashMap<>();
    private State state = State.STARTING;
    private int secondsLeft;
    private int fightSeconds;
    private int maxFightSeconds;
    private BossBar bossBar;
    private FightBorder border;
    private BukkitTask task;
    private boolean over;
    /** Set when the fight ends with a result; empty for a draw or a cancelled fight. */
    private List<Integer> winnerTeams = List.of();
    /** Null until the fight ends with a result or a draw. */
    private EndReason endReason;

    Match(ArenaInstance instance, Kit kit, List<List<Player>> teams, Type type, boolean ranked, Options options) {
        this.instance = instance;
        this.kit = kit;
        this.teams = teams.stream().map(List::copyOf).toList();
        this.type = type;
        this.ranked = ranked;
        this.options = options;
        this.roundWins = new int[teams.size()];
    }

    public Type type() {
        return type;
    }

    public Options options() {
        return options;
    }

    /** The teams that won, once the fight ended; empty for a draw or a cancelled fight. */
    public List<Integer> winnerTeams() {
        return winnerTeams;
    }

    /** How the fight ended; null if it was cancelled or is not over. */
    public EndReason endReason() {
        return endReason;
    }

    void result(List<Integer> winners, EndReason reason) {
        winnerTeams = List.copyOf(winners);
        endReason = reason;
    }

    public boolean isDuel() {
        return type == Type.DUEL;
    }

    /** The fighters of each team, in spawn order. */
    public List<List<Player>> teams() {
        return teams;
    }

    /** Every fighter, team by team. */
    public List<Player> fighters() {
        return teams.stream().flatMap(List::stream).toList();
    }

    /** The team {@code player} fights in, or -1; safe from any thread. */
    public int teamOf(UUID player) {
        for (int team = 0; team < teams.size(); team++) {
            for (Player fighter : teams.get(team)) {
                if (fighter.getUniqueId().equals(player)) {
                    return team;
                }
            }
        }
        return -1;
    }

    /** Whether two fighters are on the same team. */
    public boolean sameTeam(Player one, Player other) {
        int team = teamOf(one.getUniqueId());
        return team >= 0 && team == teamOf(other.getUniqueId());
    }

    /** The names of everyone fighting against {@code player}, joined with commas; safe from any thread. */
    public String opponentNames(UUID player) {
        int team = teamOf(player);
        List<String> names = new ArrayList<>();
        for (int other = 0; other < teams.size(); other++) {
            if (other != team) {
                teams.get(other).forEach(fighter -> names.add(fighter.getName()));
            }
        }
        return String.join(", ", names);
    }

    public boolean isRanked() {
        return ranked;
    }

    public Arena arena() {
        return instance.arena();
    }

    /** The arena in the world this duel runs in. */
    public ArenaInstance instance() {
        return instance;
    }

    /** Whether {@code location} is inside this duel's arena, in the world it runs in. */
    public boolean contains(Location location) {
        return instance.contains(location);
    }

    public Location spectatorSpawn() {
        return instance.spectatorSpawn();
    }

    public Kit kit() {
        return kit;
    }

    /** The first fighter of the first team: in a duel, one of the two. */
    public Player first() {
        return teams.getFirst().getFirst();
    }

    /** The first fighter of the second team: in a duel, the other one. */
    public Player second() {
        return teams.get(1).getFirst();
    }

    public State state() {
        return state;
    }

    public boolean isFighter(Player player) {
        return teamOf(player.getUniqueId()) >= 0;
    }

    /** A fighter still in the fight: not knocked out, not gone. */
    public boolean isAlive(Player player) {
        return isFighter(player) && !knockedOut.contains(player.getUniqueId()) && snapshots.containsKey(player.getUniqueId());
    }

    /** The teams with a fighter still in the fight. */
    public List<Integer> teamsLeft() {
        List<Integer> left = new ArrayList<>();
        for (int team = 0; team < teams.size(); team++) {
            if (teams.get(team).stream().anyMatch(this::isAlive)) {
                left.add(team);
            }
        }
        return left;
    }

    public boolean isSpectator(Player player) {
        return spectators.containsKey(player.getUniqueId());
    }

    /** A fighter who may hit and be hit right now. */
    public boolean isFighting(Player player) {
        return state == State.FIGHTING && isAlive(player);
    }

    public FightStats fightStats() {
        return fightStats;
    }

    /** Keeps {@code fighter} as they are now for {@code /duel inventory}, unless they were kept already. */
    public void recordFinal(Player fighter) {
        if (isFighter(fighter)) {
            finals.computeIfAbsent(fighter.getUniqueId(), uuid -> FighterResult.capture(fighter, fightStats));
        }
    }

    UUID resultsId() {
        return resultsId;
    }

    void resultsId(UUID id) {
        this.resultsId = id;
    }

    /** The fighters kept by {@link #recordFinal}. */
    public List<FighterResult> finals() {
        return List.copyOf(finals.values());
    }

    /** Rounds a team must win to win the fight: the kit's {@link KitRule#ROUNDS_TO_WIN} in a duel, else 1. */
    public int roundsToWin() {
        return isDuel() ? Math.max(1, kit.number(KitRule.ROUNDS_TO_WIN).orElse(1)) : 1;
    }

    /** The round being fought, from 1. */
    public int round() {
        return round;
    }

    /** Rounds {@code team} has won. */
    public int roundWins(int team) {
        return roundWins[team];
    }

    /** {@code team}'s rounds against the others', such as {@code 2 - 1}. */
    public String score(int team) {
        int others = 0;
        for (int other = 0; other < roundWins.length; other++) {
            if (other != team) {
                others += roundWins[other];
            }
        }
        return roundWins[Math.max(0, team)] + " - " + others;
    }

    /** Whether a round was played and the next has not started: leaving now loses the fight instead of cancelling it. */
    public boolean betweenRounds() {
        return state == State.ROUND_OVER || (state == State.COUNTDOWN && round > 1);
    }

    /** Gives {@code team} a round; returns how many it has won. */
    int winRound(int team) {
        return ++roundWins[team];
    }

    /** Brings everyone back into the fight for the next round; fight counts carry over, kill credit does not. */
    void nextRound() {
        round++;
        knockedOut.clear();
        hitsTaken.clear();
        finals.clear();
        fightStats.clearLastHits();
    }

    /**
     * Whether {@code viewer} may start watching: the fight allows spectators and, except in events, every fighter
     * takes them ({@link PlayerOptions#SPECTATORS}). Staff ({@code duels.admin}) may watch any fight.
     */
    public boolean watchableBy(Player viewer) {
        return viewer.hasPermission("duels.admin") || (options().spectatable()
                && (type() == Type.EVENT || fighters().stream().allMatch(PlayerOptions.SPECTATORS::isOn)));
    }

    /** Counts a hit on {@code fighter} by an opponent; returns how many they have taken. */
    public int hit(Player fighter) {
        return hitsTaken.merge(fighter.getUniqueId(), 1, Integer::sum);
    }

    /** Whether {@code player} may change blocks at {@code location} now: fighting in a build duel, inside its arena. */
    public boolean canBuild(Player player, Location location) {
        return isFighting(player) && instance.isBuild() && !instance.isClosing() && instance.contains(location);
    }

    /** In a duel, the other fighter. */
    public Player opponentOf(Player fighter) {
        return first().getUniqueId().equals(fighter.getUniqueId()) ? second() : first();
    }

    /** Where {@code fighter} starts: see {@link ArenaInstance#spawnFor}. */
    public Location spawnOf(Player fighter) {
        return instance.spawnFor(Math.max(0, teamOf(fighter.getUniqueId())), teams.size());
    }

    /** Whether the arena's bounds and freeze apply to {@code player} yet. */
    public boolean hasArrived(Player player) {
        return arrived.contains(player.getUniqueId());
    }

    /** Fighters and spectators who are still part of this match. */
    public List<Player> participants() {
        List<Player> players = new ArrayList<>();
        for (Player fighter : fighters()) {
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

    /** Seconds until the fight ends in a draw; the whole duration before it starts, 0 once it is over. */
    public int timeLeftSeconds() {
        return switch (state) {
            case STARTING, COUNTDOWN, ROUND_OVER -> maxFightSeconds;
            case FIGHTING -> Math.max(0, maxFightSeconds - fightSeconds);
            case ENDING -> 0;
        };
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

    /** The closing border, once the fight started with one; else null. */
    FightBorder border() {
        return border;
    }

    void border(FightBorder newBorder) {
        border = newBorder;
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

    void knockOut(Player player) {
        knockedOut.add(player.getUniqueId());
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
