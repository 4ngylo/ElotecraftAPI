package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaPool;
import me.angylo.elotecraftDuels.arena.ArenaTemplate;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copies of busy arenas pasted on demand in the arenas world, and cleared once idle. */
class ArenaPoolTest extends DuelsTestBase {

    private static final int SECOND = 20;

    private TestPlayer admin;
    private Arena pit;
    private World arenas;
    private Kit kit;

    @BeforeEach
    void setUpArena() {
        admin = join("Admin");
        admin.setOp(true);
        arenaWorld.getBlockAt(5, 60, 5).setType(Material.GOLD_BLOCK);
        pit = readyArena("pit").withCategories(Set.of("bridge"));
        await(duels.arenas().update(pit));
        assertSays(admin, "duels arena snapshot pit", "Saved the blocks of pit");
        arenas = Bukkit.getWorld(duels.settings().arenasWorld());
        assertNotNull(arenas);
        kit = swordKit();
    }

    /** The block of {@code copy} where pit has its gold block. */
    private Block gold(Arena copy) {
        return arenas.getBlockAt(5 + copy.copy().dx(), 60 + copy.copy().dy(), 5 + copy.copy().dz());
    }

    private Match duel(String first, String second) {
        TestPlayer one = join(first);
        assertTrue(duels.matches().start(one, join(second), kit, duels.arenas().get("pit").orElseThrow()));
        return duels.matches().matchOf(one).orElseThrow();
    }

    private ArenaPool.Count count() {
        return duels.pool().count("pit");
    }

    @Test
    void aBusyArenaGetsACopyForTheNextDuel() {
        setConfig("arenas.pool.warm", 0);
        Match first = duel("A", "B");
        Match second = duel("C", "D");

        assertNull(first.arena().copy());
        Arena copy = second.arena();
        assertEquals("pit", copy.name());
        assertEquals("pit", copy.copy().source());
        assertEquals(arenas.getName(), copy.world());
        assertEquals(Set.of("bridge"), copy.categories());
        assertEquals(pit.spawn1().x() + copy.copy().dx(), copy.spawn1().x());
        assertFalse(copy.bounds().overlaps(pit.bounds()) && copy.world().equals(pit.world()));
        assertEquals(Material.GOLD_BLOCK, gold(copy).getType());
        tickUntil(() -> second.state() == Match.State.COUNTDOWN);
        assertTrue(second.contains(second.fighters().getFirst().getLocation()));
        assertEquals(new ArenaPool.Count(1, 0), count());
        assertTrue(duels.matches().isArenaInUse("pit"));
    }

    @Test
    void aFreeCopyIsLentAgainAndMaxCopiesCapsThem() {
        setConfig("arenas.pool.warm", 0);
        setConfig("arenas.pool.max-copies", 1);
        duel("A", "B");
        Match second = duel("C", "D");
        Arena pitArena = duels.arenas().get("pit").orElseThrow();

        assertFalse(duels.matches().isArenaFree(pitArena));
        assertTrue(duels.matches().stop(second.fighters().getFirst()));
        assertEquals(new ArenaPool.Count(0, 1), count());

        Match third = duel("E", "F");
        assertEquals(second.arena(), third.arena());
        assertEquals(1, worldEdit.pastes());
    }

    @Test
    void fightersWaitWhileTheCopyIsPasted() {
        setConfig("arenas.pool.warm", 0);
        duel("A", "B");
        worldEdit.holdPastes(true);
        TestPlayer c = join("C");
        messages(c);

        Match second = duel0(c, join("D"));
        ticks(SECOND);

        assertTrue(messages(c).stream().anyMatch(line -> line.contains("Preparing a copy of the arena")));
        assertEquals(Match.State.STARTING, second.state());
        assertFalse(second.contains(c.getLocation()));
        worldEdit.releasePastes();
        tickUntil(() -> second.state() == Match.State.COUNTDOWN);
        assertTrue(second.contains(c.getLocation()));
    }

