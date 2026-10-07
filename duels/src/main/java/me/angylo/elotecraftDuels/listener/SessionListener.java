package me.angylo.elotecraftDuels.listener;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.match.RequestManager;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads a player's stats and any snapshot left by an unfinished duel before they join, restores that
 * snapshot as they join, and takes a quitting player out of duels, requests and queues.
 */
public final class SessionListener implements Listener {

    private static final long STALE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private record Preloaded(PlayerStats stats, String snapshot, long loadedAtNanos) {
    }

    private final Logger logger;
    private final Messages messages;
    private final StatsService stats;
    private final SnapshotStore snapshots;
    private final MatchManager matches;
    private final RequestManager requests;
    private final QueueManager queues;
    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();

    public SessionListener(Plugin plugin, Messages messages, StatsService stats, SnapshotStore snapshots,
                           MatchManager matches, RequestManager requests, QueueManager queues) {
        this.logger = plugin.getLogger();
        this.messages = messages;
        this.stats = stats;
        this.snapshots = snapshots;
        this.matches = matches;
        this.requests = requests;
        this.queues = queues;
    }

    /** Runs on a login thread, so waiting for the database here never lags the server. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        try {
            PlayerStats loaded = stats.loadBlocking(uuid, event.getName());
            String snapshot = snapshots.findBlocking(uuid).orElse(null);
            preloaded.put(uuid, new Preloaded(loaded, snapshot, System.nanoTime()));
        } catch (SQLException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not load duel data of " + event.getName() + " before they joined; loading it now", e);
        }
    }

    /** LOWEST, so a leftover duel is undone before other plugins see the player. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Preloaded data = preloaded.remove(player.getUniqueId());
        if (data == null) {
            load(player);
            return;
        }
        stats.cache(player.getUniqueId(), data.stats());
        if (data.snapshot() != null) {
            recover(player, data.snapshot());
        }
    }

    /** LOWEST, so the player is restored before other plugins (combat loggers, graves) act on the quit. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        matches.handleQuit(player);
        requests.handleQuit(player);
        queues.handleQuit(player);
        snapshots.forget(player.getUniqueId());
        stats.forget(player.getUniqueId());
    }

    /** Loads stats and restores a leftover snapshot for an online player, without blocking. */
    public void load(Player player) {
        stats.load(player).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not load the duel stats of " + player.getName(), error);
            return null;
        });
        snapshots.find(player.getUniqueId()).thenAccept(snapshot -> snapshot.ifPresent(text -> {
            if (player.isOnline() && !matches.isRestricted(player)) {
                recover(player, text);
            }
        })).exceptionally(error -> {
            logger.log(Level.SEVERE, "Could not check " + player.getName() + " for an unfinished duel", error);
            return null;
        });
    }

    /** Forgets data of logins that never joined, e.g. kicked by another plugin. */
    public void purgeStale() {
        long now = System.nanoTime();
        preloaded.values().removeIf(data -> now - data.loadedAtNanos() > STALE_NANOS);
    }

    private void recover(Player player, String text) {
        PlayerSnapshot snapshot;
        try {
            snapshot = PlayerSnapshot.fromText(text);
        } catch (IllegalArgumentException e) {
            logger.log(Level.SEVERE, "The saved duel state of " + player.getName() + " is unreadable; it was left in "
                    + "the duels_snapshots table for an admin to check", e);
            return;
        }
        snapshots.restore(player, snapshot, false);
        messages.send(player, "general.restored");
        logger.info("Restored " + player.getName() + "'s items and position from a duel that didn't finish");
    }
}
