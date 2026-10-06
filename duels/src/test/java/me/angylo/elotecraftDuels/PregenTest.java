package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaTemplate;
import me.angylo.elotecraftDuels.kit.Kit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duels arena pregen}: copies of an arena in the arenas world. */
class PregenTest extends DuelsTestBase {

    private TestPlayer admin;
    private Arena pit;
    private World arenas;

    @BeforeEach
    void setUpArena() {
        admin = join("Admin");
        admin.setOp(true);
        arenaWorld.getBlockAt(5, 60, 5).setType(Material.GOLD_BLOCK);
        pit = readyArena("pit").withCategories(Set.of("bridge"));
        await(duels.arenas().update(pit));
        await(ArenaTemplate.save(plugin, pit));
        arenas = Bukkit.getWorld(duels.settings().arenasWorld());
        assertNotNull(arenas);
    }

    /** The block of {@code copy} where the source arena has its gold block. */
    private Block gold(Arena copy) {
        return arenas.getBlockAt(5 + copy.copy().dx(), 60 + copy.copy().dy(), 5 + copy.copy().dz());
    }

    @Test
    void pregenPastesReadyCopiesThatDoNotOverlap() {
        assertSays(admin, "duels arena pregen pit 3", "Made 3 copies of pit");

        List<Arena> copies = duels.arenas().copiesOf("pit");
        assertEquals(List.of("pit-1", "pit-2", "pit-3"), copies.stream().map(Arena::name).toList());
        for (Arena copy : copies) {
            assertTrue(copy.isReady(), copy.name() + " " + copy.problems());
            assertEquals(arenas.getName(), copy.world());
            assertEquals(Set.of("bridge"), copy.categories());
            assertEquals(pit.spawn1().x() + copy.copy().dx(), copy.spawn1().x());
            assertEquals(Material.GOLD_BLOCK, gold(copy).getType());
            for (Arena other : copies) {
                assertTrue(other == copy || !copy.bounds().overlaps(other.bounds()));
            }
        }
    }

    @Test
    void aFreePlaceStaysClearOfCopiesAndASecondPregenWaits() {
        assertSays(admin, "duels arena pregen pit 2", "Made 2 copies");
        BoundingBox size = pit.bounds();

        int[] at = duels.pregen().freePlace((int) size.getWidthX(), (int) size.getHeight(), (int) size.getWidthZ(), 64);

        BoundingBox free = new BoundingBox(at[0], at[1], at[2], at[0] + size.getWidthX(), at[1] + size.getHeight(), at[2] + size.getWidthZ());
        assertEquals(64, at[1]);
        for (Arena copy : duels.arenas().copiesOf("pit")) {
            assertFalse(free.overlaps(copy.bounds()), copy.name());
        }

        assertSays(admin, "duels arena pregen pit clear", "Removed 2 copies");
        assertTrue(duels.pregen().pregen(pit, 1, done -> { }).isCompletedExceptionally());
    }

    @Test
    void copiesHostDuelsAtTheSameTime() {
        assertSays(admin, "duels arena pregen pit 2", "Made 2 copies");
        Kit kit = swordKit();

        assertTrue(duels.matches().start(join("A"), join("B"), kit, duels.arenas().get("pit-1").orElseThrow()));
        assertTrue(duels.matches().start(join("C"), join("D"), kit, duels.arenas().get("pit-2").orElseThrow()));
        assertEquals(2, duels.matches().activeMatches());
    }

    @Test
    void copiesAreReadOnlyAndClearedThroughTheirArena() {
        assertSays(admin, "duels arena pregen pit 2", "Made 2 copies");
        Arena copy = duels.arenas().get("pit-1").orElseThrow();

        assertSays(admin, "duels arena setspawn pit-1 1", "is a copy of pit");
        assertSays(admin, "duels arena delete pit", "has copies");
        assertSays(admin, "duels arena pregen pit 2", "has copies");
        assertSays(admin, "duels arena pregen pit clear", "Removed 2 copies of pit");

        assertTrue(duels.arenas().copiesOf("pit").isEmpty());
        tickUntil(() -> gold(copy).getType() == Material.AIR);
        tickUntil(() -> !duels.pregen().isBusy("pit"));
        assertSays(admin, "duels arena delete pit", "Deleted arena pit");
    }

    @Test
    void pregenNeedsASnapshotAndACount() {
        readyArena("yard");
        assertSays(admin, "duels arena pregen yard 2", "has no snapshot");
        assertSays(admin, "duels arena pregen pit 0", "Make 1 to 32 copies");
        assertSays(admin, "duels arena pregen pit lots", "Make 1 to 32 copies");
        assertFalse(duels.pregen().isBusy("yard"));
    }

    /** MockBukkit runs no WorldEdit, so copies come from snapshots and WorldEdit commands explain what they need. */
    @Test
    void withoutWorldEditCopiesKeepBlocksOnlyAndSchematicsAreRefused() {
        assertTrue(duels.worldEdit().isEmpty());
        assertSays(admin, "duels arena pregen pit 1", "don't keep chest contents or sign text");
        assertSays(admin, "duels arena setbox yard", "There is no arena called 'yard'");
        assertSays(admin, "duels arena setbox pit", "go there first");
        admin.teleport(new Location(arenaWorld, 5, 64, 5));
        assertSays(admin, "duels arena setbox pit", "This needs FastAsyncWorldEdit or WorldEdit.");
        assertSays(admin, "duels arena import desert ../x.schem", "Use the name of a .schem file");
        assertSays(admin, "duels arena import desert desert.txt", "Use the name of a .schem file");
        assertSays(admin, "duels arena import desert desert.schem", "This needs FastAsyncWorldEdit or WorldEdit.");
        assertSays(admin, "duels arena import pit desert.schem", "Arena pit already exists.");
    }

    @Test
    void aCopyIsRebuiltFromItsArenasSnapshot() {
        assertSays(admin, "duels arena pregen pit 1", "Made 1 copies");
        Arena copy = duels.arenas().get("pit-1").orElseThrow();
        gold(copy).setType(Material.STONE);

        assertTrue(await(duels.instances().reset(copy)) > 0);

        assertEquals(Material.GOLD_BLOCK, gold(copy).getType());
    }
}
