package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.stats.FfaStats;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kits' free-for-all arenas: joining, kills and respawns, leaving, and the stats they keep. */
class FfaTest extends DuelsTestBase {

    private TestPlayer ann;
    private TestPlayer bob;
    private Kit sword;

    @BeforeEach
    void setUp() {
        ann = join("Ann");
        bob = join("Bob");
        sword = swordKit();
        Arena pit = readyArena("pit");
        await(duels.arenas().update(pit.withFfa("sword")));
    }

    /** Joins the sword free-for-all and waits until {@code player} may fight in it. */
    private Match joinFfa(TestPlayer player) {
        server.dispatchCommand(player, "duel ffa sword");
        tickUntil(() -> duels.matches().matchOf(player).filter(match -> match.isFighting(player)).isPresent());
        return duels.matches().matchOf(player).orElseThrow();
    }

    private List<FfaStats.Entry> statsOf(TestPlayer player) {
        return await(duels.ffaStats().of(player.getUniqueId()));
    }

    @Test
    void aFreeForAllArenaTakesNoDuels() {
        assertFalse(sword.accepts(duels.arenas().get("pit").orElseThrow()));
        assertFalse(duels.matches().hasArenaFor(sword));
        assertTrue(duels.matches().hasFfaArena(sword));
    }

    @Test
    void playersJoinOneFightWithTheKit() {
        ann.getInventory().addItem(ItemStack.of(Material.DIRT));

        Match first = joinFfa(ann);
        Match second = joinFfa(bob);

        assertSame(first, second);
        assertEquals(Match.Type.FFA, first.type());
        assertEquals(2, first.fighters().size());
        assertTrue(ann.getInventory().contains(Material.DIAMOND_SWORD));
        assertFalse(ann.getInventory().contains(Material.DIRT));
    }

    @Test
    void aKillCreditsTheKillerAndTheVictimRespawnsWithTheKit() {
        Match match = joinFfa(ann);
        joinFfa(bob);

        ann.simulateDamage(100, bob);
        tick();

        assertTrue(match.isFighting(ann));
        assertTrue(ann.getInventory().contains(Material.DIAMOND_SWORD));
        assertEquals(1, match.fightStats().kills(bob));
        assertEquals(1, match.fightStats().deaths(ann));
        assertEquals(1, match.fightStats().streak(bob));
        tickUntil(() -> !statsOf(bob).isEmpty() && !statsOf(ann).isEmpty());
        assertEquals(new FfaStats.Entry("sword", 1, 0, 1), statsOf(bob).getFirst());
        assertEquals(new FfaStats.Entry("sword", 0, 1, 0), statsOf(ann).getFirst());
    }

    @Test
    void theFreeForAllBoardsRankByKills() {
        joinFfa(ann);
        joinFfa(bob);

        ann.simulateDamage(100, bob);

        tickUntil(() -> await(duels.ffaStats().top("sword", 10)).size() == 2);
        assertEquals("Bob", await(duels.ffaStats().top("sword", 10)).getFirst().name());
        assertEquals(1, await(duels.ffaStats().top(null, 10)).getFirst().wins());
    }

    @Test
    void leavingRestoresThePlayerAndTheLastOneOutClosesIt() {
        ann.getInventory().addItem(ItemStack.of(Material.DIRT));
        joinFfa(ann);
        joinFfa(bob);

        server.dispatchCommand(ann, "duel leave");
        tickUntil(() -> ann.getInventory().contains(Material.DIRT));

        assertFalse(duels.matches().isRestricted(ann));
        assertTrue(duels.matches().ffa("sword").isPresent());
        bob.disconnect();
        tick();
        assertTrue(duels.matches().ffa("sword").isEmpty());
        assertTrue(duels.matches().isArenaFree(duels.arenas().get("pit").orElseThrow()));
    }

    @Test
    void leavingRightAfterAHitCountsAsADeath() {
        joinFfa(ann);
        joinFfa(bob);

        ann.simulateDamage(1, bob);
        server.dispatchCommand(ann, "duel leave");

        tickUntil(() -> !statsOf(bob).isEmpty());
        assertEquals(1, statsOf(bob).getFirst().kills());
        assertEquals(1, statsOf(ann).getFirst().deaths());
    }
}
