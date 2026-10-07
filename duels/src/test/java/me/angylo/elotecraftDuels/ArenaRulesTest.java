package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arena categories and kit pools, build limits, the center and falling out of the arena. */
class ArenaRulesTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private TestPlayer admin;
    private Kit sword;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        admin = join("Admin");
        admin.setOp(true);
        sword = swordKit();
    }

    private Arena inCategory(String name, String category) {
        Arena arena = readyArena(name).withCategories(Set.of(category));
        await(duels.arenas().update(arena));
        return arena;
    }

    private Kit kitFor(Kit kit, String category) {
        Kit limited = kit.withArenaCategories(Set.of(category));
        await(duels.kits().update(limited));
        return limited;
    }

    private Match fight(Kit kit, Arena arena) {
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    @Test
    void kitsOnlyUseArenasOfTheirCategories() {
        readyArena("pit");
        inCategory("span", "bridge");
        Kit bridgeSword = kitFor(sword, "bridge");

        duels.queues().toggle(alex, bridgeSword, false);
        duels.queues().toggle(steve, bridgeSword, false);

        assertEquals("span", duels.matches().matchOf(alex).orElseThrow().arena().name());
    }

    @Test
    void challengingIntoAnArenaOfAnotherCategoryIsRefused() {
        readyArena("pit");
        inCategory("span", "bridge");
        kitFor(sword, "bridge");

        assertSays(alex, "duel Steve sword pit", "pit can't be used with Sword.");
        assertTrue(messages(steve).isEmpty());
    }

    @Test
    void kitsWithoutAReadyArenaCannotQueueOrChallenge() {
        readyArena("pit");
        Kit boxing = kitFor(sword, "boxing");

        assertSays(alex, "duel queue sword", "No arena is ready for Sword yet.");
        assertTrue(duels.queues().queued(alex.getUniqueId()).isEmpty());
        assertSays(alex, "duel Steve sword", "No arena is ready for Sword yet.");
        assertFalse(duels.matches().hasArenaFor(boxing));
    }

    @Test
    void adminsSetCategoriesCentersBuildLimitsAndKitPools() {
        readyArena("pit");
        assertSays(admin, "duels arena category pit add bridge", "pit is now in category bridge.");
        assertSays(admin, "duels arena category pit add Bad.Name", "Use /duels arena category");
        assertSays(admin, "duels arena buildlimit pit 70", "up to Y 70.");
        assertSays(admin, "duels arena buildlimit pit 500", "Use a Y from 60 to 80");
        assertSays(admin, "duels kit arenas sword bridge", "now use arenas in: bridge.");
        assertSays(admin, "duels arena info pit", "Categories: bridge · Build limit: 70");

        admin.teleport(new Location(arenaWorld, 10.5, 64, 10.5));
        assertSays(admin, "duels arena setcenter pit", "Set the center of pit.");
        admin.teleport(new Location(world, 0, 64, 0));
        assertSays(admin, "duels arena setcenter pit", "go there first");

        Arena pit = duels.arenas().get("pit").orElseThrow();
        assertEquals(Set.of("bridge"), pit.categories());
        assertEquals(70, pit.buildLimit());
        assertNull(pit.spectator());
        assertEquals(10.5, pit.spectatorSpawn().getX());
        assertEquals(Set.of("bridge"), duels.kits().get("sword").orElseThrow().arenaCategories());

        assertSays(admin, "duels kit arenas sword any", "now use any arena.");
        assertSays(admin, "duels arena buildlimit pit none", "anywhere inside it.");
        assertTrue(duels.kits().get("sword").orElseThrow().arenaCategories().isEmpty());
        assertNull(duels.arenas().get("pit").orElseThrow().buildLimit());
    }

    @Test
    void blocksCannotBePlacedAboveTheBuildLimit() {
        Arena pit = readyArena("pit").withBuildLimit(65);
        await(duels.arenas().update(pit));
        fight(buildKit(), pit);

        assertFalse(alex.simulateBlockPlace(Material.OAK_PLANKS, new Location(arenaWorld, 6, 65, 7)).isCancelled());
        assertTrue(alex.simulateBlockPlace(Material.OAK_PLANKS, new Location(arenaWorld, 6, 66, 7)).isCancelled());
    }

    @Test
    void fallingOutOfTheBottomLoses() {
        Match match = fight(sword, readyArena("pit"));

        alex.simulatePlayerMove(new Location(arenaWorld, 6, 59, 7));

        assertEquals(Match.State.ENDING, match.state());
        assertEquals(1, duels.stats().cached(steve.getUniqueId()).orElseThrow().wins());
    }

    @Test
    void voidEliminationCanBeTurnedOff() {
        setConfig("rules.void-eliminates", false);
        Match match = fight(sword, readyArena("pit"));

        alex.simulatePlayerMove(new Location(arenaWorld, 6, 59, 7));

        assertEquals(Match.State.FIGHTING, match.state());
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("You can't leave the arena.")));
    }
}
