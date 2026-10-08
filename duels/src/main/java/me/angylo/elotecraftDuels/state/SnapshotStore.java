package me.angylo.elotecraftDuels.state;

import me.angylo.elotecraftAPI.storage.Database;
import me.angylo.elotecraftAPI.util.Tasks;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps each player's pre-duel {@link PlayerSnapshot} in the database until it has been put back, so a
 * crash, kick or disconnect can never leave anyone with kit items or stuck in an arena: whatever is
 * still stored is restored when the player next joins. Main thread only, except {@link #findBlocking}.
 */
public final class SnapshotStore {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_snapshots (
                uuid VARCHAR(36) PRIMARY KEY,
                id   VARCHAR(36) NOT NULL,
                data MEDIUMTEXT  NOT NULL
            )""";
    private static final String DELETE_PLAYER = "DELETE FROM duels_snapshots WHERE uuid = ?";
    private static final String INSERT = "INSERT INTO duels_snapshots (uuid, id, data) VALUES (?, ?, ?)";
    // The id check keeps a late or retried delete from removing a newer snapshot of the same player.
    private static final String DELETE_SNAPSHOT = "DELETE FROM duels_snapshots WHERE uuid = ? AND id = ?";
    private static final String FIND = "SELECT data FROM duels_snapshots WHERE uuid = ?";
    private static final long RESPAWN_RESTORE_DELAY_TICKS = 1;

    private final Plugin plugin;
    private final Logger logger;
    private final Database db;
    private final CompletableFuture<Integer> schema;
    /** Players whose duel ended while they were dead; restored when they respawn. */
    private final Map<UUID, PlayerSnapshot> awaitingRespawn = new ConcurrentHashMap<>();
    /** Players an async teleport is sending back, and where; their snapshot is already deleted. */
    private final Map<UUID, Location> returning = new ConcurrentHashMap<>();
    /** Snapshot ids whose delete failed, retried by {@link #retryFailedDeletes()}. */
    private final Map<UUID, UUID> failedDeletes = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> savesInFlight = new ConcurrentHashMap<>();

    public SnapshotStore(Plugin plugin, Database db) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.db = db;
        this.schema = db.update(CREATE);
    }

    /** Completes once the table exists. */
    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /**
     * Stores the snapshots in one transaction, replacing older ones of the same players. Only change a
     * player after this completes: if it fails, nothing may be changed.
     */
    public CompletableFuture<Void> save(Map<UUID, PlayerSnapshot> snapshots) {
        Map<UUID, PlayerSnapshot> batch = Map.copyOf(snapshots);
        // Serialized here: items are read through the server, which is only safe on the main thread.
        Map<UUID, String> texts = new HashMap<>();
        batch.forEach((player, snapshot) -> texts.put(player, snapshot.toText()));
        CompletableFuture<Void> saved = write(batch, texts);
        batch.keySet().forEach(player -> {
            savesInFlight.put(player, saved);
            saved.whenComplete((ignored, error) -> savesInFlight.remove(player, saved));
        });
        return saved;
    }

    private CompletableFuture<Void> write(Map<UUID, PlayerSnapshot> batch, Map<UUID, String> texts) {
        return schema.thenCompose(ignored -> db.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(DELETE_PLAYER);
                 PreparedStatement insert = connection.prepareStatement(INSERT)) {
                for (Map.Entry<UUID, PlayerSnapshot> entry : batch.entrySet()) {
                    delete.setString(1, entry.getKey().toString());
                    delete.executeUpdate();
                    insert.setString(1, entry.getKey().toString());
                    insert.setString(2, entry.getValue().id().toString());
                    insert.setString(3, texts.get(entry.getKey()));
                    insert.executeUpdate();
                }
            }
            return null;
        }));
    }

    /**
     * Puts {@code snapshot} back and deletes it from storage. A dead player is restored when they respawn.
     *
     * @param teleportNow teleport right away instead of loading the chunk first; for quits and shutdown,
     *                    where there is no time to wait
     */
    public void restore(Player player, PlayerSnapshot snapshot, boolean teleportNow) {
        if (player.isDead()) {
            awaitingRespawn.put(player.getUniqueId(), snapshot);
            return;
        }
        snapshot.applyState(player);
        Location location = snapshot.location();
        if (teleportNow) {
            player.teleport(location, TeleportCause.PLUGIN);
        } else {
            UUID uuid = player.getUniqueId();
            returning.put(uuid, location);
            player.teleportAsync(location, TeleportCause.PLUGIN).whenComplete((arrived, error) -> {
                returning.remove(uuid, location);
                if ((error != null || !Boolean.TRUE.equals(arrived)) && player.isOnline()) {
                    logger.log(Level.WARNING, "Could not send " + player.getName() + " back after a duel; teleporting at once", error);
                    player.teleport(location, TeleportCause.PLUGIN);
                }
            });
        }
        delete(player.getUniqueId(), snapshot.id());
    }

    /**
     * Call when {@code player} quits: one still on the way back from a duel is put there at once, before the
     * server saves them, since their snapshot is gone and they would otherwise stay in the arena.
     */
    public void finishReturn(Player player) {
        Location location = returning.remove(player.getUniqueId());
        if (location != null) {
            player.teleport(location, TeleportCause.PLUGIN);
        }
    }

    /** Whether {@code player} is still on the way back from a fight, or waits to respawn for it. */
    public boolean isReturning(UUID player) {
        return returning.containsKey(player) || awaitingRespawn.containsKey(player);
    }

    /** Where to respawn {@code player} if their duel ended while they were dead. */
    public Optional<Location> respawnLocation(Player player) {
        return Optional.ofNullable(awaitingRespawn.get(player.getUniqueId())).map(PlayerSnapshot::location);
    }

    /** Call after {@code player} respawned; restores the rest of a pending snapshot a tick later. */
    public void restoreAfterRespawn(Player player) {
        PlayerSnapshot snapshot = awaitingRespawn.remove(player.getUniqueId());
        if (snapshot != null) {
            Tasks.later(plugin, () -> {
                if (player.isOnline()) {
                    restore(player, snapshot, false);
                }
            }, RESPAWN_RESTORE_DELAY_TICKS);
        }
    }

    /** Drops a quitting player's pending respawn restore; the stored row restores them on their next join. */
    public void forget(UUID player) {
        awaitingRespawn.remove(player);
    }

    /**
     * The stored snapshot of {@code player}, as text to parse on the main thread with
     * {@link PlayerSnapshot#fromText}. Blocks; for {@code AsyncPlayerPreLoginEvent} only.
     */
    public Optional<String> findBlocking(UUID player) throws SQLException {
        return db.runBlocking(connection -> {
            try (PreparedStatement find = connection.prepareStatement(FIND)) {
                find.setString(1, player.toString());
                try (ResultSet row = find.executeQuery()) {
                    return row.next() ? Optional.of(row.getString("data")) : Optional.empty();
                }
            }
        });
    }

    /** Like {@link #findBlocking} without blocking; for players already online. */
    public CompletableFuture<Optional<String>> find(UUID player) {
        return schema.thenCompose(ignored -> db.queryOne(FIND, row -> row.getString("data"), player));
    }

    /** Retries deletes that failed, so a restored snapshot is not applied a second time. */
    public void retryFailedDeletes() {
        Map<UUID, UUID> retry = Map.copyOf(failedDeletes);
        retry.forEach((player, snapshot) -> {
            failedDeletes.remove(player, snapshot);
            delete(player, snapshot);
        });
    }

    /**
     * Deletes once any save of the same player has finished: with a MySQL pool the two could otherwise run
     * at once, and a delete landing before the insert would leave a stale snapshot behind.
     */
    private void delete(UUID player, UUID snapshot) {
        CompletableFuture<Void> saving = savesInFlight.getOrDefault(player, CompletableFuture.completedFuture(null));
        saving.handle((ignored, error) -> null)
                .thenCompose(ignored -> db.update(DELETE_SNAPSHOT, player, snapshot))
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Could not delete the restored duel snapshot of " + player
                            + "; retrying every minute. Until it succeeds it would be restored again on their next join.", error);
                    failedDeletes.put(player, snapshot);
                    return null;
                });
    }
}
