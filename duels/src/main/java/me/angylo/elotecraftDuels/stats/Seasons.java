package me.angylo.elotecraftDuels.stats;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Rating seasons. Ending one copies every kit rating into {@code duels_seasons} under the season's number, then
 * resets all ratings: everyone starts the next season at {@link PlayerStats#START_ELO} in every kit. The season
 * running is one past the last one archived. {@code duels_season_info} keeps when each season started, its planned
 * end, whether it ends by itself then, and its name. Works on SQLite and MySQL.
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
    private static final String CREATE_INFO = """
            CREATE TABLE IF NOT EXISTS duels_season_info (
                season      INT PRIMARY KEY,
                started_at  BIGINT NOT NULL,
                start_known INT NOT NULL DEFAULT 1,
                ends_at     BIGINT NOT NULL DEFAULT 0,
                auto_end    INT NOT NULL DEFAULT 0,
                name        VARCHAR(128) NOT NULL DEFAULT ''
            )""";
    private static final String CURRENT = "SELECT COALESCE(MAX(season), 0) + 1 AS season FROM duels_seasons";
    /** A season with no ratings archives nothing, so its end is only seen here: the next season's info row. */
    private static final String LAST_INFO = "SELECT COALESCE(MAX(season), 0) AS season FROM duels_season_info";
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
    private static final String TOP_KIT = """
            SELECT name, elo, wins, losses FROM duels_seasons WHERE season = ? AND kit = ? ORDER BY elo DESC, wins DESC LIMIT ?""";
    private static final String INFO_COLUMNS = "season, started_at, start_known, ends_at, auto_end, name";
    private static final String READ_INFO = "SELECT " + INFO_COLUMNS + " FROM duels_season_info WHERE season = ?";
    private static final String ALL_INFO = "SELECT " + INFO_COLUMNS + " FROM duels_season_info";
    private static final String INSERT_INFO = "INSERT INTO duels_season_info (" + INFO_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)";
    private static final String LAST_END = "SELECT MAX(ended_at) AS ended_at FROM duels_seasons WHERE season = ?";
    private static final String SET_ENDS = "UPDATE duels_season_info SET ends_at = ? WHERE season = ?";
    private static final String SET_AUTO = "UPDATE duels_season_info SET auto_end = ? WHERE season = ?";
    private static final String SET_NAME = "UPDATE duels_season_info SET name = ? WHERE season = ?";
    // Every ranked duel gives one win, so the wins of a season's ratings count its ranked duels.
    private static final String SUMMARIES = """
            SELECT season, COUNT(DISTINCT uuid) AS players, SUM(wins) AS duels, MAX(ended_at) AS ended_at FROM duels_seasons
            GROUP BY season ORDER BY season""";
    private static final String LIVE_ROWS = """
            SELECT r.uuid, s.name, r.kit, r.elo, r.peak, r.wins, r.losses FROM duels_ratings r JOIN duels_stats s ON s.uuid = r.uuid""";
    private static final String ARCHIVED_ROWS = "SELECT uuid, name, kit, elo, peak, wins, losses FROM duels_seasons WHERE season = ?";

    /** A player's overall rating at the end of a season: the average over the kits that exist. */
    public record Standing(UUID player, String name, int elo) {
    }

    /** @param ratings how many kit ratings were archived */
    public record Ended(int season, int ratings, List<Standing> standings) {
    }

    /**
     * What is known of a season.
     *
     * @param startKnown false when the season began before starts were kept: {@code startedAt} is when they began to be
     * @param endsAt     the planned end, 0 for none
     * @param name       MiniMessage, empty for none
     */
    public record Info(int season, long startedAt, boolean startKnown, long endsAt, boolean autoEnd, String name) {

        public boolean planned() {
            return endsAt > 0;
        }
    }

    /**
     * An ended season.
     *
     * @param startedAt 0 if unknown
     * @param name      MiniMessage, empty for none
     */
    public record Summary(int season, String name, long startedAt, long endedAt, int players, int duels) {
    }

    /** One kit rating of a season: live for the season running, archived for an ended one. */
    public record Rating(UUID player, String name, String kit, int elo, int peak, int wins, int losses) {
    }

    /** How much a kit was played ranked in a season. */
    public record KitUse(String kit, int players, int duels) {
    }

    /** A season's numbers, from its ratings. */
    public record Totals(int players, int duels, int averageElo, List<KitUse> kits) {

        /** @param ratings a season's ratings; kits by ranked duels, most first */
        public static Totals of(List<Rating> ratings) {
            Set<UUID> players = new HashSet<>();
            Map<String, Set<UUID>> kitPlayers = new LinkedHashMap<>();
            Map<String, Integer> kitDuels = new HashMap<>();
            long eloSum = 0;
            int duels = 0;
            for (Rating rating : ratings) {
                players.add(rating.player());
                kitPlayers.computeIfAbsent(rating.kit(), kit -> new HashSet<>()).add(rating.player());
                kitDuels.merge(rating.kit(), rating.wins(), Integer::sum);
                eloSum += rating.elo();
                duels += rating.wins();
            }
            List<KitUse> kits = kitPlayers.entrySet().stream()
                    .map(entry -> new KitUse(entry.getKey(), entry.getValue().size(), kitDuels.get(entry.getKey())))
                    .sorted(Comparator.comparingInt(KitUse::duels).thenComparingInt(KitUse::players).reversed())
                    .toList();
            int average = ratings.isEmpty() ? PlayerStats.START_ELO : (int) Math.round((double) eloSum / ratings.size());
            return new Totals(players.size(), duels, average, kits);
        }
    }

    private record Ending(Ended ended, Info next) {
    }

    private final Database db;
    private final Supplier<Duration> defaultLength;
    private final CompletableFuture<Integer> schema;
    private volatile Info current = new Info(1, System.currentTimeMillis(), true, 0, false, "");

    /**
     * @param statsReady    the stats tables, which an end of season reads
     * @param defaultLength the planned length of a season that starts with no info yet, or when one ends; zero for none
     */
    public Seasons(Plugin plugin, Database db, CompletableFuture<?> statsReady, Supplier<Duration> defaultLength) {
        this.db = db;
        this.defaultLength = defaultLength;
        this.schema = statsReady.thenCompose(ignored -> db.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE);
                statement.executeUpdate(CREATE_INFO);
            }
            int season = readCurrent(connection);
            Optional<Info> stored = info(connection, season);
            return stored.isPresent() ? stored.get() : startInfo(connection, season);
        })).thenApply(info -> {
            current = info;
            return info.season();
        }).whenComplete((season, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not create the duels_seasons tables", error);
            }
        });
    }

    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /** The season running now; safe from any thread. */
    public int current() {
        return current.season();
    }

    /** What is known of the season running; safe from any thread. */
    public Info info() {
        return current;
    }

    /**
     * Ends the season running: archives and resets every rating, and starts the next season (keeping auto end, with
     * the default planned length) in one transaction. Cannot be undone. The standings are the overall ratings over
     * {@code kits}, for the season rewards; completes on the main thread.
     */
    public CompletableFuture<Ended> end(Collection<String> kits) {
        boolean autoEnd = current.autoEnd();
        Duration length = defaultLength.get();
        return schema.thenCompose(ignored -> db.transaction(connection -> {
            int season = readCurrent(connection);
            long now = System.currentTimeMillis();
            int archived;
            try (PreparedStatement archive = connection.prepareStatement(ARCHIVE)) {
                archive.setInt(1, season);
                archive.setLong(2, now);
                archived = archive.executeUpdate();
            }
            List<Standing> standings = standings(connection, kits);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(RESET_RATINGS);
                statement.executeUpdate(RESET_START);
            }
            Info next = new Info(season + 1, now, true, length.isPositive() ? now + length.toMillis() : 0, autoEnd, "");
            insert(connection, next);
            return new Ending(new Ended(season, archived, standings), next);
        })).thenApply(ending -> {
            current = ending.next();
            return ending.ended();
        });
    }

    /**
     * Sets the planned end of the season running (0 for none); completes on the main thread with the season running
     * then (unchanged if a season ended meanwhile).
     */
    public CompletableFuture<Info> schedule(long endsAt) {
        int season = current.season();
        return change(SET_ENDS, endsAt, season).thenApply(ignored -> update(season,
                info -> new Info(season, info.startedAt(), info.startKnown(), endsAt, info.autoEnd(), info.name())));
    }

    /** Turns auto end of the season running on or off; completes like {@link #schedule}. */
    public CompletableFuture<Info> autoEnd(boolean on) {
        int season = current.season();
        return change(SET_AUTO, on ? 1 : 0, season).thenApply(ignored -> update(season,
                info -> new Info(season, info.startedAt(), info.startKnown(), info.endsAt(), on, info.name())));
    }

    /** Applies {@code change} to the season running if it is still {@code season}; returns the season running. */
    private Info update(int season, UnaryOperator<Info> change) {
        Info info = current;
        if (info.season() == season) {
            current = change.apply(info);
        }
        return current;
    }

    /** Names season {@code season} (MiniMessage; empty for none), running or ended; completes on the main thread. */
    public CompletableFuture<Void> name(int season, String name) {
        return schema.thenCompose(ignored -> db.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SET_NAME)) {
                statement.setString(1, name);
                statement.setInt(2, season);
                if (statement.executeUpdate() == 0) {
                    insert(connection, new Info(season, 0, false, 0, false, name));
                }
            }
            return null;
        })).thenAccept(ignored -> update(season,
                info -> new Info(season, info.startedAt(), info.startKnown(), info.endsAt(), info.autoEnd(), name)));
    }

    /** Every ended season, oldest first. */
    public CompletableFuture<List<Summary>> ended() {
        return schema.thenCompose(ignored -> db.transaction(connection -> {
            Map<Integer, Info> infos = new HashMap<>();
            try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(ALL_INFO)) {
                while (row.next()) {
                    Info info = readInfo(row);
                    infos.put(info.season(), info);
                }
            }
            List<Summary> summaries = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(SUMMARIES)) {
                long previousEnd = 0;
                while (row.next()) {
                    int season = row.getInt("season");
                    Info info = infos.get(season);
                    // A season without a known start began when the one before it ended.
                    long started = info != null && info.startKnown() ? info.startedAt() : previousEnd;
                    long ended = row.getLong("ended_at");
                    summaries.add(new Summary(season, info == null ? "" : info.name(), started, ended, row.getInt("players"),
                            row.getInt("duels")));
                    previousEnd = ended;
                }
            }
            return summaries;
        }));
    }

    /** One ended season, if it exists. */
    public CompletableFuture<Optional<Summary>> ended(int season) {
        return ended().thenApply(all -> all.stream().filter(summary -> summary.season() == season).findFirst());
    }

    /** Every kit rating of {@code season}: the live ratings for the season running, the archived ones otherwise. */
    public CompletableFuture<List<Rating>> ratings(int season) {
        return season == current() ? schema.thenCompose(ignored -> db.query(LIVE_ROWS, Seasons::readRating))
                : schema.thenCompose(ignored -> db.query(ARCHIVED_ROWS, Seasons::readRating, season));
    }

    /** The overall ratings of the season running over {@code kits}, as {@link #end} would pay rewards on them. */
    public CompletableFuture<List<Standing>> standings(Collection<String> kits) {
        return schema.thenCompose(ignored -> db.transaction(connection -> standings(connection, kits)));
    }

    /** The {@code limit} best overall ratings of an ended season, averaged over every kit archived. */
    public CompletableFuture<List<Ranking>> top(int season, int limit) {
        return schema.thenCompose(ignored -> db.query(TOP, row -> new Ranking(row.getString("name"),
                (int) Math.round(row.getDouble("avg_elo")), row.getInt("total_wins"), row.getInt("total_losses")), season, limit));
    }

    /** The {@code limit} best ratings in {@code kit} of an ended season. */
    public CompletableFuture<List<Ranking>> top(int season, String kit, int limit) {
        return schema.thenCompose(ignored -> db.query(TOP_KIT, row -> new Ranking(row.getString("name"), row.getInt("elo"),
                row.getInt("wins"), row.getInt("losses")), season, kit, limit));
    }

    private CompletableFuture<Integer> change(String sql, Object value, int season) {
        return schema.thenCompose(ignored -> db.update(sql, value, season));
    }

    /**
     * Info for a season that has none yet: it started when the season before ended, or, if none has ended, now (marked
     * as not known), with the default planned length.
     */
    private Info startInfo(Connection connection, int season) throws SQLException {
        long previousEnd = 0;
        try (PreparedStatement statement = connection.prepareStatement(LAST_END)) {
            statement.setInt(1, season - 1);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    previousEnd = row.getLong("ended_at");
                }
            }
        }
        boolean known = previousEnd > 0;
        long started = known ? previousEnd : System.currentTimeMillis();
        Duration length = defaultLength.get();
        Info info = new Info(season, started, known, length.isPositive() ? started + length.toMillis() : 0, false, "");
        insert(connection, info);
        return info;
    }

    private static Optional<Info> info(Connection connection, int season) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ_INFO)) {
            statement.setInt(1, season);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readInfo(row)) : Optional.empty();
            }
        }
    }

    private static void insert(Connection connection, Info info) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_INFO)) {
            statement.setInt(1, info.season());
            statement.setLong(2, info.startedAt());
            statement.setInt(3, info.startKnown() ? 1 : 0);
            statement.setLong(4, info.endsAt());
            statement.setInt(5, info.autoEnd() ? 1 : 0);
            statement.setString(6, info.name());
            statement.executeUpdate();
        }
    }

    private static Info readInfo(ResultSet row) throws SQLException {
        return new Info(row.getInt("season"), row.getLong("started_at"), row.getInt("start_known") != 0, row.getLong("ends_at"),
                row.getInt("auto_end") != 0, row.getString("name"));
    }

    private static Rating readRating(ResultSet row) throws SQLException {
        return new Rating(UUID.fromString(row.getString("uuid")), row.getString("name"), row.getString("kit"), row.getInt("elo"),
                row.getInt("peak"), row.getInt("wins"), row.getInt("losses"));
    }

    /** One past the last season archived, or the last season with info if that is later; at least 1. */
    private static int readCurrent(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int archived;
            try (ResultSet row = statement.executeQuery(CURRENT)) {
                archived = row.next() ? row.getInt("season") : 1;
            }
            try (ResultSet row = statement.executeQuery(LAST_INFO)) {
                return Math.max(archived, row.next() ? row.getInt("season") : 0);
            }
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
