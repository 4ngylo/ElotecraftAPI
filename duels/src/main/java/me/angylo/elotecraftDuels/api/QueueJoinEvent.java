package me.angylo.elotecraftDuels.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to join a kit's unranked or ranked queue, after every check of ElotecraftDuels passed.
 * Cancelling it keeps them out; the canceller tells them why. Main thread.
 */
public final class QueueJoinEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String kit;
    private final boolean ranked;
    private boolean cancelled;

    public QueueJoinEvent(Player player, String kit, boolean ranked) {
        super(player);
        this.kit = kit;
        this.ranked = ranked;
    }

    public String kit() {
        return kit;
    }

    public boolean ranked() {
        return ranked;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
