package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        return new Kit("mode", "<gold>Mode", Material.OAK_PLANKS, null, List.of(ItemStack.of(Material.OAK_PLANKS, 64)), Set.of()).withRule(KitRule.BUILD, true)
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

    /** Leather armor, white wool and blue terracotta: the pieces team colors change. */
    private static Kit coloredKit(Kit.Mode mode) {
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(41, (ItemStack) null));
        items.set(0, ItemStack.of(Material.WHITE_WOOL, 32));
        items.set(1, ItemStack.of(Material.BLUE_TERRACOTTA, 16));
        items.set(2, ItemStack.of(Material.TERRACOTTA, 8));
        items.set(3, ItemStack.of(Material.STONE, 8));
        items.set(4, ItemStack.of(Material.WHITE_GLAZED_TERRACOTTA, 4));
        items.set(36, ItemStack.of(Material.LEATHER_BOOTS));
        items.set(38, ItemStack.of(Material.LEATHER_CHESTPLATE));
        items.set(39, ItemStack.of(Material.IRON_HELMET));
        return new Kit("mode", "<gold>Mode", Material.OAK_PLANKS, null, items, Set.of()).withRule(KitRule.BUILD, true).withMode(mode);
    }

    private static Color color(ItemStack armor) {
        return ((LeatherArmorMeta) armor.getItemMeta()).getColor();
    }

    private void assertTeamColors(TestPlayer player, Color color, Material wool, Material terracotta) {
        assertEquals(color, color(player.getInventory().getBoots()));
        assertEquals(color, color(player.getInventory().getChestplate()));
        assertEquals(Material.IRON_HELMET, player.getInventory().getHelmet().getType());
        assertEquals(wool, player.getInventory().getItem(0).getType());
        assertEquals(32, player.getInventory().getItem(0).getAmount());
        assertEquals(terracotta, player.getInventory().getItem(1).getType());
        assertEquals(terracotta, player.getInventory().getItem(2).getType());
        assertEquals(Material.STONE, player.getInventory().getItem(3).getType());
        assertEquals(Material.WHITE_GLAZED_TERRACOTTA, player.getInventory().getItem(4).getType());
    }

    @Test
    void modeFightersGetTheirTeamColors() {
        for (Kit.Mode mode : List.of(Kit.Mode.BRIDGE, Kit.Mode.BED_FIGHT)) {
            block(5, 64, 12).setType(Material.RED_BED);
            block(15, 64, 12).setType(Material.RED_BED);
            Match match = fight(coloredKit(mode));

            assertTeamColors(alex, Color.RED, Material.RED_WOOL, Material.RED_TERRACOTTA);
            assertTeamColors(steve, Color.BLUE, Material.BLUE_WOOL, Material.BLUE_TERRACOTTA);
            steve.simulateDamage(100, alex);
            tickUntil(() -> steve.getLocation().distance(match.spawnOf(steve)) < 1);
            assertTeamColors(steve, Color.BLUE, Material.BLUE_WOOL, Material.BLUE_TERRACOTTA);

            assertTrue(duels.matches().stop(alex));
            tickUntil(() -> duels.matches().matchOf(alex).isEmpty() && duels.matches().matchOf(steve).isEmpty());
            ticks(20 * duels.settings().endDelaySeconds() + 40);
        }
    }

    @Test
    void normalKitsKeepTheirColors() {
        fight(coloredKit(Kit.Mode.NORMAL));

        assertEquals(Material.WHITE_WOOL, alex.getInventory().getItem(0).getType());
        assertNotEquals(Color.RED, color(alex.getInventory().getBoots()));
    }

    /** The goal is a flat ring at the goal point's layer: passing over or beside it scores nothing, through it scores. */
    @Test
    void aGoalIsALayerToPassThroughNotABox() {
        Match match = fight(kit(Kit.Mode.BRIDGE).withRule(KitRule.ROUNDS_TO_WIN, 3));

        alex.simulatePlayerMove(new Location(arenaWorld, 15.5, 65, 15.5));
        alex.simulatePlayerMove(new Location(arenaWorld, 13.5, 66, 13.5));
        alex.simulatePlayerMove(new Location(arenaWorld, 15.5, 66, 15.5));
        alex.simulatePlayerMove(new Location(arenaWorld, 19.5, 66, 15.5));
        alex.simulatePlayerMove(new Location(arenaWorld, 19.5, 64, 15.5));
        assertEquals(Match.State.FIGHTING, match.state());

        // Falling from above to below the layer in one move still crosses it.
        alex.simulatePlayerMove(new Location(arenaWorld, 16.5, 66, 14.5));
        alex.simulatePlayerMove(new Location(arenaWorld, 16.5, 62.5, 14.5));
        assertEquals(Match.State.ROUND_OVER, match.state());
    }

    @Test
    void anEndPortalOnTheEnemySideScoresOnceEvenOutsideTheGoalRadius() {
        Match match = fight(kit(Kit.Mode.BRIDGE).withRule(KitRule.ROUNDS_TO_WIN, 3));
        block(15, 64, 19).setType(Material.END_PORTAL);
        block(15, 64, 15).setType(Material.END_PORTAL);
        block(5, 64, 19).setType(Material.END_PORTAL);

        // Alex's own goal side: nothing.
        alex.simulatePlayerMove(new Location(arenaWorld, 5.5, 64, 19.5));
        assertEquals(Match.State.FIGHTING, match.state());

        // Inside the goal radius and in a portal at once: one goal.
        alex.simulatePlayerMove(new Location(arenaWorld, 15.5, 64, 15.5));
        assertEquals(Match.State.ROUND_OVER, match.state());
        server.getPluginManager().callEvent(new PlayerPortalEvent(alex, alex.getLocation(), world.getSpawnLocation(), TeleportCause.END_PORTAL));
        assertEquals(List.of(1, 0), List.of(match.roundWins(0), match.roundWins(1)));

        tickUntil(() -> match.state() == Match.State.FIGHTING);
        messages(alex);
        alex.simulatePlayerMove(new Location(arenaWorld, 15.5, 64, 19.5));
        assertEquals(Match.State.ROUND_OVER, match.state());
        assertEquals(2, match.roundWins(0));
        PlayerPortalEvent portal = new PlayerPortalEvent(alex, alex.getLocation(), world.getSpawnLocation(), TeleportCause.END_PORTAL);
        server.getPluginManager().callEvent(portal);
        assertTrue(portal.isCancelled());
        assertTrue(messages(alex).stream().noneMatch(line -> line.contains("teleport")));
    }

    @Test
    void theScorerWatchesFromTheCenterUntilTheNextRound() {
        await(duels.arenas().update(arena.withCenter(new Position(10.5, 70, 10.5, 90, 0))));
        arena = duels.arenas().get("pit").orElseThrow();
        Match match = fight(kit(Kit.Mode.BRIDGE).withRule(KitRule.ROUNDS_TO_WIN, 2));

        alex.simulatePlayerMove(steveGoal());

        assertEquals(GameMode.SPECTATOR, alex.getGameMode());
        assertEquals(GameMode.SURVIVAL, steve.getGameMode());
        tickUntil(() -> alex.getLocation().distance(new Location(arenaWorld, 10.5, 70, 10.5)) < 0.1);
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        tickUntil(() -> alex.getGameMode() == GameMode.SURVIVAL);
        assertTrue(alex.getLocation().distance(match.spawnOf(alex)) < 1);

        tickUntil(() -> match.state() == Match.State.FIGHTING);
        alex.simulatePlayerMove(steveGoal());
        assertEquals(Match.State.ENDING, match.state());
        assertEquals(GameMode.SPECTATOR, alex.getGameMode());
        tickUntil(() -> duels.matches().matchOf(alex).isEmpty());
        tickUntil(() -> alex.getGameMode() == GameMode.SURVIVAL);
    }

    @Test
    void withoutACenterTheScorerWatchesFromBetweenTheSpawns() {
        Match match = fight(kit(Kit.Mode.BRIDGE).withRule(KitRule.ROUNDS_TO_WIN, 2));

        alex.simulatePlayerMove(steveGoal());

        Location spawn1 = match.spawnOf(alex);
        Location spawn2 = match.spawnOf(steve);
        Location middle = spawn1.clone().add(spawn2).multiply(0.5);
        tickUntil(() -> alex.getLocation().distance(middle) < 0.1);
    }

    @Test
    void arenaBoundsOffLetsFightersLeaveTheBoxButNotSpectators() {
        Match match = fight(kit(Kit.Mode.BRIDGE).withRule(KitRule.ARENA_BOUNDS, false));
        Location outside = new Location(arenaWorld, 30, 70, 10);
        messages(alex);

        PlayerMoveEvent move = alex.simulatePlayerMove(outside);

        assertEquals(outside, move.getTo());
        assertTrue(match.isFighting(alex));
        assertTrue(messages(alex).stream().noneMatch(line -> line.contains("can't leave the arena")));
        TestPlayer watcher = join("Watcher");
        duels.matches().spectate(watcher, match);
        tickUntil(() -> match.contains(watcher.getLocation()));
        assertNotEquals(outside, watcher.simulatePlayerMove(outside).getTo());
    }

    @Test
    void arenaBoundsOnByDefaultSendsFightersBack() {
        Match match = fight(kit(Kit.Mode.BRIDGE));

        PlayerMoveEvent move = alex.simulatePlayerMove(new Location(arenaWorld, 30, 70, 10));

        assertEquals(match.spawnOf(alex), move.getTo());
    }

    /** MockBukkit has no LivingEntity#heal, so this is reported as skipped; smoke.js checks it on a real server. */
    @Test
    void aGoldenAppleHealsBridgeFightersFullyAndKeepsItsEffects() {
        fight(kit(Kit.Mode.BRIDGE));
        alex.setHealth(4);
        alex.setFoodLevel(10);
        alex.setSaturation(0);

        eat(alex, Material.GOLDEN_APPLE);

        assertEquals(alex.getAttribute(Attribute.MAX_HEALTH).getValue(), alex.getHealth());
        assertEquals(10, alex.getFoodLevel());
        assertEquals(0, alex.getSaturation());
    }

    @Test
    void aGoldenAppleDoesNotHealOtherKitsAtOnce() {
        fight(kit(Kit.Mode.NORMAL));
        alex.setHealth(4);

        eat(alex, Material.GOLDEN_APPLE);

        assertEquals(4, alex.getHealth());
    }

    /** The consume event, then what vanilla does: the apple's effects. MockBukkit eats nothing itself. */
    private void eat(TestPlayer player, Material food) {
        PlayerItemConsumeEvent event = new PlayerItemConsumeEvent(player, ItemStack.of(food), EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled());
        tick();
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
        assertTrue(sword.flag(KitRule.BUILD, duels.settings()));
        assertTrue(sword.accepts(arena));
        assertFalse(sword.accepts(plain));
        assertFalse(sword.withMode(Kit.Mode.BED_FIGHT).accepts(plain.withPoints(arena.points().withBed(2, null))));
    }
}
