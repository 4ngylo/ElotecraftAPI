package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Elo ratings: queue duels move them, challenges do not, and the queue pairs players by rating. */
class RankedTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        kit = swordKit();
        readyArena("pit");
    }

    private void rate(TestPlayer player, int elo) {
        duels.stats().cache(player.getUniqueId(), new PlayerStats(player.getName(), 0, 0, 0, 0, elo, Map.of()));
    }

    private Match fightStarted(TestPlayer player) {
        Match match = duels.matches().matchOf(player).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    @Test
    void eloChangeRewardsUpsetsMore() {
        assertEquals(16, PlayerStats.eloChange(1000, 1000, 32));
        assertEquals(3, PlayerStats.eloChange(1400, 1000, 32));
        assertEquals(29, PlayerStats.eloChange(1000, 1400, 32));
        assertEquals(1, PlayerStats.eloChange(3000, 1000, 32));
    }

    @Test
    void aQueueDuelMovesBothRatingsAndIsSaved() {
        duels.queues().toggle(alex, kit, true);
        duels.queues().toggle(steve, kit, true);
        assertTrue(fightStarted(alex).isRanked());

        steve.simulateDamage(100, alex);

        assertEquals(1016, duels.stats().elo(alex.getUniqueId(), "sword"));
        assertEquals(984, duels.stats().elo(steve.getUniqueId(), "sword"));
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("rating +16 · 1016")));
        assertTrue(messages(steve).stream().anyMatch(line -> line.contains("rating -16 · 984")));
        tickUntil(() -> await(duels.stats().topByElo("sword", 2)).getFirst().elo() == 1016);
        assertEquals(984, await(duels.stats().topByElo("sword", 2)).getLast().elo());
    }

    @Test
    void forfeitingARankedDuelCostsRating() {
        duels.queues().toggle(alex, kit, true);
        duels.queues().toggle(steve, kit, true);
        fightStarted(alex);

        assertTrue(duels.matches().leave(alex));

        assertEquals(984, duels.stats().elo(alex.getUniqueId(), "sword"));
        assertEquals(1016, duels.stats().elo(steve.getUniqueId(), "sword"));
    }

    @Test
    void challengesAreUnranked() {
        assertTrue(duels.matches().start(alex, steve, kit, duels.arenas().get("pit").orElseThrow()));
        assertFalse(fightStarted(alex).isRanked());

        steve.simulateDamage(100, alex);

        assertEquals(1, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
        assertEquals(1000, duels.stats().elo(alex.getUniqueId(), "sword"));
        assertEquals(1000, duels.stats().elo(steve.getUniqueId(), "sword"));
    }

    @Test
    void farRatingsWaitUntilTheRangeGrows() {
        setConfig("ranked.range", 100);
        setConfig("ranked.range-growth", 100);
        rate(alex, 1500);
        duels.queues().toggle(alex, kit, true);
        duels.queues().toggle(steve, kit, true);

        ticks(20 * 3);
        assertFalse(duels.matches().isBusy(alex));

        ticks(20 * 2);
        assertTrue(duels.matches().isBusy(alex));
        assertTrue(duels.matches().isBusy(steve));
    }

    @Test
    void theClosestRatedOpponentIsPreferred() {
        TestPlayer sam = join("Sam");
        rate(alex, 1500);
        rate(sam, 1050);
        duels.queues().toggle(alex, kit, true);
        duels.queues().toggle(steve, kit, true);
        duels.queues().toggle(sam, kit, true);

        assertTrue(duels.matches().isBusy(steve));
        assertTrue(duels.matches().isBusy(sam));
        assertFalse(duels.matches().isBusy(alex));
        assertEquals(new QueueManager.QueueId("sword", true), duels.queues().queued(alex.getUniqueId()).orElseThrow());
    }

    @Test
    void theUnrankedQueueIgnoresRatingsAndLeavesThemAlone() {
        rate(alex, 1500);
        duels.queues().toggle(alex, kit, false);
        duels.queues().toggle(steve, kit, false);
        assertFalse(fightStarted(alex).isRanked());

        steve.simulateDamage(100, alex);

        assertEquals(1, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
        assertEquals(1500, duels.stats().elo(alex.getUniqueId(), "sword"));
        assertEquals(1000, duels.stats().elo(steve.getUniqueId(), "sword"));
    }

    @Test
    void playersInDifferentQueuesOfAKitAreNotPaired() {
        duels.queues().toggle(alex, kit, false);
        duels.queues().toggle(steve, kit, true);
        ticks(20 * 2);

        assertFalse(duels.matches().isBusy(alex));
        assertEquals(1, duels.queues().size("sword", false));
        assertEquals(1, duels.queues().size("sword", true));

        duels.queues().toggle(alex, kit, true);

        assertTrue(duels.matches().isBusy(alex));
        assertTrue(duels.matches().matchOf(alex).orElseThrow().isRanked());
        assertEquals(0, duels.queues().size("sword", false));
    }

    @Test
    void anOldTableGetsTheEloColumn() throws SQLException {
        duels.shutdown();
        File file = new File(plugin.getDataFolder(), "duels.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE duels_stats");
            statement.executeUpdate("""
                    CREATE TABLE duels_stats (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL,
                        wins INT NOT NULL DEFAULT 0, losses INT NOT NULL DEFAULT 0,
                        win_streak INT NOT NULL DEFAULT 0, best_win_streak INT NOT NULL DEFAULT 0)""");
            statement.executeUpdate("INSERT INTO duels_stats VALUES ('00000000-0000-0000-0000-000000000001', 'Veteran', 7, 2, 0, 3)");
        }

        duels = Duels.start(plugin);
        await(duels.ready());

        PlayerStats veteran = await(duels.stats().find("Veteran")).orElseThrow();
        assertEquals(7, veteran.wins());
        assertEquals(PlayerStats.START_ELO, veteran.legacyElo());
    }
}
