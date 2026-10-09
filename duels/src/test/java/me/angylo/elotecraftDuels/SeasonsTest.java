package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.stats.KitRating;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Peak ratings, the daily ranked limit and rating seasons. */
class SeasonsTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;

    @BeforeEach
    void players() {
        alex = join("Alex");
        steve = join("Steve");
        kit = swordKit();
        readyArena("pit");
    }

    private PlayerStats stored(String name) {
        return await(duels.stats().find(name)).orElseThrow();
    }

    /** Waits until the database has {@code name}'s rating in sword at {@code elo}. */
    private void storedElo(String name, int elo) {
        tickUntil(() -> {
            KitRating rating = await(duels.stats().find(name)).flatMap(stats -> Optional.ofNullable(stats.ratings().get("sword")))
                    .orElse(null);
            return rating != null && rating.elo() == elo;
        });
    }

    @Test
    void thePeakIsTheBestRatingOfTheSeason() {
        duels.stats().recordResult(alex, steve, "sword", 20);
        duels.stats().recordResult(steve, alex, "sword", 5);
        assertEquals(1015, duels.stats().cached(alex.getUniqueId()).orElseThrow().elo("sword"));
        assertEquals(1020, duels.stats().cached(alex.getUniqueId()).orElseThrow().peak("sword"));

        duels.stats().forget(alex.getUniqueId());
        storedElo("Alex", 1015);
        assertEquals(new KitRating(1015, 1, 1, 1020), stored("Alex").ratings().get("sword"));
        assertEquals(new KitRating(985, 1, 1, 1000), stored("Steve").ratings().get("sword"));
    }

    @Test
    void anOldRatingsTableGetsPeaksFromTheRatings() throws SQLException {
        duels.shutdown();
        File file = new File(plugin.getDataFolder(), "duels.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE duels_ratings");
            statement.executeUpdate("""
                    CREATE TABLE duels_ratings (uuid VARCHAR(36) NOT NULL, kit VARCHAR(64) NOT NULL, elo INT NOT NULL,
                        wins INT NOT NULL DEFAULT 0, losses INT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, kit))""");
            statement.executeUpdate("INSERT INTO duels_stats (uuid, name) VALUES ('00000000-0000-0000-0000-000000000001', 'Veteran')");
            statement.executeUpdate("INSERT INTO duels_ratings VALUES ('00000000-0000-0000-0000-000000000001', 'sword', 1234, 5, 1)");
        }

        duels = Duels.start(plugin);
        await(duels.ready());

        assertEquals(new KitRating(1234, 5, 1, 1234), stored("Veteran").ratings().get("sword"));
    }

    @Test
    void theDailyLimitStopsRankedQueuesButNotUnrankedOnes() {
        setConfig("ranked.daily-limit", 1);
        duels.queues().toggle(alex, kit, true);
        duels.queues().toggle(steve, kit, true);
        Match match = duels.matches().matchOf(alex).orElseThrow();
        assertTrue(match.isRanked());
        duels.matches().leave(alex);
        ticks(20 * duels.settings().endDelaySeconds() + 1);

        assertSays(alex, "duel ranked sword", "You played your 1 ranked duels for today.");
        assertSays(alex, "duel queue sword", "Joined the Unranked");
        alex.performCommand("duel leave");
        alex.addAttachment(plugin, "duels.queue.ranked.unlimited", true);
        assertSays(alex, "duel ranked sword", "Joined the Ranked");
    }

    @Test
    void endingASeasonArchivesResetsAndPaysDivisionRewards() {
        // The reward renames the kit to the player, so it can be seen here.
        setConfig("ranked.divisions", List.of(Map.of("name", "Bronze", "min", 0),
                Map.of("name", "Gold", "min", 1010, "season-reward", Map.of("commands", List.of("duels kit setname sword <player>-<season>")))));
        duels.stats().recordResult(alex, steve, "sword", 20);
        storedElo("Steve", 980);
        TestPlayer admin = join("Admin");
        admin.setOp(true);

        assertSays(admin, "duels season end confirm", "Run /duels season end first");
        assertSays(admin, "duels season end", "This ends season 1 for good");
        assertSays(admin, "duels season end confirm", "Season 1 ended: 2 ratings archived, 1 rewards paid.");

        assertEquals("Alex-1", duels.kits().get("sword").orElseThrow().displayName());
        assertEquals(2, duels.seasons().current());
        assertEquals(PlayerStats.START_ELO, duels.stats().cached(alex.getUniqueId()).orElseThrow().elo("sword"));
        assertTrue(stored("Steve").ratings().isEmpty());
        assertEquals(PlayerStats.START_ELO, stored("Steve").legacyElo());
        assertSays(admin, "duel top season 1", "#1 Alex · 1020 rating");
        assertSays(steve, "duel top season 2", "Use /duel top season <number>, 1 to 1.");
    }

    @Test
    void theFirstSeasonHasNoArchive() {
        assertEquals(1, duels.seasons().current());
        assertSays(alex, "duel top season 1", "No season has ended yet.");
    }

    @Test
    void aPermissionRaisesTheDailyLimit() {
        setConfig("ranked.daily-limit", 1);
        alex.addAttachment(plugin, "duels.queue.ranked.limit.2", true);
        duels.queues().toggle(alex, kit, true);
        duels.queues().toggle(steve, kit, true);
        duels.matches().leave(alex);
        ticks(20 * duels.settings().endDelaySeconds() + 1);

        assertSays(alex, "duel ranked sword", "Joined the Ranked");
        assertSays(steve, "duel ranked sword", "You played your 1 ranked duels for today.");
    }
}
