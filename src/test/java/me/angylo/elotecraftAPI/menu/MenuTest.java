package me.angylo.elotecraftAPI.menu;

import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MenuListener is not covered: MockBukkit 4.116.3 does not implement Inventory#getHolder(boolean),
 * so these tests call {@link Menu#handleClick} directly.
 */
class MenuTest {

    private PluginMock plugin;
    private PlayerMock player;
    private final List<String> clicks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void buttonReceivesPlayerAndClickType() {
        List<Player> clickers = new ArrayList<>();
        Menu menu = new Menu(plugin, 1, "Test").set(0, Button.of(new ItemStack(Material.STONE), (clicker, click) -> {
            clickers.add(clicker);
            clicks.add(click.name());
        }));
        menu.open(player);

        menu.handleClick(player.simulateInventoryClick(0));

        assertSame(player, clickers.getFirst());
        assertEquals(List.of(ClickType.LEFT.name()), clicks);
    }

    @Test
    void displayItemReplacesButton() {
        Menu menu = new Menu(plugin, 1, "Test")
                .set(0, Button.of(new ItemStack(Material.STONE), (clicker, click) -> clicks.add("stone")))
                .set(0, new ItemStack(Material.DIRT));
        menu.open(player);

        menu.handleClick(player.simulateInventoryClick(0));

        assertEquals(Material.DIRT, menu.getInventory().getItem(0).getType());
        assertTrue(clicks.isEmpty());
    }

    @Test
    void fillOnlyTouchesEmptySlots() {
        Menu menu = new Menu(plugin, 1, "Test")
                .set(0, Button.of(new ItemStack(Material.STONE), (clicker, click) -> clicks.add("stone")))
                .fill(new ItemStack(Material.GLASS_PANE));
        menu.open(player);

        menu.handleClick(player.simulateInventoryClick(0));
        menu.handleClick(player.simulateInventoryClick(8));

        assertEquals(Material.STONE, menu.getInventory().getItem(0).getType());
        assertEquals(Material.GLASS_PANE, menu.getInventory().getItem(8).getType());
        assertEquals(List.of("stone"), clicks);
    }

    @Test
    void throwingButtonIsContained() {
        Menu menu = new Menu(plugin, 1, "Test").set(0, Button.of(new ItemStack(Material.STONE), (clicker, click) -> {
            throw new IllegalStateException("boom");
        }));
        menu.open(player);

        assertDoesNotThrow(() -> menu.handleClick(player.simulateInventoryClick(0)));
    }

    @Test
    void buttonCopiesItsItem() {
        ItemStack item = new ItemStack(Material.STONE);
        Button button = Button.display(item);

        item.setAmount(5);
        button.item().setAmount(7);

        assertEquals(1, button.item().getAmount());
    }

    @Test
    void stringTitleIsMiniMessage() {
        new Menu(plugin, 1, "<red>Shop").open(player);

        assertEquals("Shop", Text.plain(player.getOpenInventory().title()));
    }

    @Test
    void rejectsInvalidRows() {
        assertThrows(IllegalArgumentException.class, () -> new Menu(plugin, 0, Component.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Menu(plugin, 7, Component.empty()));
    }
}
