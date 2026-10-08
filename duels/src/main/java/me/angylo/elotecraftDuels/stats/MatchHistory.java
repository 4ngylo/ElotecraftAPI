package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finished duels in the {@code duels_history} table, one row per player, so a player's history is read
 * through the primary key on SQLite and MySQL alike. Rows are never pruned. Main thread only.
 */
public final class MatchHistory {

    /** How many duels {@link #of} returns at most. */
    public static final int LIMIT = 50;

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_history (
                uuid       VARCHAR(36) NOT NULL,
                ended_at   BIGINT NOT NULL,
                opponent   VARCHAR(16) NOT NULL,
                won        INT NOT NULL,
                kit        VARCHAR(64) NOT NULL,
                arena      VARCHAR(64) NOT NULL,
                ranked     INT NOT NULL,
                elo_change INT NOT NULL,
                seconds    INT NOT NULL,
                reason     VARCHAR(16) NOT NULL,
                health     DOUBLE NOT NULL,
                PRIMARY KEY (uuid, ended_at)
            )""";
    private static final String INSERT = """
            INSERT INTO duels_history (uuid, ended_at, opponent, won, kit, arena, ranked, elo_change, seconds, reason, health)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String COLUMNS = "h.ended_at, h.opponent, h.won, h.kit, h.arena, h.ranked, h.elo_change, h.seconds, h.reason, h.health";
    private static final String BY_UUID = "SELECT " + COLUMNS + " FROM duels_history h WHERE h.uuid = ? ORDER BY h.ended_at DESC LIMIT ?";
    // Names come from duels_stats, which every player with a finished duel has a row in.
    private static final String BY_NAME = "SELECT " + COLUMNS + " FROM duels_history h JOIN duels_stats s ON s.uuid = h.uuid"
            + " WHERE LOWER(s.name) = LOWER(?) ORDER BY h.ended_at DESC LIMIT ?";

    /**
     * One finished duel as one player saw it.
     *
     * @param eloChange rating the winner took from the loser; 0 unless ranked
     * @param reason    how it ended: eliminated, forfeit or quit
     * @param health    the winner's health left
     */
    public record Entry(long endedAt, String opponent, boolean won, String kit, String arena, boolean ranked,
                        int eloChange, int seconds, String reason, double health) {
    }

    /** A finished duel, written as one row for each fighter. */
    public record Duel(UUID winner, String winnerName, UUID loser, String loserName, long endedAt, String kit,
                       String arena, boolean ranked, int eloChange, int seconds, String reason, double health) {
    }

    private final Logger logger;
    private final Database db;
    private final CompletableFuture<Integer> schema;

    /** Call after {@link StatsService}'s constructor: lookups by name join its table. */
    public MatchHistory(Plugin plugin, Database db) {
        this.logger = plugin.getLogger();
        this.db = db;
        this.schema = db.update(CREATE);
    }

    /** Completes once the table exists. */
    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /** Writes {@code duel} without waiting; a failed write is logged and dropped. */
    public void record(Duel duel) {
        schema.thenCompose(ignored -> db.transaction(connection -> {
            insert(connection, duel, duel.winner(), duel.loserName(), true);
            insert(connection, duel, duel.loser(), duel.winnerName(), false);
            return null;
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save the duel " + duel.winnerName() + " beat " + duel.loserName()
                    + " to the history", error);
            return null;
        });
    }

    /** The {@link #LIMIT} latest duels of {@code player}, newest first. */
    public CompletableFuture<List<Entry>> of(UUID player) {
        return schema.thenCompose(ignored -> db.query(BY_UUID, MatchHistory::read, player.toString(), LIMIT));
    }

    /** Like {@link #of(UUID)} for a player known by name, online or not. */
    public CompletableFuture<List<Entry>> of(String name) {
        return schema.thenCompose(ignored -> db.query(BY_NAME, MatchHistory::read, name, LIMIT));
    }

    private static void insert(Connection connection, Duel duel, UUID player, String opponent, boolean won) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, player.toString());
            statement.setLong(2, duel.endedAt());
            statement.setString(3, opponent);
            statement.setInt(4, won ? 1 : 0);
            statement.setString(5, duel.kit());
            statement.setString(6, duel.arena());
            statement.setInt(7, duel.ranked() ? 1 : 0);
            statement.setInt(8, duel.eloChange());
            statement.setInt(9, duel.seconds());
            statement.setString(10, duel.reason());
            statement.setDouble(11, duel.health());
            statement.executeUpdate();
        }
    }

    private static Entry read(ResultSet row) throws SQLException {
        return new Entry(row.getLong("ended_at"), row.getString("opponent"), row.getInt("won") == 1, row.getString("kit"),
                row.getString("arena"), row.getInt("ranked") == 1, row.getInt("elo_change"), row.getInt("seconds"),
                row.getString("reason"), row.getDouble("health"));
    }
}
