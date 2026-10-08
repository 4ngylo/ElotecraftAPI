package me.angylo.elotecraftAPI.menu;

import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
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
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuConfigTest {

    private static final String SHOP = """
            shop:
              title: "<gold>Shop"
              rows: 3
              fill: GRAY_STAINED_GLASS_PANE
              items:
                diamond:
                  slot: 13
                  material: DIAMOND
                  amount: 2
                  name: "<aqua>Diamonds"
                  lore: ["<gray>Click to buy"]
                  action: buy
                info:
                  slots: [10, 16]
                  material: BOOK
            """;

    private PluginMock plugin;
    private PlayerMock player;
    private final List<String> clicks = new ArrayList<>();
    private final Map<String, BiConsumer<Player, ClickType>> actions = Map.of("buy", (clicker, click) -> clicks.add("buy"));

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

    private Menu load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return MenuConfig.load(plugin, config.getConfigurationSection("shop"), actions);
    }

    @Test
    void buildsMenuFromYaml() throws InvalidConfigurationException {
        Menu menu = load(SHOP);
        menu.open(player);
        Inventory inventory = menu.getInventory();

        assertEquals("Shop", Text.plain(player.getOpenInventory().title()));
        assertEquals(27, inventory.getSize());
        assertEquals(Material.DIAMOND, inventory.getItem(13).getType());
        assertEquals(2, inventory.getItem(13).getAmount());
        assertEquals("Diamonds", Text.plain(inventory.getItem(13).getItemMeta().displayName()));
        assertEquals(Material.BOOK, inventory.getItem(10).getType());
        assertEquals(Material.BOOK, inventory.getItem(16).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(0).getType());

        menu.handleClick(player.simulateInventoryClick(13));
        menu.handleClick(player.simulateInventoryClick(10));
        assertEquals(List.of("buy"), clicks);
    }

    @Test
    void buildsSingleItemWithPlaceholders() throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                button:
                  material: BOW
                  amount: 3
                  name: "<gold><kit>"
                  lore: ["<gray>Queued: <queued>"]
                """);

        ItemStack item = MenuConfig.item(config.getConfigurationSection("button"),
                Placeholder.unparsed("kit", "<red>Archer"), Placeholder.unparsed("queued", "4"));

        assertEquals(Material.BOW, item.getType());
        assertEquals(3, item.getAmount());
        assertEquals("<red>Archer", Text.plain(item.getItemMeta().displayName()));
        assertEquals("Queued: 4", Text.plain(item.getItemMeta().lore().getFirst()));
        // A bow's attributes and a potion's "No Effects" would show under the lore.
        assertTrue(item.getItemMeta().hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
        assertThrows(IllegalArgumentException.class, () -> MenuConfig.item(null));
    }

    @Test
    void rejectsBadConfigWithItsPath() {
        IllegalArgumentException material = assertThrows(IllegalArgumentException.class,
                () -> load(SHOP.replace("material: DIAMOND", "material: DIAMONDZ")));
        IllegalArgumentException slot = assertThrows(IllegalArgumentException.class,
                () -> load(SHOP.replace("slot: 13", "slot: 27")));
        IllegalArgumentException action = assertThrows(IllegalArgumentException.class,
                () -> load(SHOP.replace("action: buy", "action: sell")));

        assertTrue(material.getMessage().contains("shop.items.diamond.material"));
        assertTrue(slot.getMessage().contains("must be 0 to 26"));
        assertTrue(action.getMessage().contains("Unknown action 'sell'"));
        assertThrows(IllegalArgumentException.class, () -> MenuConfig.load(plugin, null, actions));
    }
}
