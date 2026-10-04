package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole duels, from saving everyone's state to putting it back. */
class MatchFlowTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        alex.getInventory().addItem(ItemStack.of(Material.DIRT, 5));
        steve.getInventory().addItem(ItemStack.of(Material.COBBLESTONE, 7));
        kit = swordKit();
        arena = readyArena("pit");
    }

    private Match startAndFight() {
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    @Test
    void winnerGetsTheWinAndEveryoneIsRestored() {
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);

        assertEquals(arenaWorld, alex.getWorld());
        assertTrue(alex.getInventory().contains(Material.DIAMOND_SWORD));
        assertFalse(alex.getInventory().contains(Material.DIRT));
        Location frozenAt = alex.getLocation();
        PlayerMoveEvent move = alex.simulatePlayerMove(frozenAt.clone().add(1, 0, 0));
        assertEquals(frozenAt.getX(), move.getTo().getX());

        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());

        EntityDamageEvent lethal = steve.simulateDamage(100, alex);
        assertTrue(lethal.isCancelled());
        assertFalse(steve.isDead());
        assertEquals(Match.State.ENDING, match.state());
        assertEquals(GameMode.SPECTATOR, steve.getGameMode());
        assertEquals(1, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
        assertEquals(1, duels.stats().cached(steve.getUniqueId()).orElseThrow().losses());

        ticks(20 * duels.settings().endDelaySeconds() + 1);
        assertFalse(duels.matches().isBusy(alex));
        assertFalse(duels.matches().isBusy(steve));
        assertEquals(world, alex.getWorld());
        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
        assertFalse(alex.getInventory().contains(Material.DIAMOND_SWORD));
        assertTrue(steve.getInventory().contains(Material.COBBLESTONE, 7));
        assertEquals(GameMode.SURVIVAL, steve.getGameMode());
        assertFalse(duels.matches().isArenaBusy("pit"));

        tickUntil(() -> await(duels.snapshots().find(alex.getUniqueId())).isEmpty());
        List<PlayerStats> top = await(duels.stats().top(10));
        assertEquals("Alex", top.getFirst().name());
        assertEquals(1, top.getFirst().wins());
        assertEquals(1, top.getFirst().winStreak());
    }

    @Test
    void quittingMidFightLosesAndIsRestoredAtOnce() {
        startAndFight();

        steve.disconnect();

        assertTrue(steve.getInventory().contains(Material.COBBLESTONE, 7));
        assertFalse(steve.getInventory().contains(Material.DIAMOND_SWORD));
        assertEquals(world, steve.getWorld());
        assertEquals(1, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Steve left the duel, so Alex wins.")));
    }

    @Test
    void quittingDuringTheCountdownCancelsWithoutAResult() {
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);

        steve.disconnect();

        assertFalse(duels.matches().isBusy(alex));
        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
        assertEquals(0, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
    }

    @Test
    void timeRunningOutIsADraw() {
        Match match = startAndFight();

        ticks(20 * (int) duels.settings().maxDuration().toSeconds());

        assertEquals(Match.State.ENDING, match.state());
        assertEquals(0, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("ended in a draw")));
    }

    @Test
    void forfeitingGivesTheOpponentTheWin() {
        startAndFight();

        assertTrue(duels.matches().leave(alex));

        assertEquals(1, duels.stats().cached(steve.getUniqueId()).orElseThrow().wins());
    }

    @Test
    void aDeathThatSlipsThroughKeepsEverythingAndEndsTheDuel() {
        Match match = startAndFight();

        steve.setHealth(0);
        ticks(2);

        assertEquals(Match.State.ENDING, match.state());
        assertEquals(1, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
        assertFalse(steve.isDead());
        ticks(20 * duels.settings().endDelaySeconds() + 2);
        assertTrue(steve.getInventory().contains(Material.COBBLESTONE, 7));
        assertEquals(world, steve.getWorld());
    }

    @Test
    void shutdownPutsEveryoneBack() {
        startAndFight();

        duels.shutdown();
        duels = null;

        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
        assertEquals(world, alex.getWorld());
        assertEquals(world, steve.getWorld());
    }

    @Test
    void aSnapshotLeftByACrashIsRestoredOnTheNextJoin() {
        PlayerSnapshot beforeCrash = PlayerSnapshot.capture(alex);
        await(duels.snapshots().save(Map.of(alex.getUniqueId(), beforeCrash)));
        alex.getInventory().clear();
        alex.getInventory().addItem(ItemStack.of(Material.DIAMOND_SWORD));
        alex.disconnect();

        TestPlayer back = new TestPlayer(server, "Alex", alex.getUniqueId());
        server.addPlayer(back);
        tick();

        assertTrue(back.getInventory().contains(Material.DIRT, 5));
        assertFalse(back.getInventory().contains(Material.DIAMOND_SWORD));
        tickUntil(() -> await(duels.snapshots().find(back.getUniqueId())).isEmpty());
    }

    @Test
    void spectatorsWatchAndAreSentBackWhenItEnds() {
        TestPlayer viewer = join("Viewer");
        Match match = startAndFight();

        duels.matches().spectate(viewer, match);
        tickUntil(() -> viewer.getGameMode() == GameMode.SPECTATOR);
        assertEquals(arenaWorld, viewer.getWorld());

        steve.simulateDamage(100, alex);
        ticks(20 * duels.settings().endDelaySeconds() + 1);

        assertEquals(world, viewer.getWorld());
        assertEquals(GameMode.SURVIVAL, viewer.getGameMode());
    }
}
