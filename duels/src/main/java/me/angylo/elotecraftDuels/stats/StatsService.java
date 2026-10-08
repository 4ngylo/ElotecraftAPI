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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 * Wins, losses and streaks in the {@code duels_stats} table, and a rating per kit in {@code duels_ratings}.
 * Online players' stats are cached so placeholders and commands never wait for the database. The SQL works
 * on both SQLite and MySQL. Main thread only, except {@link #cached}, {@link #elo} and {@link #loadBlocking}.
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
    // ponytail: no index on (kit, elo); leaderboards scan the table, add one if it ever holds millions of rows
    private static final String CREATE_RATINGS = """
            CREATE TABLE IF NOT EXISTS duels_ratings (
                uuid   VARCHAR(36) NOT NULL,
                kit    VARCHAR(64) NOT NULL,
                elo    INT NOT NULL,
                wins   INT NOT NULL DEFAULT 0,
                losses INT NOT NULL DEFAULT 0,
                peak   INT NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, kit)
            )""";
    /** Rating tables made before peaks lack the column; each rating's peak starts at the rating. */
    private static final String ADD_PEAK = "ALTER TABLE duels_ratings ADD COLUMN peak INT NOT NULL DEFAULT 0";
    private static final String START_PEAKS = "UPDATE duels_ratings SET peak = elo";
    private static final String COLUMNS = "uuid, name, wins, losses, win_streak, best_win_streak, elo";
    private static final String FIND_BY_UUID = "SELECT " + COLUMNS + " FROM duels_stats WHERE uuid = ?";
    private static final String FIND_BY_NAME = "SELECT " + COLUMNS + " FROM duels_stats WHERE LOWER(name) = LOWER(?) LIMIT 1";
    private static final String FIND_RATINGS = "SELECT kit, elo, wins, losses, peak FROM duels_ratings WHERE uuid = ?";
    private static final String TOP = "SELECT " + COLUMNS + " FROM duels_stats ORDER BY wins DESC, losses ASC LIMIT ?";
    private static final String TOP_KIT = """
            SELECT s.name, r.elo, r.wins, r.losses FROM duels_ratings r JOIN duels_stats s ON s.uuid = r.uuid
            WHERE r.kit = ? ORDER BY r.elo DESC, r.wins DESC LIMIT ?""";
    /** {@code %s} is one {@code ?} per kit. */
    private static final String TOP_OVERALL = """
            SELECT s.name, AVG(r.elo) AS avg_elo, SUM(r.wins) AS total_wins, SUM(r.losses) AS total_losses
            FROM duels_ratings r JOIN duels_stats s ON s.uuid = r.uuid
            WHERE r.kit IN (%s) GROUP BY r.uuid, s.name ORDER BY avg_elo DESC, total_wins DESC LIMIT ?""";
    private static final String UPDATE_NAME = "UPDATE duels_stats SET name = ? WHERE uuid = ?";
    // MySQL applies SET clauses left to right, so best_win_streak is set before win_streak changes;
    // SQLite reads the old values either way, so both give the same result.
    // The elo column is left alone: ratings are per kit now, and it is where they start from.
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
    // Rating changes are added to the stored value, so servers sharing a MySQL database cannot overwrite each other's.
    // peak comes first: MySQL applies SET clauses left to right, so it reads the rating before this change.
    private static final String RECORD_RATING = """
            UPDATE duels_ratings SET peak = CASE WHEN elo + ? > peak THEN elo + ? ELSE peak END,
                elo = elo + ?, wins = wins + ?, losses = losses + ?
            WHERE uuid = ? AND kit = ?""";
    /** A first ranked duel with a kit: the rating starts from the old one rating, in the row written just before. */
    private static final String INSERT_RATING = """
            INSERT INTO duels_ratings (uuid, kit, elo, wins, losses, peak)
            SELECT uuid, ?, elo + ?, ?, ?, CASE WHEN ? > 0 THEN elo + ? ELSE elo END FROM duels_stats WHERE uuid = ?""";

    /**
     * @param kit       the kit of a ranked duel, whose ratings move; null for an unranked duel
     * @param eloChange what the winner gained and the loser lost
     */
    private record Result(UUID winner, String winnerName, UUID loser, String loserName, String kit, int eloChange) {
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
        // One transaction, queued first: logins read both tables without waiting for this future.
        this.schema = db.transaction(StatsService::createTables);
    }

    /** Completes once the tables exist. */
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
            PlayerStats stats = find(connection, FIND_BY_UUID, player.toString()).orElse(PlayerStats.empty(name));
            if (!stats.name().equals(name)) {
                try (PreparedStatement rename = connection.prepareStatement(UPDATE_NAME)) {
                    rename.setString(1, name);
                    rename.setString(2, player.toString());
                    rename.executeUpdate();
                }
            }
            return new PlayerStats(name, stats.wins(), stats.losses(), stats.winStreak(), stats.bestWinStreak(),
                    stats.legacyElo(), stats.ratings());
        });
    }

    /** Like {@link #loadBlocking} without blocking; caches the stats when they arrive. */
    public CompletableFuture<PlayerStats> load(Player player) {
        UUID uuid = player.getUniqueId();
        return schema.thenCompose(ignored -> db.transaction(connection -> find(connection, FIND_BY_UUID, uuid.toString())))
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

    /** An online player's rating in {@code kit}, or the starting one if their stats are not loaded; safe from any thread. */
    public int elo(UUID player, String kit) {
        PlayerStats stats = online.get(player);
        return stats == null ? PlayerStats.START_ELO : stats.elo(kit);
    }

    /** After a season ended ({@link Seasons#end}): online players' cached ratings start again, like the stored ones. */
    public void seasonReset() {
        online.replaceAll((uuid, stats) -> new PlayerStats(stats.name(), stats.wins(), stats.losses(), stats.winStreak(),
                stats.bestWinStreak(), PlayerStats.START_ELO, Map.of()));
    }

    /** Counts a finished unranked duel. */
    public void recordResult(Player winner, Player loser) {
        record(new Result(winner.getUniqueId(), winner.getName(), loser.getUniqueId(), loser.getName(), null, 0));
    }

    /**
     * Counts a finished ranked duel: the winner takes {@code eloChange} of the loser's rating in {@code kit}.
     * Updates the cache at once and the database in one transaction.
     */
    public void recordResult(Player winner, Player loser, String kit, int eloChange) {
        record(new Result(winner.getUniqueId(), winner.getName(), loser.getUniqueId(), loser.getName(), kit, eloChange));
    }

    private void record(Result result) {
        online.compute(result.winner(), (uuid, stats) -> {
            PlayerStats won = (stats == null ? PlayerStats.empty(result.winnerName()) : stats).win(result.winnerName());
            return result.kit() == null ? won : won.rated(result.kit(), result.eloChange(), true);
        });
        online.compute(result.loser(), (uuid, stats) -> {
            PlayerStats lost = (stats == null ? PlayerStats.empty(result.loserName()) : stats).loss(result.loserName());
            return result.kit() == null ? lost : lost.rated(result.kit(), result.eloChange(), false);
        });
        write(result);
    }

    /** Stats by player name, with their ratings; online players come from the cache. */
    public CompletableFuture<Optional<PlayerStats>> find(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player != null && online.containsKey(player.getUniqueId())) {
            return CompletableFuture.completedFuture(Optional.of(online.get(player.getUniqueId())));
        }
        return schema.thenCompose(ignored -> db.transaction(connection -> find(connection, FIND_BY_NAME, name)));
    }

    /** The {@code limit} players with the most wins. */
    public CompletableFuture<List<PlayerStats>> top(int limit) {
        return schema.thenCompose(ignored -> db.query(TOP, row -> read(row, Map.of()), limit));
    }

    /** The {@code limit} best rated players in {@code kit}. */
    public CompletableFuture<List<Ranking>> topByElo(String kit, int limit) {
        return schema.thenCompose(ignored -> db.query(TOP_KIT, row -> new Ranking(row.getString("name"), row.getInt("elo"),
                row.getInt("wins"), row.getInt("losses")), kit, limit));
    }

    /** The {@code limit} players with the best overall rating: the average over the {@code kits} they played ranked. */
    public CompletableFuture<List<Ranking>> topByElo(Collection<String> kits, int limit) {
        if (kits.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String sql = TOP_OVERALL.formatted(String.join(", ", kits.stream().map(kit -> "?").toList()));
        List<Object> params = new ArrayList<>(kits);
        params.add(limit);
        // AVG is a decimal on MySQL and a real on SQLite; both read as a double, rounded like PlayerStats.overallElo.
        return schema.thenCompose(ignored -> db.query(sql, row -> new Ranking(row.getString("name"),
                (int) Math.round(row.getDouble("avg_elo")), row.getInt("total_wins"), row.getInt("total_losses")), params.toArray()));
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
            if (result.kit() != null) {
                rate(connection, result.winner(), result.kit(), result.eloChange(), true);
                rate(connection, result.loser(), result.kit(), -result.eloChange(), false);
            }
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

    /** Moves the player's rating in {@code kit} by {@code change}, starting it if this is their first ranked duel with it. */
    private static void rate(Connection connection, UUID player, String kit, int change, boolean won) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RECORD_RATING)) {
            statement.setInt(1, change);
            statement.setInt(2, change);
            statement.setInt(3, change);
            statement.setInt(4, won ? 1 : 0);
            statement.setInt(5, won ? 0 : 1);
            statement.setString(6, player.toString());
            statement.setString(7, kit);
            if (statement.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_RATING)) {
            statement.setString(1, kit);
            statement.setInt(2, change);
            statement.setInt(3, won ? 1 : 0);
            statement.setInt(4, won ? 0 : 1);
            statement.setInt(5, change);
            statement.setInt(6, change);
            statement.setString(7, player.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Creates the tables, and adds the columns tables made by older versions lack (elo, peak); reads the columns
     * the same way on SQLite and MySQL.
     */
    private static Integer createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE);
            statement.executeUpdate(CREATE_RATINGS);
            int changed = 0;
            if (!hasColumn(statement, "duels_stats", "elo")) {
                changed += statement.executeUpdate(ADD_ELO);
            }
            if (!hasColumn(statement, "duels_ratings", "peak")) {
                statement.executeUpdate(ADD_PEAK);
                changed += statement.executeUpdate(START_PEAKS);
            }
            return changed;
        }
    }

    /** @param table a constant table name, never input */
    private static boolean hasColumn(Statement statement, String table, String column) throws SQLException {
        try (ResultSet none = statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData columns = none.getMetaData();
            for (int i = 1; i <= columns.getColumnCount(); i++) {
                if (columns.getColumnName(i).equalsIgnoreCase(column)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A player's stats and ratings, found by {@code sql} with {@code key} (a uuid or a name). */
    private static Optional<PlayerStats> find(Connection connection, String sql, String key) throws SQLException {
        String uuid;
        PlayerStats stats;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                uuid = row.getString("uuid");
                stats = read(row, Map.of());
            }
        }
        Map<String, KitRating> ratings = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(FIND_RATINGS)) {
            statement.setString(1, uuid);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    ratings.put(row.getString("kit"), new KitRating(row.getInt("elo"), row.getInt("wins"), row.getInt("losses"),
                            row.getInt("peak")));
                }
            }
        }
        return Optional.of(withRatings(stats, ratings));
    }

    private static PlayerStats read(ResultSet row, Map<String, KitRating> ratings) throws SQLException {
        return new PlayerStats(row.getString("name"), row.getInt("wins"), row.getInt("losses"),
                row.getInt("win_streak"), row.getInt("best_win_streak"), row.getInt("elo"), ratings);
    }

    private static PlayerStats withRatings(PlayerStats stats, Map<String, KitRating> ratings) {
        return new PlayerStats(stats.name(), stats.wins(), stats.losses(), stats.winStreak(), stats.bestWinStreak(),
                stats.legacyElo(), ratings);
    }
}
