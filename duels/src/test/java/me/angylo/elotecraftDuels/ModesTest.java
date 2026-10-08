package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bridge and bed fight kit modes. */
class ModesTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Arena arena;

    @BeforeEach
    void setUpArena() {
        alex = join("Alex");
        steve = join("Steve");
        arena = readyArena("pit").withPoints(new Arena.ModePoints(new Position(5.5, 64, 15.5, 0, 0),
                new Position(15.5, 64, 15.5, 0, 0), new Position(5, 64, 12, 0, 0), new Position(15, 64, 12, 0, 0)));
        await(duels.arenas().update(arena));
    }

    private static Kit kit(Kit.Mode mode) {
        return new Kit("mode", "<gold>Mode", Material.OAK_PLANKS, null, List.of(ItemStack.of(Material.OAK_PLANKS, 64)), true, Set.of(), true)
                .withMode(mode);
    }

    /** Steve's goal: Alex scores by walking in. */
    private Location steveGoal() {
        return new Location(arenaWorld, 15.5, 64, 15.5);
    }

    private Match fight(Kit kit) {
        await(duels.kits().update(kit));
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        return match;
    }

    private Block block(int x, int y, int z) {
        return arenaWorld.getBlockAt(x, y, z);
    }

    private boolean said(TestPlayer player, String text) {
        return messages(player).stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void bridgeFightersRespawnAndGoalsWinRoundsWithTheBlocksLeftInPlace() {
        Match match = fight(kit(Kit.Mode.BRIDGE).withRule(KitRule.ROUNDS_TO_WIN, 2));

        steve.simulateDamage(100, alex);
        assertTrue(match.isFighting(steve));
        assertEquals(Match.State.FIGHTING, match.state());
        tickUntil(() -> steve.getLocation().distance(match.spawnOf(steve)) < 1);

        BlockPlaceEvent bridge = alex.simulateBlockPlace(Material.OAK_PLANKS, new Location(arenaWorld, 10, 64, 10));
        BlockPlaceEvent nearSpawn = alex.simulateBlockPlace(Material.OAK_PLANKS, new Location(arenaWorld, 6, 64, 6));
        assertFalse(bridge.isCancelled());
        assertTrue(nearSpawn.isCancelled());

        messages(alex);
        alex.simulatePlayerMove(steveGoal());
        assertEquals(Match.State.ROUND_OVER, match.state());
        assertTrue(said(alex, "GOAL! Alex scored."));
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        assertEquals(Material.OAK_PLANKS, block(10, 64, 10).getType());

        alex.simulatePlayerMove(steveGoal());
        assertEquals(Match.State.ENDING, match.state());
        assertEquals(List.of(0), match.winnerTeams());
    }

    @Test
    void aBridgeFighterWhoReallyDiesComesBackAtTheirSpawnWithTheKit() {
        Match match = fight(kit(Kit.Mode.BRIDGE));

        steve.setHealth(0);
        tickUntil(() -> !steve.isDead() && steve.getLocation().distance(match.spawnOf(steve)) < 1);
        tick();

        assertTrue(match.isFighting(steve));
        assertTrue(steve.getInventory().contains(Material.OAK_PLANKS));
        assertEquals(Match.State.FIGHTING, match.state());
    }

    @Test
    void bedFightersRespawnUntilTheirBedIsBroken() {
        block(5, 64, 12).setType(Material.RED_BED);
        block(15, 64, 12).setType(Material.RED_BED);
        Match match = fight(kit(Kit.Mode.BED_FIGHT));

        steve.simulateDamage(100, alex);
        assertTrue(match.isFighting(steve));

        messages(steve);
        BlockBreakEvent own = steve.simulateBlockBreak(block(15, 64, 12));
        assertTrue(own.isCancelled());
        assertTrue(said(steve, "You can't break your own bed."));

        BlockBreakEvent enemy = alex.simulateBlockBreak(block(15, 64, 12));
        assertFalse(enemy.isCancelled());
        assertFalse(enemy.isDropItems());
        assertFalse(match.hasBed(1));
        assertTrue(match.hasBed(0));

        steve.simulateDamage(100, alex);
        assertEquals(Match.State.ENDING, match.state());
        assertEquals(List.of(0), match.winnerTeams());
    }

    private List<String> sidebar(TestPlayer player) {
        return duels.sidebar().layoutFor(player).lines().stream().map(Text::plain).toList();
    }

    @Test
    void theSidebarShowsGoalsAndBeds() {
        Match bridge = fight(kit(Kit.Mode.BRIDGE).withRule(KitRule.ROUNDS_TO_WIN, 3));
        alex.simulatePlayerMove(steveGoal());

        assertTrue(sidebar(alex).contains("Goals: 1 - 0 (to 3)"), sidebar(alex).toString());
        assertTrue(sidebar(steve).contains("Goals: 0 - 1 (to 3)"));
        assertTrue(sidebar(alex).stream().noneMatch(line -> line.contains("Round")));
        assertTrue(duels.matches().stop(alex));
        tickUntil(() -> duels.matches().matchOf(alex).isEmpty() && !duels.matches().running().contains(bridge));

        block(5, 64, 12).setType(Material.RED_BED);
        block(15, 64, 12).setType(Material.RED_BED);
        fight(kit(Kit.Mode.BED_FIGHT));
        assertTrue(sidebar(alex).contains("Your bed: ✔ · Enemy: ✔"));
        alex.simulateBlockBreak(block(15, 64, 12));

        assertTrue(sidebar(alex).contains("Your bed: ✔ · Enemy: ✘"));
        assertTrue(sidebar(steve).contains("Your bed: ✘ · Enemy: ✔"));
    }

    @Test
    void aModeKitOnlyTakesArenasWithItsGoalsOrBeds() {
        Arena plain = readyArena("plain");
        TestPlayer admin = join("Admin");
        admin.setOp(true);
        swordKit();

        assertSays(admin, "duels kit mode sword bridge", "Sword is now a bridge kit");
        Kit sword = duels.kits().get("sword").orElseThrow();

        assertEquals(Kit.Mode.BRIDGE, sword.mode());
        assertTrue(sword.build());
        assertTrue(sword.accepts(arena));
        assertFalse(sword.accepts(plain));
        assertFalse(sword.withMode(Kit.Mode.BED_FIGHT).accepts(plain.withPoints(arena.points().withBed(2, null))));
    }
}
