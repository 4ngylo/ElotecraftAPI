package me.angylo.elotecraftDuels.api;

import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A fight ended with a result or a draw, after stats, ratings and rewards were recorded and before the fighters
 * are sent back. Fights cancelled without a result (an admin stop, a quit before the fight, a shutdown) do not
 * fire it. Main thread.
 */
public final class MatchEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<List<Player>> teams;
    private final List<Player> winners;
    private final String kit;
    private final String arena;
    private final Match.Type type;
    private final boolean ranked;
    private final Match.EndReason reason;
    private final int fightSeconds;

    public MatchEndEvent(Match match, List<Integer> winnerTeams, Match.EndReason reason) {
        this.teams = match.teams();
        this.winners = winnerTeams.stream().flatMap(team -> teams.get(team).stream()).toList();
        this.kit = match.kit().name();
        this.arena = match.arena().name();
        this.type = match.type();
        this.ranked = match.isRanked();
        this.reason = reason;
        this.fightSeconds = match.fightSeconds();
    }

    /** The fighters of each team, in spawn order; a duel is two teams of one. */
    public List<List<Player>> teams() {
        return teams;
    }

    /** Every fighter of the winning teams, knocked out or not; empty for a draw. */
    public List<Player> winners() {
        return winners;
    }

    public boolean draw() {
        return winners.isEmpty();
    }

    /** The kit's name ({@code custom} for a player's custom kit). */
    public String kit() {
        return kit;
    }

    /** The arena's name, also for a copy of it. */
    public String arena() {
        return arena;
    }

    public Match.Type type() {
        return type;
    }

    /** Whether the result moved Elo ratings: duels from the ranked queue. */
    public boolean ranked() {
        return ranked;
    }

    /** How it ended: a knockout, a quit, a forfeit, or {@link Match.EndReason#TIMEOUT} for a draw. */
    public Match.EndReason reason() {
        return reason;
    }

    /** How long the last round was fought. */
    public int fightSeconds() {
        return fightSeconds;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
