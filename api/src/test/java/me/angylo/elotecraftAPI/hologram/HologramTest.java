package me.angylo.elotecraftAPI.hologram;

import me.angylo.elotecraftAPI.CleanupListener;
import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramTest {

    private ServerMock server;
    private PluginMock plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.getPluginManager().registerEvents(new CleanupListener(), MockBukkit.createMockPlugin("Api"));
        plugin = MockBukkit.createMockPlugin("Shop");
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spawnsFacingNonPersistentTextDisplay() {
        Hologram hologram = Hologram.spawn(plugin, new Location(world, 1, 65, 2), "<gold>Shop\n<gray>Open");

        TextDisplay display = hologram.entity();
        assertEquals("Shop\nOpen", Text.plain(display.text()));
        assertEquals(Display.Billboard.CENTER, display.getBillboard());
        assertFalse(display.isPersistent());
        assertEquals(1, world.getEntitiesByClass(TextDisplay.class).size());
    }

    @Test
    void updatesTextAndPosition() {
        Hologram hologram = Hologram.spawn(plugin, new Location(world, 0, 65, 0), "Open");

        hologram.text("<red>Closed").teleport(new Location(world, 5, 70, 5));

        assertEquals("Closed", Text.plain(hologram.entity().text()));
        assertEquals(5, hologram.entity().getLocation().getBlockX());
    }

    @Test
    void removeAndPluginDisableDeleteTheEntity() {
        Hologram removed = Hologram.spawn(plugin, new Location(world, 0, 65, 0), "a");
        Hologram onDisable = Hologram.spawn(plugin, new Location(world, 1, 65, 0), "b");
        assertTrue(removed.isValid());

        removed.remove();
        server.getPluginManager().disablePlugin(plugin);

        assertFalse(removed.isValid());
        assertFalse(onDisable.isValid());
    }

    @Test
    void rejectsLocationWithoutWorld() {
        assertThrows(IllegalArgumentException.class, () -> Hologram.spawn(plugin, new Location(null, 0, 0, 0), "a"));
    }
}
