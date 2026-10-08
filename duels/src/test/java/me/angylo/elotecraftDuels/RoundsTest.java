package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kits with {@code rounds-to-win}: a duel of rounds, with fighters and arena put back between them. */
class RoundsTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Arena arena;

    @BeforeEach
    void players() {
        alex = join("Alex");
        steve = join("Steve");
        arena = readyArena("pit");
    }

    private Match fight(Kit kit) {
        await(duels.kits().update(kit));
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        return match;
    }

    private static Kit firstToTwo(Kit kit) {
        return kit.withRule(KitRule.ROUNDS_TO_WIN, 2);
    }

    /** Waits out the pause after a round, then the next countdown. */
    private void nextRound(Match match) {
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        tickUntil(() -> match.state() == Match.State.FIGHTING);
    }

    private int wins(TestPlayer player) {
        return duels.stats().cached(player.getUniqueId()).orElseThrow().wins();
    }

    private boolean said(TestPlayer player, String text) {
        return messages(player).stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void theFirstToWinTheRoundsWinsTheDuel() {
        Match match = fight(firstToTwo(swordKit()));
        messages(alex);

        steve.simulateDamage(5, alex);
        steve.simulateDamage(100, alex);
        assertEquals(Match.State.ROUND_OVER, match.state());
        assertTrue(said(alex, "Alex won round 1 (1 - 0)"));
        assertEquals(GameMode.SPECTATOR, steve.getGameMode());
        assertEquals(0, wins(alex));

        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        // One timer: the countdown takes as long as the first one did.
        ticks(20 * (duels.settings().countdownSeconds() - 1));
        assertEquals(Match.State.COUNTDOWN, match.state());
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        assertEquals(2, match.round());
        assertEquals(GameMode.SURVIVAL, steve.getGameMode());
        assertEquals(steve.getAttribute(Attribute.MAX_HEALTH).getValue(), steve.getHealth());
        assertTrue(steve.getInventory().contains(Material.DIAMOND_SWORD));
        assertTrue(match.isFighting(steve));

        alex.simulateDamage(100, steve);
        assertTrue(said(alex, "Steve won round 2 (1 - 1)"));
        nextRound(match);

        steve.simulateDamage(100, alex);
        assertEquals(Match.State.ENDING, match.state());
        assertTrue(said(alex, "Alex defeated Steve 2 - 1"));
        assertEquals(1, wins(alex));
        assertEquals(1, duels.stats().cached(steve.getUniqueId()).orElseThrow().losses());
    }

    @Test
    void boxingHitsStartAgainEachRound() {
        Match match = fight(firstToTwo(swordKit().withDamage(false).withRule(KitRule.HITS_TO_WIN, 2)));
        steve.simulateDamage(1, alex);
        steve.simulateDamage(1, alex);
        assertEquals(Match.State.ROUND_OVER, match.state());
        nextRound(match);

        steve.simulateDamage(1, alex);

        assertEquals(Match.State.FIGHTING, match.state());
    }

    @Test
    void forfeitingBetweenRoundsLosesTheDuel() {
        Match match = fight(firstToTwo(swordKit()));
        alex.simulateDamage(100, steve);
        assertEquals(Match.State.ROUND_OVER, match.state());

        assertTrue(duels.matches().leave(alex));

        assertEquals(Match.State.ENDING, match.state());
        assertEquals(1, wins(steve));
    }

    @Test
    void quittingDuringALaterCountdownLosesTheDuel() {
        Match match = fight(firstToTwo(swordKit()));
        steve.simulateDamage(100, alex);
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);

        alex.disconnect();

        assertEquals(1, wins(steve));
        assertTrue(said(steve, "Alex left the duel, so Steve wins."));
    }

    @Test
    void aRoundRunningOutOfTimeIsADraw() {
        setConfig("match.max-duration", "10s");
        Match match = fight(firstToTwo(swordKit()));
        steve.simulateDamage(100, alex);
        nextRound(match);

        ticks(20 * 11);

        assertEquals(Match.State.ENDING, match.state());
        assertEquals(0, wins(alex));
        assertTrue(said(alex, "ended in a draw"));
    }

    @Test
    void buildArenasAndLeftoversArePutBackBetweenRounds() {
        Location spot = new Location(arenaWorld, 6, 64, 7);
        spot.getBlock().setType(Material.SHORT_GRASS);
        Match match = fight(firstToTwo(buildKit()));
        assertFalse(alex.simulateBlockPlace(Material.OAK_PLANKS, spot).isCancelled());
        Item leftover = arenaWorld.dropItem(spot, ItemStack.of(Material.ENDER_PEARL));

        steve.simulateDamage(100, alex);
        nextRound(match);

        assertEquals(Material.SHORT_GRASS, spot.getBlock().getType());
        assertFalse(leftover.isValid());
        // The arena's own grass is not a fighter's block any more.
        BlockBreakEvent breakGrass = steve.simulateBlockBreak(spot.getBlock());
        assertTrue(breakGrass.isCancelled());
        assertTrue(duels.matches().isArenaInUse(arena.name()));
    }

    @Test
    void partyFightsPlayOneRound() {
        Kit kit = firstToTwo(swordKit());
        await(duels.kits().update(kit));
        assertTrue(duels.matches().start(List.of(List.of(alex), List.of(steve)), kit, arena, Match.Type.PARTY, false));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);

        steve.simulateDamage(100, alex);

        assertEquals(Match.State.ENDING, match.state());
    }

    @Test
    void anAdminStopBetweenRoundsSendsEveryoneBack() {
        Match match = fight(firstToTwo(buildKit()));
        steve.simulateDamage(100, alex);
        assertEquals(Match.State.ROUND_OVER, match.state());

        assertTrue(duels.matches().stop(alex));

        assertFalse(duels.matches().isBusy(alex));
        assertFalse(duels.matches().isBusy(steve));
        tickUntil(() -> !duels.matches().isArenaInUse(arena.name()));
        assertEquals(0, wins(alex));
    }

    @Test
    void roundsHaveTheirOwnLimit() {
        Kit kit = swordKit();

        assertEquals(1, fight(kit).roundsToWin());
        assertEquals(10, kit.withRule(KitRule.ROUNDS_TO_WIN, KitRule.MAX_ROUNDS).number(KitRule.ROUNDS_TO_WIN).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> kit.withRule(KitRule.ROUNDS_TO_WIN, KitRule.MAX_ROUNDS + 1));
    }
}
