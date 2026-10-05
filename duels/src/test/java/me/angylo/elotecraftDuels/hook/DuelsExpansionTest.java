package me.angylo.elotecraftDuels.hook;

import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftAPI.CleanupListener;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Placeholders read caches only; checked without PlaceholderAPI running. */
class DuelsExpansionTest {

    private ServerMock server;
    private Duels duels;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.getPluginManager().registerEvents(new CleanupListener(), MockBukkit.createMockPlugin("ElotecraftAPI"));
        PluginMock plugin = MockBukkit.createMockPlugin("ElotecraftDuels");
        server.addSimpleWorld("world");
        duels = Duels.start(plugin);
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
        Kit kit = new Kit("sword", "Sword", Material.DIAMOND_SWORD, null, List.of(ItemStack.of(Material.DIAMOND_SWORD)), false);
        duels.kits().update(kit);
        duels.queues().toggle(alex, kit, true);
        DuelsExpansion expansion = new DuelsExpansion(duels);

        assertEquals("duels", expansion.getIdentifier());
        assertEquals("0", expansion.onRequest(alex, "active_matches"));
        assertEquals("0", expansion.onRequest(alex, "wins"));
        assertEquals("0", expansion.onRequest(alex, "win_rate"));
        assertEquals("1000", expansion.onRequest(alex, "elo"));
        assertEquals("false", expansion.onRequest(alex, "in_match"));
        assertEquals("", expansion.onRequest(alex, "opponent"));
        assertEquals("sword", expansion.onRequest(alex, "queue"));
        assertEquals("ranked", expansion.onRequest(alex, "queue_type"));
        assertEquals("", expansion.onRequest(null, "wins"));
        assertNull(expansion.onRequest(alex, "unknown"));
        assertEquals(13, expansion.getPlaceholders().size());
    }
}
