package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.KitRating;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.Ranking;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.inventory.ItemStack;
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
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ratings per kit: stored per kit, started from the old single rating, used by the queue, shown with divisions. */
class RatingsTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit sword;
    private Kit axe;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        sword = swordKit();
        axe = new Kit("axe", "<gray>Axe", Material.IRON_AXE, null, List.of(ItemStack.of(Material.IRON_AXE)), Set.of());
        await(duels.kits().update(axe));
        readyArena("pit");
    }

    /** Alex and Steve queue ranked for {@code kit}; Alex wins the duel. */
    private void alexWinsRanked(Kit kit) {
        duels.queues().toggle(alex, kit, true);
        duels.queues().toggle(steve, kit, true);
        Match match = duels.matches().matchOf(alex).orElseThrow();
        assertTrue(match.isRanked());
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        steve.simulateDamage(100, alex);
    }

    /** {@code name}'s stats as stored, once {@code until} holds for them. */
    private PlayerStats stored(TestPlayer player, Predicate<PlayerStats> until) {
        duels.stats().forget(player.getUniqueId());
        PlayerStats[] found = new PlayerStats[1];
        tickUntil(() -> {
            Optional<PlayerStats> stats = await(duels.stats().find(player.getName()));
            found[0] = stats.orElse(null);
            return found[0] != null && until.test(found[0]);
        });
        return found[0];
    }

    @Test
    void aRankedWinMovesOnlyThatKitsRatingAndIsStored() {
        alexWinsRanked(sword);

        assertEquals(1016, duels.stats().elo(alex.getUniqueId(), "sword"));
        assertEquals(1000, duels.stats().elo(alex.getUniqueId(), "axe"));
        assertEquals(984, duels.stats().elo(steve.getUniqueId(), "sword"));
        PlayerStats alexStored = stored(alex, stats -> stats.ratings().containsKey("sword"));
        assertEquals(new KitRating(1016, 1, 0, 1016), alexStored.ratings().get("sword"));
        assertEquals(Map.of("sword", new KitRating(1016, 1, 0, 1016)), alexStored.ratings());
        // The old single rating no longer moves: ratings in new kits still start from it.
        assertEquals(PlayerStats.START_ELO, alexStored.legacyElo());
        assertEquals(new KitRating(984, 0, 1, 1000), stored(steve, stats -> stats.ratings().containsKey("sword")).ratings().get("sword"));
    }

    @Test
    void unrankedDuelsMoveNoRating() {
        assertTrue(duels.matches().start(alex, steve, sword, duels.arenas().get("pit").orElseThrow()));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);

        steve.simulateDamage(100, alex);

        assertEquals(1, stored(alex, stats -> stats.wins() == 1).wins());
        assertTrue(duels.stats().cached(alex.getUniqueId()).isEmpty() || duels.stats().cached(alex.getUniqueId()).get().ratings().isEmpty());
        assertTrue(stored(alex, stats -> true).ratings().isEmpty());
    }

    @Test
    void aFirstRankedDuelInAKitStartsFromTheOldRating() throws SQLException {
        // Alex's one rating from before ratings were per kit, in the database and in the cache.
        File file = new File(plugin.getDataFolder(), "duels.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO duels_stats VALUES ('" + alex.getUniqueId() + "', 'Alex', 3, 1, 0, 2, 1200)");
        }
        duels.stats().cache(alex.getUniqueId(), new PlayerStats("Alex", 3, 1, 0, 2, 1200, Map.of()));
        // 200 apart: wider than the queue's starting range.
        setConfig("ranked.range", 500);

        alexWinsRanked(sword);

        int change = PlayerStats.eloChange(1200, 1000, duels.settings().ranked().kFactor());
        assertEquals(1200 + change, duels.stats().elo(alex.getUniqueId(), "sword"));
        PlayerStats alexStored = stored(alex, stats -> stats.ratings().containsKey("sword"));
        assertEquals(1200 + change, alexStored.ratings().get("sword").elo());
        assertEquals(1200, alexStored.legacyElo());
        assertEquals(1200, alexStored.elo("axe"));
        assertEquals(4, alexStored.wins());
    }

    @Test
    void theRankedQueuePairsByTheQueuedKitsRating() {
        duels.stats().cache(alex.getUniqueId(), new PlayerStats("Alex", 0, 0, 0, 0, 1000,
                Map.of("sword", new KitRating(1500, 20, 0, 1500))));
        duels.queues().toggle(alex, sword, true);
        duels.queues().toggle(steve, sword, true);
        ticks(20 * 2);
        assertFalse(duels.matches().isBusy(alex));

        duels.queues().toggle(alex, sword, true);
        duels.queues().toggle(steve, sword, true);
        duels.queues().toggle(alex, axe, true);
        duels.queues().toggle(steve, axe, true);

        assertTrue(duels.matches().isBusy(alex));
    }

    @Test
    void divisionsComeFromConfigSortedAndSkipBadEntries() {
        setConfig("ranked.divisions", List.of(Map.of("name", "High", "min", 1100), Map.of("name", "Broken"),
                Map.of("name", "Low", "min", 0)));
        Divisions divisions = duels.settings().ranked().divisions();

        assertEquals(List.of("Low", "High"), divisions.list().stream().map(Divisions.Division::name).toList());
        assertEquals("Low", divisions.of(1099).orElseThrow().name());
        assertEquals("High", divisions.of(1100).orElseThrow().name());
        assertTrue(divisions.of(-5).isEmpty());
        assertTrue(Divisions.NONE.of(2000).isEmpty());
    }

    @Test
    void reachingANewDivisionIsAnnounced() {
        setConfig("ranked.divisions", List.of(Map.of("name", "Low", "min", 0), Map.of("name", "High", "min", 1010)));
        messages(alex);
        messages(steve);

        alexWinsRanked(sword);

        List<String> alexSaw = messages(alex);
        assertTrue(alexSaw.stream().anyMatch(line -> line.contains("Sword rating +16 · 1016 High")), alexSaw.toString());
        assertTrue(alexSaw.stream().anyMatch(line -> line.contains("Promoted to High in Sword")), alexSaw.toString());
        assertTrue(messages(steve).stream().noneMatch(line -> line.contains("Dropped")));
    }

    @Test
    void leaderboardsArePerKitOrOverallOverExistingKits() {
        duels.stats().recordResult(alex, steve, "sword", 16);
        duels.stats().recordResult(alex, steve, "axe", 20);
        // A deleted kit's ratings are kept but count nowhere.
        duels.stats().recordResult(alex, steve, "gone", 400);
        tickUntil(() -> await(duels.stats().topByElo("gone", 5)).size() == 2);

        assertEquals(1018, duels.stats().cached(alex.getUniqueId()).orElseThrow().overallElo(duels.kits().names()));
        assertEquals(List.of("Alex", "Steve"), await(duels.stats().topByElo(duels.kits().names(), 5)).stream()
                .map(Ranking::name).toList());
        assertEquals(1018, await(duels.stats().topByElo(duels.kits().names(), 5)).getFirst().elo());
        assertEquals(1020, await(duels.stats().topByElo("axe", 5)).getFirst().elo());

        // One lookup per player: lookups have a cooldown.
        assertSays(alex, "duel top elo sword", "Alex · 1016 rating");
        assertSays(steve, "duel top elo", "Alex · 1018 rating");
        assertSays(join("Sam"), "duel top elo nope", "There is no kit called 'nope'");
        assertSays(join("Zoe"), "duel stats Alex", "Sword 1016");
        Command duel = server.getCommandMap().getCommand("duel");
        assertEquals(List.of("sword"), duel.tabComplete(alex, "duel", new String[]{"top", "elo", "s"}));
    }
}
