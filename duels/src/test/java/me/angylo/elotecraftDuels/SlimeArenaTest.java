package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.hook.SlimeWorlds;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arenas in AdvancedSlimePaper worlds, against a fake ASP API: every duel gets its own copy. */
class SlimeArenaTest extends DuelsTestBase {

    private SlimeWorlds slime;
    private World template;
    private Arena arena;
    private Kit kit;

    @BeforeEach
    void setUpTemplate() {
        slime = duels.slime().orElseThrow();
        template = await(slime.create("desert"));
        await(duels.arenas().create("dunes", new Location(template, 0, 64, 0)));
        arena = duels.arenas().get("dunes").orElseThrow()
                .withSpawn(1, new Position(5.5, 64, 5.5, 0, 0))
                .withSpawn(2, new Position(15.5, 64, 5.5, 180, 0))
                .withCorner(1, new Position(0, 60, 0, 0, 0))
                .withCorner(2, new Position(20, 80, 20, 0, 0));
        await(duels.arenas().update(arena));
        kit = buildKit();
    }

    @Test
    void createdWorldsAreSavedTemplates() {
        assertTrue(slime.isTemplate("desert"));
        assertTrue(Files.exists(plugin.getDataFolder().toPath().resolve("slime-worlds/desert.slime")));
        assertEquals(Material.STONE, template.getBlockAt(0, 63, 0).getType());
        assertTrue(arena.isReady());
    }

    @Test
    void everyDuelRunsInItsOwnCopyThatIsUnloadedAfterwards() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        TestPlayer sam = join("Sam");
        TestPlayer kai = join("Kai");

        assertTrue(duels.matches().start(alex, steve, kit, arena));
        assertTrue(duels.matches().start(sam, kai, kit, arena));
        Match first = duels.matches().matchOf(alex).orElseThrow();
        Match second = duels.matches().matchOf(sam).orElseThrow();
        tickUntil(() -> first.state() != Match.State.STARTING && second.state() != Match.State.STARTING);

        World copy = alex.getWorld();
        assertNotEquals(template, copy);
        assertNotEquals(copy, sam.getWorld());
        assertEquals(copy, steve.getWorld());
        assertTrue(first.contains(alex.getLocation()));
        assertFalse(first.contains(sam.getLocation()));
        assertEquals(2, duels.matches().activeMatches());

        tickUntil(() -> first.state() == Match.State.FIGHTING);
        assertFalse(alex.simulateBlockPlace(Material.OAK_PLANKS, new Location(copy, 6, 64, 7)).isCancelled());
        assertEquals(Material.AIR, template.getBlockAt(6, 64, 7).getType());
        assertTrue(duels.arenas().needingReset().isEmpty());

        String copyName = copy.getName();
        assertTrue(duels.matches().leave(alex));
        tickUntil(() -> Bukkit.getWorld(copyName) == null);
        assertEquals(world, alex.getWorld());
        assertEquals(1, duels.matches().activeMatches());
    }

    @Test
    void copiesPerArenaIsALimit() {
        setConfig("slime.copies-per-arena", 1);
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        TestPlayer sam = join("Sam");
        TestPlayer kai = join("Kai");

        assertTrue(duels.matches().start(alex, steve, kit, arena));

        assertFalse(duels.matches().isArenaFree(arena));
        assertFalse(duels.matches().start(sam, kai, kit, arena));
    }

    @Test
    void shutdownUnloadsCopies() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        String copyName = alex.getWorld().getName();

        duels.shutdown();
        duels = null;

        assertNull(Bukkit.getWorld(copyName));
        assertEquals(world, alex.getWorld());
    }

    @Test
    void templatesAreLoadedAgainOnStart() {
        duels.shutdown();
        assertTrue(Bukkit.unloadWorld(template, false));
        duels = Duels.start(plugin);
        await(duels.ready());

        tickUntil(() -> Bukkit.getWorld("desert") != null);
        assertTrue(duels.slime().orElseThrow().isTemplate("desert"));
        assertTrue(duels.arenas().get("dunes").orElseThrow().isReady());
    }
}
