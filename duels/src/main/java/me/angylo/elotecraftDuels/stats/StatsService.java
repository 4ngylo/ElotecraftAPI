package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wins, losses and streaks in the {@code duels_stats} table. Online players' stats are cached so
 * placeholders and commands never wait for the database. The SQL works on both SQLite and MySQL.
 * Main thread only, except {@link #cached} and {@link #loadBlocking}.
 */
public final class StatsService {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_stats (
                uuid            VARCHAR(36) PRIMARY KEY,
                name            VARCHAR(16) NOT NULL,
                wins            INT NOT NULL DEFAULT 0,
                losses          INT NOT NULL DEFAULT 0,
                win_streak      INT NOT NULL DEFAULT 0,
                best_win_streak INT NOT NULL DEFAULT 0
            )""";
    private static final String COLUMNS = "name, wins, losses, win_streak, best_win_streak";
    private static final String FIND_BY_UUID = "SELECT " + COLUMNS + " FROM duels_stats WHERE uuid = ?";
    private static final String FIND_BY_NAME = "SELECT " + COLUMNS + " FROM duels_stats WHERE LOWER(name) = LOWER(?) LIMIT 1";
    private static final String TOP = "SELECT " + COLUMNS + " FROM duels_stats ORDER BY wins DESC, losses ASC LIMIT ?";
    private static final String UPDATE_NAME = "UPDATE duels_stats SET name = ? WHERE uuid = ?";
    // MySQL applies SET clauses left to right, so best_win_streak is set before win_streak changes;
    // SQLite reads the old values either way, so both give the same result.
    private static final String RECORD_WIN = """
            UPDATE duels_stats SET name = ?,
                best_win_streak = CASE WHEN win_streak + 1 > best_win_streak THEN win_streak + 1 ELSE best_win_streak END,
                win_streak = win_streak + 1,
                wins = wins + 1
            WHERE uuid = ?""";
    private static final String INSERT_WIN = """
            INSERT INTO duels_stats (uuid, name, wins, losses, win_streak, best_win_streak) VALUES (?, ?, 1, 0, 1, 1)""";
    private static final String RECORD_LOSS = "UPDATE duels_stats SET name = ?, losses = losses + 1, win_streak = 0 WHERE uuid = ?";
    private static final String INSERT_LOSS = """
            INSERT INTO duels_stats (uuid, name, wins, losses, win_streak, best_win_streak) VALUES (?, ?, 0, 1, 0, 0)""";

    private record Result(UUID winner, String winnerName, UUID loser, String loserName) {
    }

    private final Logger logger;
    private final Database db;
    private final CompletableFuture<Integer> schema;
    private final Map<UUID, PlayerStats> online = new ConcurrentHashMap<>();
    /** Results whose write failed, retried by {@link #retryFailed()}; written to by database threads at shutdown. */
    private final Queue<Result> failed = new ConcurrentLinkedQueue<>();

    public StatsService(Plugin plugin, Database db) {
        this.logger = plugin.getLogger();
        this.db = db;
        this.schema = db.update(CREATE);
    }

    /** Completes once the table exists. */
    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /** An online player's stats; safe from any thread. */
    public Optional<PlayerStats> cached(UUID player) {
        return Optional.ofNullable(online.get(player));
    }

    /**
     * Reads a joining player's stats and stores their current name. Blocks; for
     * {@code AsyncPlayerPreLoginEvent}. Cache the result with {@link #cache} once they join.
     */
    public PlayerStats loadBlocking(UUID player, String name) throws SQLException {
        return db.runBlocking(connection -> {
            PlayerStats stats = find(connection, player).orElse(PlayerStats.empty(name));
            if (!stats.name().equals(name)) {
                try (PreparedStatement rename = connection.prepareStatement(UPDATE_NAME)) {
                    rename.setString(1, name);
                    rename.setString(2, player.toString());
                    rename.executeUpdate();
                }
            }
            return new PlayerStats(name, stats.wins(), stats.losses(), stats.winStreak(), stats.bestWinStreak());
        });
    }

    /** Like {@link #loadBlocking} without blocking; caches the stats when they arrive. */
    public CompletableFuture<PlayerStats> load(Player player) {
        UUID uuid = player.getUniqueId();
        return schema.thenCompose(ignored -> db.queryOne(FIND_BY_UUID, StatsService::read, uuid))
                .thenApply(stats -> {
                    PlayerStats loaded = stats.orElse(PlayerStats.empty(player.getName()));
                    if (player.isOnline()) {
                        online.put(uuid, loaded);
                    }
                    return loaded;
                });
    }

    public void cache(UUID player, PlayerStats stats) {
        online.put(player, stats);
    }

    public void forget(UUID player) {
        online.remove(player);
    }

    /** Counts a finished duel: updates the cache at once and the database in one transaction. */
    public void recordResult(Player winner, Player loser) {
        online.compute(winner.getUniqueId(), (uuid, stats) -> (stats == null ? PlayerStats.empty(winner.getName()) : stats).win(winner.getName()));
        online.compute(loser.getUniqueId(), (uuid, stats) -> (stats == null ? PlayerStats.empty(loser.getName()) : stats).loss(loser.getName()));
        write(new Result(winner.getUniqueId(), winner.getName(), loser.getUniqueId(), loser.getName()));
    }

    /** Stats by player name; online players come from the cache. */
    public CompletableFuture<Optional<PlayerStats>> find(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player != null && online.containsKey(player.getUniqueId())) {
            return CompletableFuture.completedFuture(Optional.of(online.get(player.getUniqueId())));
        }
        return schema.thenCompose(ignored -> db.queryOne(FIND_BY_NAME, StatsService::read, name));
    }

    /** The {@code limit} players with the most wins. */
    public CompletableFuture<List<PlayerStats>> top(int limit) {
        return schema.thenCompose(ignored -> db.query(TOP, StatsService::read, limit));
    }

    /** Writes results that failed earlier; call periodically and before closing the database. */
    public void retryFailed() {
        for (int pending = failed.size(); pending > 0; pending--) {
            Result result = failed.poll();
            if (result == null) {
                return;
            }
            write(result);
        }
    }

    private void write(Result result) {
        schema.thenCompose(ignored -> db.transaction(connection -> {
            record(connection, RECORD_WIN, INSERT_WIN, result.winner(), result.winnerName());
            record(connection, RECORD_LOSS, INSERT_LOSS, result.loser(), result.loserName());
            return null;
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save the duel result " + result.winnerName() + " beat "
                    + result.loserName() + "; retrying every minute", error);
            failed.add(result);
            return null;
        });
    }

    /** Updates the player's row, or inserts it if this is their first finished duel. */
    private static void record(Connection connection, String update, String insert, UUID player, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setString(1, name);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, player.toString());
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    private static Optional<PlayerStats> find(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_UUID)) {
            statement.setString(1, player.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private static PlayerStats read(ResultSet row) throws SQLException {
        return new PlayerStats(row.getString("name"), row.getInt("wins"), row.getInt("losses"),
                row.getInt("win_streak"), row.getInt("best_win_streak"));
    }
}
