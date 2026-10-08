package me.angylo.elotecraftDuels.hook;

import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftAPI.CleanupListener;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Placeholders read caches only; checked without PlaceholderAPI running. */
class DuelsExpansionTest {

    private ServerMock server;
    private Duels duels;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.getPluginManager().registerEvents(new CleanupListener(), MockBukkit.createMockPlugin("ElotecraftAPI"));
        PluginMock plugin = MockBukkit.createMockPlugin("ElotecraftDuels");
        world = server.addSimpleWorld("world");
        duels = Duels.start(plugin);
        // Tables first: a player joining before them is loaded later, after this test reads the cache. The
        // future completes on the main thread, so tick rather than block.
        CompletableFuture<?> ready = duels.ready();
        while (!ready.isDone()) {
            server.getScheduler().performOneTick();
        }
    }

    @AfterEach
    void tearDown() {
        try {
            duels.shutdown();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void answersFromCachedStatsAndQueues() {
        PlayerMock alex = server.addPlayer("Alex");
        Kit kit = new Kit("sword", "Sword", Material.DIAMOND_SWORD, null, List.of(ItemStack.of(Material.DIAMOND_SWORD)), false, Set.of(), true);
        duels.kits().update(kit);
        readyArena();
        duels.queues().toggle(alex, kit, true);
        DuelsExpansion expansion = new DuelsExpansion(duels);

        assertEquals("duels", expansion.getIdentifier());
        assertEquals("0", expansion.onRequest(alex, "active_matches"));
        assertEquals("0", expansion.onRequest(alex, "wins"));
        assertEquals("0", expansion.onRequest(alex, "win_rate"));
        assertEquals("1000", expansion.onRequest(alex, "elo"));
        assertEquals("1000", expansion.onRequest(alex, "elo_sword"));
        assertEquals("Bronze", expansion.onRequest(alex, "division"));
        assertEquals("Bronze", expansion.onRequest(alex, "division_sword"));
        assertNull(expansion.onRequest(alex, "elo_nope"));
        assertEquals("false", expansion.onRequest(alex, "in_match"));
        assertEquals("", expansion.onRequest(alex, "opponent"));
        assertEquals("sword", expansion.onRequest(alex, "queue"));
        assertEquals("ranked", expansion.onRequest(alex, "queue_type"));
        assertEquals("0", expansion.onRequest(alex, "party_size"));
        assertEquals("", expansion.onRequest(alex, "party_leader"));
        assertEquals("", expansion.onRequest(null, "wins"));
        assertNull(expansion.onRequest(alex, "unknown"));
        assertEquals(25, expansion.getPlaceholders().size());
    }

    /** Queues need a ready arena for the kit. */
    private void readyArena() {
        duels.arenas().create("pit", new Location(world, 0, 64, 0)).join();
        duels.arenas().update(duels.arenas().get("pit").orElseThrow()
                .withSpawn(1, new Arena.Position(1.5, 64, 1.5, 0, 0))
                .withSpawn(2, new Arena.Position(5.5, 64, 1.5, 0, 0))
                .withCorner(1, new Arena.Position(0, 60, 0, 0, 0))
                .withCorner(2, new Arena.Position(8, 70, 8, 0, 0)));
    }
}