    @Test
    void aDuelCancelledDuringThePasteFreesTheCopyOnceDone() {
        setConfig("arenas.pool.warm", 0);
        duel("A", "B");
        worldEdit.holdPastes(true);
        TestPlayer c = join("C");
        duel0(c, join("D"));

        c.disconnect();
        tick();
        assertTrue(duels.matches().matchOf(c).isEmpty());
        worldEdit.releasePastes();

        assertEquals(new ArenaPool.Count(0, 1), count());
    }

    @Test
    void aFailedPasteCancelsTheDuel() {
        setConfig("arenas.pool.warm", 0);
        duel("A", "B");
        worldEdit.failPastes(true);
        TestPlayer c = join("C");
        messages(c);

        duel0(c, join("D"));
        tickUntil(() -> duels.matches().matchOf(c).isEmpty());

        assertTrue(messages(c).stream().anyMatch(line -> line.contains("couldn't be prepared")));
        assertEquals(new ArenaPool.Count(0, 0), count());
    }

    @Test
    void aWarmCopyIsPastedAheadAndIdleCopiesAreClearedAfterTheTimeout() {
        setConfig("arenas.pool.idle-timeout", "10s");
        Match first = duel("A", "B");
        ticks(SECOND + 1);
        // pit itself is busy, so one copy waits for the next duel.
        assertEquals(new ArenaPool.Count(0, 1), count());
        Match second = duel("C", "D");
        Arena copy = second.arena();
        assertEquals(1, worldEdit.pastes());

        assertTrue(duels.matches().stop(second.fighters().getFirst()));
        ticks(11 * SECOND);
        // Still the one warm place while pit is busy.
        assertEquals(new ArenaPool.Count(0, 1), count());

        assertTrue(duels.matches().stop(first.fighters().getFirst()));
        ticks(11 * SECOND);
        assertEquals(new ArenaPool.Count(0, 0), count());
        assertEquals(Material.AIR, gold(copy).getType());
        assertTrue(duels.arenas().poolBoxes().isEmpty());
    }

    @Test
    void withoutASnapshotAnArenaHostsOneDuelAtATime() {
        readyArena("yard");
        Arena yard = duels.arenas().get("yard").orElseThrow();
        assertTrue(duels.matches().start(join("A"), join("B"), kit, yard));

        assertFalse(duels.matches().isArenaFree(yard));
        assertSays(admin, "duels arena pool yard", "none until /duels arena snapshot saves its blocks");
        assertSays(admin, "duels arena pool pit", "0 in use, 0 free (at most 32)");
    }

    @Test
    void changingTheBoxDropsTheSnapshotAndFreeCopies() {
        setConfig("arenas.pool.warm", 0);
        Match first = duel("A", "B");
        Match second = duel("C", "D");
        assertTrue(duels.matches().stop(second.fighters().getFirst()));
        assertTrue(duels.matches().stop(first.fighters().getFirst()));
        admin.teleport(new Location(arenaWorld, 0, 60, 0));

        assertSays(admin, "duels arena setcorner pit 1", "Set corner 1");

        assertFalse(duels.pool().hasTemplate(duels.arenas().get("pit").orElseThrow()));
        tickUntil(() -> count().free() == 0);
        tickUntil(() -> !Files.exists(plugin.getDataFolder().toPath().resolve("arenas").resolve("pit.schem")));
    }

    @Test
    void poolClearRemovesFreeCopies() {
        setConfig("arenas.pool.warm", 0);
        duel("A", "B");
        Match second = duel("C", "D");
        assertTrue(duels.matches().stop(second.fighters().getFirst()));
        // Its fighters may still be on their way out.
        assertSays(admin, "duels arena pool pit clear", "pit has no free copies.");
        ticks(5 * SECOND);

        assertSays(admin, "duels arena pool pit clear", "Removed 1 free copies of pit");
        assertEquals(Material.AIR, gold(second.arena()).getType());
        assertSays(admin, "duels arena pool pit clear", "pit has no free copies.");
    }

