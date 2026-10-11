package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kills and deaths in each kit's free-for-all, in the {@code duels_ffa_stats} table, written as deltas so servers
 * sharing a MySQL database add up. Main thread only.
 */
public final class FfaStats {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_ffa_stats (
                uuid   VARCHAR(36) NOT NULL,
                kit    VARCHAR(64) NOT NULL,
                name   VARCHAR(16) NOT NULL,
                kills  INT NOT NULL DEFAULT 0,
                deaths INT NOT NULL DEFAULT 0,
                best_streak INT NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, kit)
            )""";
    private static final String ADD = """
            UPDATE duels_ffa_stats SET name = ?, kills = kills + ?, deaths = deaths + ?,
                best_streak = CASE WHEN ? > best_streak THEN ? ELSE best_streak END
            WHERE uuid = ? AND kit = ?""";
    private static final String INSERT = "INSERT INTO duels_ffa_stats (uuid, kit, name, kills, deaths, best_streak) VALUES (?, ?, ?, ?, ?, ?)";
    // ponytail: leaderboards scan the table; index (kit, kills) if it ever holds millions of rows
    private static final String TOP_KIT = "SELECT name, kills, deaths FROM duels_ffa_stats WHERE kit = ? ORDER BY kills DESC, deaths ASC LIMIT ?";
    private static final String TOP = """
            SELECT MAX(name) AS name, SUM(kills) AS kills, SUM(deaths) AS deaths FROM duels_ffa_stats
            GROUP BY uuid ORDER BY kills DESC, deaths ASC LIMIT ?""";
    private static final String OF = "SELECT kit, kills, deaths, best_streak FROM duels_ffa_stats WHERE uuid = ? ORDER BY kit";

    /** One player's kills, deaths and most kills without dying in one kit. */
    public record Entry(String kit, int kills, int deaths, int bestStreak) {

        /** Kills per death; the kills themselves before a first death. */
        public double ratio() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }
    }

    private final Logger logger;
    private final Database db;
    private final CompletableFuture<Integer> schema;

    public FfaStats(Plugin plugin, Database db) {
        this.logger = plugin.getLogger();
        this.db = db;
        this.schema = db.update(CREATE);
    }

    /**
     * Adds to {@code player}'s counts in {@code kit} without waiting, raising their best streak to {@code streak}; a
     * failed write is logged and dropped.
     */
    public void add(UUID player, String name, String kit, int kills, int deaths, int streak) {
        schema.thenCompose(ignored -> db.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(ADD)) {
                update.setString(1, name);
                update.setInt(2, kills);
                update.setInt(3, deaths);
                update.setInt(4, streak);
                update.setInt(5, streak);
                update.setString(6, player.toString());
                update.setString(7, kit);
                if (update.executeUpdate() > 0) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setString(1, player.toString());
                insert.setString(2, kit);
                insert.setString(3, name);
                insert.setInt(4, kills);
                insert.setInt(5, deaths);
                insert.setInt(6, streak);
                insert.executeUpdate();
            }
            return null;
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save free-for-all stats of " + player + " in " + kit, error);
            return null;
        });
    }

    /**
     * The {@code limit} players with the most kills in {@code kit}'s free-for-all, or in all of them for a null kit, as
     * rankings whose wins are kills and losses deaths.
     */
    public CompletableFuture<List<Ranking>> top(String kit, int limit) {
        Database.RowMapper<Ranking> read = row -> new Ranking(row.getString("name"), 0, row.getInt("kills"), row.getInt("deaths"));
        return schema.thenCompose(ignored -> kit == null ? db.query(TOP, read, limit) : db.query(TOP_KIT, read, kit, limit));
    }

    /** {@code player}'s counts in every kit they fought a free-for-all in, by kit name. */
    public CompletableFuture<List<Entry>> of(UUID player) {
        return schema.thenCompose(ignored -> db.query(OF, row -> new Entry(row.getString("kit"), row.getInt("kills"),
                row.getInt("deaths"), row.getInt("best_streak")), player.toString()));
    }
}
