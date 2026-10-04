package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaTemplate;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Build kits: what fighters may change, and the arena being put back afterwards. */
class BuildTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Arena arena;
    private Kit kit;
    private Block floor;

    @BeforeEach
    void setUp() {
        alex = join("Alex");
        steve = join("Steve");
        arena = readyArena("pit");
        kit = buildKit();
        floor = arenaWorld.getBlockAt(6, 63, 6);
        floor.setType(Material.STONE);
    }

    private Match fight(Kit with) {
        assertTrue(duels.matches().start(alex, steve, with, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    /** Forfeits and waits until the arena takes duels again. */
    private void endAndWaitForArena() {
        assertTrue(duels.matches().leave(alex));
        tickUntil(() -> !duels.matches().isArenaInUse(arena.name()));
    }

    private Location inside() {
        return new Location(arenaWorld, 6, 64, 7);
    }

    @Test
    void fightersBreakOnlyBlocksPlacedDuringTheDuel() {
        fight(kit);

        BlockPlaceEvent place = alex.simulateBlockPlace(Material.OAK_PLANKS, inside());
        BlockBreakEvent breakFloor = alex.simulateBlockBreak(floor);
        BlockBreakEvent breakPlaced = steve.simulateBlockBreak(inside().getBlock());

        assertFalse(place.isCancelled());
        assertTrue(breakFloor.isCancelled());
        assertEquals(Material.STONE, floor.getType());
        assertFalse(breakPlaced.isCancelled());
        assertFalse(breakPlaced.isDropItems());
        assertEquals(Material.AIR, inside().getBlock().getType());
    }

    @Test
    void arenaBlocksCanBeBrokenWhenAllowed() {
        setConfig("build.break-arena-blocks", true);
        fight(kit);

        assertFalse(alex.simulateBlockBreak(floor).isCancelled());
        endAndWaitForArena();

        assertEquals(Material.STONE, floor.getType());
    }

    @Test
    void placingNeedsABuildKitTheFightAndTheArena() {
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        assertTrue(alex.simulateBlockPlace(Material.OAK_PLANKS, inside()).isCancelled());

        ticks(20 * duels.settings().countdownSeconds());
        assertTrue(alex.simulateBlockPlace(Material.OAK_PLANKS, new Location(arenaWorld, 30, 64, 30)).isCancelled());
        assertFalse(alex.simulateBlockPlace(Material.OAK_PLANKS, inside()).isCancelled());
        endAndWaitForArena();

        fight(swordKit());
        assertTrue(alex.simulateBlockPlace(Material.OAK_PLANKS, inside()).isCancelled());
        assertEquals(Material.AIR, inside().getBlock().getType());
    }

    @Test
    void theArenaIsPutBackBeforeTheNextDuel() {
        setConfig("regen.blocks-per-tick", 1);
        fight(kit);
        for (int z = 7; z < 10; z++) {
            assertFalse(alex.simulateBlockPlace(Material.OAK_PLANKS, new Location(arenaWorld, 6, 64, z)).isCancelled());
        }
        assertTrue(duels.arenas().needingReset().contains(arena.name()));

        assertTrue(duels.matches().leave(alex));
        tickUntil(() -> duels.matches().matchOf(steve).isEmpty());
        assertTrue(duels.matches().isArenaInUse(arena.name()));
        assertFalse(duels.matches().isArenaFree(arena));

        tickUntil(() -> !duels.matches().isArenaInUse(arena.name()));
        for (int z = 7; z < 10; z++) {
            assertEquals(Material.AIR, arenaWorld.getBlockAt(6, 64, z).getType());
        }
        assertTrue(duels.arenas().needingReset().isEmpty());
    }

    @Test
    void fluidsStayInsideAndAreRemoved() {
        fight(kit);
        Block edge = arenaWorld.getBlockAt(20, 64, 10);
        Block inner = arenaWorld.getBlockAt(10, 64, 10);
        Block next = arenaWorld.getBlockAt(11, 64, 10);

        BlockFromToEvent out = new BlockFromToEvent(edge, arenaWorld.getBlockAt(21, 64, 10));
        server.getPluginManager().callEvent(out);
        BlockFromToEvent in = new BlockFromToEvent(inner, next);
        server.getPluginManager().callEvent(in);
        next.setType(Material.WATER);

        assertTrue(out.isCancelled());
        assertFalse(in.isCancelled());
        endAndWaitForArena();
        assertEquals(Material.AIR, next.getType());
    }

    @Test
    void fireAndOtherChangesStayInsideAndArePutBack() {
        fight(kit);
        Block edge = arenaWorld.getBlockAt(20, 64, 10);
        Block outside = arenaWorld.getBlockAt(21, 64, 10);
        Block fire = arenaWorld.getBlockAt(10, 64, 10);
        Block ice = arenaWorld.getBlockAt(11, 64, 10);
        Block lit = arenaWorld.getBlockAt(12, 64, 10);

        assertTrue(called(new BlockSpreadEvent(outside, edge, outside.getState())));
        assertTrue(called(new BlockIgniteEvent(outside, IgniteCause.SPREAD, edge)));
        assertTrue(called(new BlockBurnEvent(floor, edge)));
        assertTrue(called(new TNTPrimeEvent(floor, TNTPrimeEvent.PrimeCause.FIRE, null, edge)));
        assertTrue(called(new BlockPistonExtendEvent(fire, List.of(), BlockFace.UP)));
        assertTrue(called(new BlockPistonRetractEvent(fire, List.of(), BlockFace.UP)));
        assertTrue(called(new BlockIgniteEvent(new Location(arenaWorld, 30, 64, 30).getBlock(), IgniteCause.FLINT_AND_STEEL, alex)));
        assertFalse(called(new BlockSpreadEvent(fire, edge, fire.getState())));
        fire.setType(Material.FIRE);
        assertFalse(called(new BlockFormEvent(ice, ice.getState())));
        ice.setType(Material.ICE);
        assertFalse(called(new BlockIgniteEvent(lit, IgniteCause.FLINT_AND_STEEL, alex)));
        lit.setType(Material.FIRE);
        assertFalse(called(new BlockFadeEvent(lit, lit.getState())));

        endAndWaitForArena();
        for (Block block : List.of(fire, ice, lit)) {
            assertEquals(Material.AIR, block.getType());
        }
        assertEquals(Material.STONE, floor.getType());
    }

    @Test
    void bucketsFollowTheSameRules() {
        fight(kit);
        Block water = inside().getBlock();
        ItemStack bucket = ItemStack.of(Material.WATER_BUCKET);

        assertFalse(called(new PlayerBucketEmptyEvent(alex, water, floor, BlockFace.UP, Material.WATER_BUCKET, bucket)));
        water.setType(Material.WATER);
        assertFalse(called(new PlayerBucketFillEvent(alex, water, floor, BlockFace.UP, Material.BUCKET, bucket)));
        assertTrue(called(new PlayerBucketFillEvent(alex, floor, floor, BlockFace.UP, Material.BUCKET, bucket)));

        endAndWaitForArena();
        assertEquals(Material.AIR, water.getType());
    }

    /** @return whether the event was cancelled */
    private boolean called(Event event) {
        server.getPluginManager().callEvent(event);
        return ((Cancellable) event).isCancelled();
    }

    @Test
    void explosionsBreakOnlyPlacedBlocks() {
        fight(kit);
        alex.simulateBlockPlace(Material.OAK_PLANKS, inside());
        Block placed = inside().getBlock();

        List<Block> blocks = new ArrayList<>(List.of(placed, floor));
        server.getPluginManager().callEvent(new BlockExplodeEvent(placed, placed.getState(), blocks, 1, ExplosionResult.DESTROY));

        assertEquals(List.of(placed), blocks);
    }

    @Test
    void duelExplosionsBreakNothingOutsideAndDropNothing() {
        fight(kit);
        alex.simulateBlockPlace(Material.OAK_PLANKS, inside());
        Block placed = inside().getBlock();
        Block outside = arenaWorld.getBlockAt(21, 64, 10);

        List<Block> blocks = new ArrayList<>(List.of(placed, outside));
        BlockExplodeEvent event = new BlockExplodeEvent(placed, placed.getState(), blocks, 1, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(event);

        assertEquals(List.of(placed), blocks);
        assertEquals(0, event.getYield());
    }

    @Test
    void fireAndDispensersCannotReachOutside() {
        fight(kit);
        Block edge = arenaWorld.getBlockAt(20, 64, 10);

        assertTrue(called(new BlockBurnEvent(arenaWorld.getBlockAt(21, 64, 10), edge)));
        assertTrue(called(new BlockDispenseEvent(edge, ItemStack.of(Material.LAVA_BUCKET), new Vector())));
    }

    @Test
    void stoppingTheServerMidDuelPutsTheArenaBackAndClearsTheMark() {
        fight(kit);
        alex.simulateBlockPlace(Material.OAK_PLANKS, inside());

        server.getPluginManager().disablePlugin(plugin);
        duels.shutdown();
        duels = null;

        assertEquals(Material.AIR, inside().getBlock().getType());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "arenas.yml"));
        assertTrue(saved.getStringList("needs-reset").isEmpty());
    }

    @Test
    void anArenaLeftChangedTakesNoDuelsUntilFixed() {
        duels.arenas().needsReset(arena.name(), true);

        assertFalse(duels.matches().isArenaFree(arena));
        assertFalse(duels.matches().start(alex, steve, kit, arena));
    }

    @Test
    void aSnapshotOfOtherCornersIsNotPasted() {
        await(ArenaTemplate.save(plugin, arena));
        Arena moved = arena.withCorner(2, new Arena.Position(30, 80, 30, 0, 0));
        await(duels.arenas().update(moved));

        CompletableFuture<Integer> reset = duels.instances().reset(moved);
        tickUntil(reset::isDone);

        assertTrue(reset.isCompletedExceptionally());
        assertFalse(duels.matches().isArenaInUse(arena.name()));
    }

    @Test
    void explosionsNeverBreakAnIdleArena() {
        Block idle = arenaWorld.getBlockAt(10, 63, 10);
        List<Block> blocks = new ArrayList<>(List.of(idle, arenaWorld.getBlockAt(50, 63, 50)));

        server.getPluginManager().callEvent(new BlockExplodeEvent(idle, idle.getState(), blocks, 1, ExplosionResult.DESTROY));

        assertEquals(1, blocks.size());
        assertEquals(50, blocks.getFirst().getX());
    }

    @Test
    void shutdownPutsTheArenaBack() {
        fight(kit);
        alex.simulateBlockPlace(Material.OAK_PLANKS, inside());

        duels.shutdown();
        duels = null;

        assertEquals(Material.AIR, inside().getBlock().getType());
    }

    @Test
    void anArenaACrashLeftChangedIsRebuiltOnStart() {
        assertEquals(arena.bounds().getVolume(), (double) await(ArenaTemplate.save(plugin, arena)));
        floor.setType(Material.AIR);
        inside().getBlock().setType(Material.OAK_PLANKS);
        duels.arenas().needsReset(arena.name(), true);

        duels.shutdown();
        duels = Duels.start(plugin);
        await(duels.ready());
        tickUntil(() -> !duels.matches().isArenaInUse(arena.name()));

        assertEquals(Material.STONE, floor.getType());
        assertEquals(Material.AIR, inside().getBlock().getType());
        assertTrue(duels.arenas().needingReset().isEmpty());
    }
}
