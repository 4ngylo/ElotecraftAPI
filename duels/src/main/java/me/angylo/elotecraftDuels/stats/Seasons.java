package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Rating seasons. Ending one copies every kit rating into {@code duels_seasons} under the season's number, then
 * resets all ratings: everyone starts the next season at {@link PlayerStats#START_ELO} in every kit. The season
 * running is one past the last one archived. Works on SQLite and MySQL.
 */
public final class Seasons {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_seasons (
                season   INT NOT NULL,
                uuid     VARCHAR(36) NOT NULL,
                name     VARCHAR(16) NOT NULL,
                kit      VARCHAR(64) NOT NULL,
                elo      INT NOT NULL,
                peak     INT NOT NULL,
                wins     INT NOT NULL,
                losses   INT NOT NULL,
                ended_at BIGINT NOT NULL,
                PRIMARY KEY (season, uuid, kit)
            )""";
    private static final String CURRENT = "SELECT COALESCE(MAX(season), 0) + 1 AS season FROM duels_seasons";
    private static final String ARCHIVE = """
            INSERT INTO duels_seasons (season, uuid, name, kit, elo, peak, wins, losses, ended_at)
            SELECT ?, r.uuid, s.name, r.kit, r.elo, r.peak, r.wins, r.losses, ? FROM duels_ratings r JOIN duels_stats s ON s.uuid = r.uuid""";
    /** {@code %s} is one {@code ?} per kit. */
    private static final String STANDINGS = """
            SELECT r.uuid, s.name, AVG(r.elo) AS avg_elo FROM duels_ratings r JOIN duels_stats s ON s.uuid = r.uuid
            WHERE r.kit IN (%s) GROUP BY r.uuid, s.name""";
    private static final String RESET_RATINGS = "DELETE FROM duels_ratings";
    /** The one rating of old versions, where a kit's first rating starts from: back to the start for everyone. */
    private static final String RESET_START = "UPDATE duels_stats SET elo = " + PlayerStats.START_ELO;
    private static final String TOP = """
            SELECT name, AVG(elo) AS avg_elo, SUM(wins) AS total_wins, SUM(losses) AS total_losses FROM duels_seasons
            WHERE season = ? GROUP BY uuid, name ORDER BY avg_elo DESC, total_wins DESC LIMIT ?""";

    /** A player's overall rating at the end of a season: the average over the kits that exist. */
    public record Standing(UUID player, String name, int elo) {
    }

    /** @param ratings how many kit ratings were archived */
    public record Ended(int season, int ratings, List<Standing> standings) {
    }

    private final Database db;
    private final CompletableFuture<Integer> schema;
    private volatile int current = 1;

    /** @param statsReady the stats tables, which an end of season reads */
    public Seasons(Plugin plugin, Database db, CompletableFuture<?> statsReady) {
        this.db = db;
        this.schema = statsReady.thenCompose(ignored -> db.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE);
            }
            return readCurrent(connection);
        })).whenComplete((season, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not create the duels_seasons table", error);
            } else {
                current = season;
            }
        });
    }

    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /** The season running now; safe from any thread. */
    public int current() {
        return current;
    }

    /**
     * Ends the season running: archives and resets every rating in one transaction. Cannot be undone. The standings
     * are the overall ratings over {@code kits}, for the season rewards; completes on the main thread.
     */
    public CompletableFuture<Ended> end(Collection<String> kits) {
        return schema.thenCompose(ignored -> db.transaction(connection -> {
            int season = readCurrent(connection);
            int archived;
            try (PreparedStatement archive = connection.prepareStatement(ARCHIVE)) {
                archive.setInt(1, season);
                archive.setLong(2, System.currentTimeMillis());
                archived = archive.executeUpdate();
            }
            List<Standing> standings = standings(connection, kits);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(RESET_RATINGS);
                statement.executeUpdate(RESET_START);
            }
            return new Ended(season, archived, standings);
        })).thenApply(ended -> {
            current = ended.season() + 1;
            return ended;
        });
    }

    /** The {@code limit} best overall ratings of an ended season, averaged over every kit archived. */
    public CompletableFuture<List<Ranking>> top(int season, int limit) {
        return schema.thenCompose(ignored -> db.query(TOP, row -> new Ranking(row.getString("name"),
                (int) Math.round(row.getDouble("avg_elo")), row.getInt("total_wins"), row.getInt("total_losses")), season, limit));
    }

    private static int readCurrent(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(CURRENT)) {
            return row.next() ? row.getInt("season") : 1;
        }
    }

    private static List<Standing> standings(Connection connection, Collection<String> kits) throws SQLException {
        List<Standing> standings = new ArrayList<>();
        if (kits.isEmpty()) {
            return standings;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                STANDINGS.formatted(String.join(", ", kits.stream().map(kit -> "?").toList())))) {
            int index = 1;
            for (String kit : kits) {
                statement.setString(index++, kit);
            }
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    standings.add(new Standing(UUID.fromString(row.getString("uuid")), row.getString("name"),
                            (int) Math.round(row.getDouble("avg_elo"))));
                }
            }
        }
        return standings;
    }
}