    @Test
    void aRestartClearsCopiesLeftBehind() {
        setConfig("arenas.pool.warm", 0);
        duel("A", "B");
        Arena copy = duel("C", "D").arena();
        duels.shutdown();
        assertEquals(1, duels.arenas().poolBoxes().size());

        duels = Duels.start(plugin, worldEdit);

        assertEquals(Material.AIR, gold(copy).getType());
        tickUntil(() -> duels.arenas().poolBoxes().isEmpty());
        assertTrue(duels.pool().hasTemplate(duels.arenas().get("pit").orElseThrow()));
    }

    @Test
    void copiesMadeByTheOldPregenAreDroppedAndCleared() throws IOException {
        duels.shutdown();
        arenas.getBlockAt(1000, 61, 0).setType(Material.STONE);
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String old = "arenas.pit-1.";
        yaml.set(old + "world", arenas.getName());
        yaml.set(old + "corner1.x", 1000);
        yaml.set(old + "corner1.y", 60);
        yaml.set(old + "corner1.z", 0);
        yaml.set(old + "corner2.x", 1020);
        yaml.set(old + "corner2.y", 80);
        yaml.set(old + "corner2.z", 20);
        yaml.set(old + "copy-of.arena", "pit");
        yaml.save(file);

        duels = Duels.start(plugin, worldEdit);

        assertTrue(duels.arenas().get("pit-1").isEmpty());
        assertEquals(Material.AIR, arenas.getBlockAt(1000, 61, 0).getType());
        tickUntil(() -> !YamlConfiguration.loadConfiguration(file).isConfigurationSection("arenas.pit-1"));
    }

    @Test
    void anArenaWithOnlyABlockSnapshotGetsOneForCopiesAtStart() {
        readyArena("yard");
        Arena yard = duels.arenas().get("yard").orElseThrow();
        await(ArenaTemplate.save(plugin, yard));
        assertFalse(duels.pool().hasTemplate(yard));
        duels.shutdown();

        duels = Duels.start(plugin, worldEdit);

        assertTrue(duels.pool().hasTemplate(duels.arenas().get("yard").orElseThrow()));
    }

    @Test
    void theSchematicsFolderIsMadeAndImportsReadFromIt() {
        assertTrue(Files.isDirectory(duels.schematicsFolder()));
        assertSays(admin, "duels arena import desert desert.schem", "There is no desert.schem");
        worldEdit.writeSchematic(duels.schematicsFolder().resolve("desert.schem"),
                worldEdit.copy(arenaWorld, new BoundingBox(0, 60, 0, 6, 65, 6)).join());

        assertSays(admin, "duels arena import desert desert.schem", "Imported arena desert");

        Arena desert = duels.arenas().get("desert").orElseThrow();
        assertEquals(arenas.getName(), desert.world());
        assertEquals(Material.GOLD_BLOCK, arenas.getBlockAt((int) desert.corner1().x() + 5, (int) desert.corner1().y(),
                (int) desert.corner1().z() + 5).getType());
        assertSays(admin, "duels arena import pit desert.schem", "Arena pit already exists.");
        assertSays(admin, "duels arena import other ../x.schem", "Use the name of a .schem file");
    }

    @Test
    void setboxTakesTheWorldEditSelection() {
        admin.teleport(new Location(arenaWorld, 5, 64, 5));
        assertSays(admin, "duels arena setbox pit", "Select a cuboid with WorldEdit first");
        worldEdit.selection(new BoundingBox(0, 60, 0, 21, 81, 21));

        assertSays(admin, "duels arena setbox pit", "Set both corners of pit");
    }

    private Match duel0(TestPlayer first, TestPlayer second) {
        assertTrue(duels.matches().start(first, second, kit, duels.arenas().get("pit").orElseThrow()));
        return duels.matches().matchOf(first).orElseThrow();
    }
}
