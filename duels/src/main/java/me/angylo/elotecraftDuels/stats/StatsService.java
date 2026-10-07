package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Wins, losses, streaks and Elo ratings in the {@code duels_stats} table. Online players' stats are cached
 * so placeholders and commands never wait for the database. The SQL works on both SQLite and MySQL.
 * Main thread only, except {@link #cached}, {@link #elo} and {@link #loadBlocking}.
 */
public final class StatsService {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_stats (
                uuid            VARCHAR(36) PRIMARY KEY,
                name            VARCHAR(16) NOT NULL,
                wins            INT NOT NULL DEFAULT 0,
                losses          INT NOT NULL DEFAULT 0,
                win_streak      INT NOT NULL DEFAULT 0,
                best_win_streak INT NOT NULL DEFAULT 0,
                elo             INT NOT NULL DEFAULT\s""" + PlayerStats.START_ELO + ")";
    /** Tables made before ranked duels lack the elo column; its default gives every player the starting rating. */
    private static final String ADD_ELO = "ALTER TABLE duels_stats ADD COLUMN elo INT NOT NULL DEFAULT " + PlayerStats.START_ELO;
    private static final String COLUMNS = "name, wins, losses, win_streak, best_win_streak, elo";
    private static final String FIND_BY_UUID = "SELECT " + COLUMNS + " FROM duels_stats WHERE uuid = ?";
    private static final String FIND_BY_NAME = "SELECT " + COLUMNS + " FROM duels_stats WHERE LOWER(name) = LOWER(?) LIMIT 1";
    private static final String TOP = "SELECT " + COLUMNS + " FROM duels_stats ORDER BY wins DESC, losses ASC LIMIT ?";
    private static final String TOP_ELO = "SELECT " + COLUMNS + " FROM duels_stats ORDER BY elo DESC, wins DESC LIMIT ?";
    private static final String UPDATE_NAME = "UPDATE duels_stats SET name = ? WHERE uuid = ?";
    // MySQL applies SET clauses left to right, so best_win_streak is set before win_streak changes;
    // SQLite reads the old values either way, so both give the same result.
    // Rating changes are added to the stored value, so servers sharing a MySQL database cannot overwrite each other's.
    private static final String RECORD_WIN = """
            UPDATE duels_stats SET name = ?,
                best_win_streak = CASE WHEN win_streak + 1 > best_win_streak THEN win_streak + 1 ELSE best_win_streak END,
                win_streak = win_streak + 1,
                wins = wins + 1,
                elo = elo + ?
            WHERE uuid = ?""";
    private static final String INSERT_WIN = """
            INSERT INTO duels_stats (uuid, name, wins, losses, win_streak, best_win_streak, elo) VALUES (?, ?, 1, 0, 1, 1, ?)""";
    private static final String RECORD_LOSS = """
            UPDATE duels_stats SET name = ?, losses = losses + 1, win_streak = 0, elo = elo + ? WHERE uuid = ?""";
    private static final String INSERT_LOSS = """
            INSERT INTO duels_stats (uuid, name, wins, losses, win_streak, best_win_streak, elo) VALUES (?, ?, 0, 1, 0, 0, ?)""";

    /** {@code eloChange} is what the winner gained and the loser lost; 0 for an unranked duel. */
    private record Result(UUID winner, String winnerName, UUID loser, String loserName, int eloChange) {
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
        this.schema = db.update(CREATE).thenCompose(ignored -> db.transaction(StatsService::addEloColumn));
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
            return new PlayerStats(name, stats.wins(), stats.losses(), stats.winStreak(), stats.bestWinStreak(), stats.elo());
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

    /** An online player's rating, or the starting one if their stats are not loaded; safe from any thread. */
    public int elo(UUID player) {
        PlayerStats stats = online.get(player);
        return stats == null ? PlayerStats.START_ELO : stats.elo();
    }

    /** Counts a finished unranked duel. */
    public void recordResult(Player winner, Player loser) {
        recordResult(winner, loser, 0);
    }

    /**
     * Counts a finished duel: updates the cache at once and the database in one transaction.
     *
     * @param eloChange rating the winner takes from the loser; 0 for an unranked duel
     */
    public void recordResult(Player winner, Player loser, int eloChange) {
        online.compute(winner.getUniqueId(), (uuid, stats) ->
                (stats == null ? PlayerStats.empty(winner.getName()) : stats).win(winner.getName(), eloChange));
        online.compute(loser.getUniqueId(), (uuid, stats) ->
                (stats == null ? PlayerStats.empty(loser.getName()) : stats).loss(loser.getName(), eloChange));
        write(new Result(winner.getUniqueId(), winner.getName(), loser.getUniqueId(), loser.getName(), eloChange));
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

    /** The {@code limit} players with the highest rating. */
    public CompletableFuture<List<PlayerStats>> topByElo(int limit) {
        return schema.thenCompose(ignored -> db.query(TOP_ELO, StatsService::read, limit));
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
            record(connection, RECORD_WIN, INSERT_WIN, result.winner(), result.winnerName(), result.eloChange());
            record(connection, RECORD_LOSS, INSERT_LOSS, result.loser(), result.loserName(), -result.eloChange());
            return null;
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save the duel result " + result.winnerName() + " beat "
                    + result.loserName() + "; retrying every minute", error);
            failed.add(result);
            return null;
        });
    }

    /** Updates the player's row, or inserts it if this is their first finished duel. */
    private static void record(Connection connection, String update, String insert, UUID player, String name,
                               int eloChange) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setString(1, name);
            statement.setInt(2, eloChange);
            statement.setString(3, player.toString());
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, player.toString());
            statement.setString(2, name);
            statement.setInt(3, PlayerStats.START_ELO + eloChange);
            statement.executeUpdate();
        }
    }

    /** Adds the elo column to a table made by an older version; reads the columns the same way on SQLite and MySQL. */
    private static Integer addEloColumn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet none = statement.executeQuery("SELECT * FROM duels_stats WHERE 1 = 0")) {
                ResultSetMetaData columns = none.getMetaData();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    if (columns.getColumnName(i).equalsIgnoreCase("elo")) {
                        return 0;
                    }
                }
            }
            return statement.executeUpdate(ADD_ELO);
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
                row.getInt("win_streak"), row.getInt("best_win_streak"), row.getInt("elo"));
    }
}
