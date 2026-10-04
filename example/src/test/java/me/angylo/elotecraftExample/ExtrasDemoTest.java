package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.CleanupListener;
import me.angylo.elotecraftAPI.hud.Sidebar;
import me.angylo.elotecraftAPI.input.InputListener;
import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the Phase 1 demos through /example, like a player would. */
class ExtrasDemoTest {

    private ServerMock server;
    private ExampleCommand example;
    private PlayerMock player;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock api = MockBukkit.createMockPlugin("ElotecraftAPI");
        server.getPluginManager().registerEvents(new InputListener(), api);
        server.getPluginManager().registerEvents(new CleanupListener(), api);
        world = server.addSimpleWorld("world");
        example = ExampleCommand.register(MockBukkit.createMockPlugin("ElotecraftExample"));
        player = server.addPlayer();
        player.setOp(true);
    }

    @AfterEach
    void tearDown() {
        if (example != null) {
            example.shutdown();
        }
        MockBukkit.unmock();
    }

    private String nextMessage() {
        return Text.plain(Objects.requireNonNull(player.nextComponentMessage(), "no message"));
    }

    @Test
    void inputReadsTheNextChatMessageInThePlayersLanguage() {
        player.setLocale(Locale.forLanguageTag("es"));
        server.dispatchCommand(player, "example input");
        assertTrue(nextMessage().startsWith("Ejemplo » Escribe un apodo"));

        player.chat("Steve");
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performOneTick();

        assertEquals("Ejemplo » ¡Encantado, Steve!", nextMessage());
    }

    @Test
    void hudTogglesTheSidebar() {
        server.dispatchCommand(player, "example hud");
        assertTrue(Sidebar.of(player).isPresent());

        server.dispatchCommand(player, "example hud");
        assertFalse(Sidebar.of(player).isPresent());
    }

    @Test
    void hologramCountsDownThenDisappears() {
        player.teleport(world.getSpawnLocation());
        server.dispatchCommand(player, "example hologram");
        TextDisplay display = world.getEntitiesByClass(TextDisplay.class).iterator().next();
        assertTrue(Text.plain(display.text()).endsWith("Disappears in 15s"));

        server.getScheduler().performTicks(20);
        assertTrue(Text.plain(display.text()).endsWith("Disappears in 14s"));

        server.getScheduler().performTicks(20 * 15);
        assertFalse(display.isValid());
    }

    @Test
    void skullGivesYourHead() {
        server.dispatchCommand(player, "example skull");

        ItemStack head = Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull).findFirst().orElseThrow();
        assertEquals(Material.PLAYER_HEAD, head.getType());
        assertEquals(player.getName() + "'s head", Text.plain(head.getItemMeta().displayName()));
    }

    @Test
    void shopComesFromYamlAndItsClockRefreshes() {
        server.dispatchCommand(player, "example shop");

        assertEquals("Config Shop", Text.plain(player.getOpenInventory().title()));
        assertEquals(Material.DIAMOND, player.getOpenInventory().getTopInventory().getItem(11).getType());
        assertEquals(Material.CLOCK, player.getOpenInventory().getTopInventory().getItem(4).getType());
    }

    @Test
    void adminGroupHoldsCooldownAndReload() {
        server.dispatchCommand(player, "example admin reload");

        assertTrue(nextMessage().startsWith("Reloaded"));
    }
}
