package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
                teammates  VARCHAR(64) NOT NULL DEFAULT '',
                opponents  VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (uuid, ended_at)
            )""";
    /** Tables made before 2v2 duels lack the team columns; their rows are 1v1 duels. */
    private static final String ADD_TEAMMATES = "ALTER TABLE duels_history ADD COLUMN teammates VARCHAR(64) NOT NULL DEFAULT ''";
    private static final String ADD_OPPONENTS = "ALTER TABLE duels_history ADD COLUMN opponents VARCHAR(64) NOT NULL DEFAULT ''";
    private static final String INSERT = """
            INSERT INTO duels_history (uuid, ended_at, opponent, won, kit, arena, ranked, elo_change, seconds, reason, health,
                teammates, opponents)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String COLUMNS = "h.ended_at, h.opponent, h.won, h.kit, h.arena, h.ranked, h.elo_change, h.seconds, h.reason,"
            + " h.health, h.teammates, h.opponents";
    /** {@code %s}: whether the rows are 1v1 or 2v2 duels. */
    private static final String BY_UUID = "SELECT " + COLUMNS + " FROM duels_history h WHERE h.uuid = ? AND %s"
            + " ORDER BY h.ended_at DESC LIMIT ?";
    // Names come from duels_stats, which every player with a finished duel has a row in.
    private static final String BY_NAME = "SELECT " + COLUMNS + " FROM duels_history h JOIN duels_stats s ON s.uuid = h.uuid"
            + " WHERE LOWER(s.name) = LOWER(?) AND %s ORDER BY h.ended_at DESC LIMIT ?";
    private static final String DUELS = "h.teammates = ''";
    private static final String TEAM_DUELS = "h.teammates <> ''";

    /**
     * One finished duel as one player saw it.
     *
     * @param eloChange rating the winner took from the loser; 0 unless ranked
     * @param reason    how it ended: eliminated, forfeit or quit
     * @param health    the winner's health left
     * @param teammates the player's teammates in a 2v2 duel, joined with commas; empty for a 1v1 duel
     * @param opponents every opponent of a 2v2 duel, joined with commas; empty for a 1v1 duel
     */
    public record Entry(long endedAt, String opponent, boolean won, String kit, String arena, boolean ranked,
                        int eloChange, int seconds, String reason, double health, String teammates, String opponents) {

        /** Who the player fought: the opponent, or every opponent of a 2v2 duel. */
        public String against() {
            return opponents.isEmpty() ? opponent : opponents;
        }
    }

    /** A finished 2v2 duel, written as one row for each fighter; the names in team order. */
    public record TeamDuel(List<UUID> winners, List<String> winnerNames, List<UUID> losers, List<String> loserNames,
                           long endedAt, String kit, String arena, boolean ranked, int eloChange, int seconds, String reason,
                           double health) {
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
        this.schema = db.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE);
                if (!StatsService.hasColumn(statement, "duels_history", "teammates")) {
                    statement.executeUpdate(ADD_TEAMMATES);
                    statement.executeUpdate(ADD_OPPONENTS);
                }
            }
            return 0;
        });
    }

    /** Completes once the table exists. */
    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /** Writes {@code duel} without waiting; a failed write is logged and dropped. */
    public void record(Duel duel) {
        schema.thenCompose(ignored -> db.transaction(connection -> {
            insert(connection, duel.endedAt(), duel.kit(), duel.arena(), duel.ranked(), duel.eloChange(), duel.seconds(),
                    duel.reason(), duel.health(), duel.winner(), duel.loserName(), true, "", "");
            insert(connection, duel.endedAt(), duel.kit(), duel.arena(), duel.ranked(), duel.eloChange(), duel.seconds(),
                    duel.reason(), duel.health(), duel.loser(), duel.winnerName(), false, "", "");
            return null;
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save the duel " + duel.winnerName() + " beat " + duel.loserName()
                    + " to the history", error);
            return null;
        });
    }

    /** Writes {@code duel} without waiting, one row per fighter; a failed write is logged and dropped. */
    public void record(TeamDuel duel) {
        schema.thenCompose(ignored -> db.transaction(connection -> {
            teamRows(connection, duel, duel.winners(), duel.winnerNames(), duel.loserNames(), true);
            teamRows(connection, duel, duel.losers(), duel.loserNames(), duel.winnerNames(), false);
            return null;
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save the 2v2 duel " + duel.winnerNames() + " beat " + duel.loserNames()
                    + " to the history", error);
            return null;
        });
    }

    private static void teamRows(Connection connection, TeamDuel duel, List<UUID> team, List<String> names, List<String> others,
                                 boolean won) throws SQLException {
        for (int i = 0; i < team.size(); i++) {
            String self = names.get(i);
            String teammates = String.join(", ", names.stream().filter(name -> !name.equals(self)).toList());
            // The opponent paired with this fighter in the stats; every opponent goes in opponents.
            insert(connection, duel.endedAt(), duel.kit(), duel.arena(), duel.ranked(), duel.eloChange(), duel.seconds(),
                    duel.reason(), duel.health(), team.get(i), others.get(Math.min(i, others.size() - 1)), won,
                    teammates.isEmpty() ? "-" : teammates, String.join(", ", others));
        }
    }

    /** The {@link #LIMIT} latest 1v1 or 2v2 duels of {@code player}, newest first. */
    public CompletableFuture<List<Entry>> of(UUID player, boolean team) {
        return schema.thenCompose(ignored -> db.query(BY_UUID.formatted(team ? TEAM_DUELS : DUELS), MatchHistory::read,
                player.toString(), LIMIT));
    }

    /** Like {@link #of(UUID, boolean)} for a player known by name, online or not. */
    public CompletableFuture<List<Entry>> of(String name, boolean team) {
        return schema.thenCompose(ignored -> db.query(BY_NAME.formatted(team ? TEAM_DUELS : DUELS), MatchHistory::read, name, LIMIT));
    }

    private static void insert(Connection connection, long endedAt, String kit, String arena, boolean ranked, int eloChange,
                               int seconds, String reason, double health, UUID player, String opponent, boolean won,
                               String teammates, String opponents) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, player.toString());
            statement.setLong(2, endedAt);
            statement.setString(3, opponent);
            statement.setInt(4, won ? 1 : 0);
            statement.setString(5, kit);
            statement.setString(6, arena);
            statement.setInt(7, ranked ? 1 : 0);
            statement.setInt(8, eloChange);
            statement.setInt(9, seconds);
            statement.setString(10, reason);
            statement.setDouble(11, health);
            statement.setString(12, teammates);
            statement.setString(13, opponents);
            statement.executeUpdate();
        }
    }

    private static Entry read(ResultSet row) throws SQLException {
        return new Entry(row.getLong("ended_at"), row.getString("opponent"), row.getInt("won") == 1, row.getString("kit"),
                row.getString("arena"), row.getInt("ranked") == 1, row.getInt("elo_change"), row.getInt("seconds"),
                row.getString("reason"), row.getDouble("health"), row.getString("teammates"), row.getString("opponents"));
    }
}
