package me.angylo.elotecraftDuels.api;

import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A fight's first countdown ended and its fighters may hit each other: a duel, a party fight or an event fight
 * (each fight of a tournament too). Later rounds of the same fight do not fire it again. Main thread.
 */
public final class MatchStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<List<Player>> teams;
    private final String kit;
    private final String arena;
    private final Match.Type type;
    private final boolean ranked;

    public MatchStartEvent(Match match) {
        this.teams = match.teams();
        this.kit = match.kit().name();
        this.arena = match.arena().name();
        this.type = match.type();
        this.ranked = match.isRanked();
    }

    /** The fighters of each team, in spawn order; a duel is two teams of one. */
    public List<List<Player>> teams() {
        return teams;
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

    /** Whether the result moves Elo ratings: duels from the ranked queue. */
    public boolean ranked() {
        return ranked;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
