package me.angylo.elotecraftAPI.menu;

import me.angylo.elotecraftAPI.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Builds a {@link Menu} from YAML, so layouts live in config and only actions live in code.
 * <pre>
 * shop:
 *   title: "&lt;gold&gt;Shop"
 *   rows: 3
 *   fill: GRAY_STAINED_GLASS_PANE      # optional
 *   items:
 *     diamond:
 *       slot: 13                       # or slots: [10, 11, 12]
 *       material: DIAMOND
 *       amount: 1                      # optional
 *       name: "&lt;aqua&gt;Diamonds"         # optional, MiniMessage
 *       lore: ["&lt;gray&gt;Click to buy"]   # optional
 *       glint: true                    # optional
 *       model: "elotecraft:gem"        # optional item model
 *       action: buy-diamond            # optional, a key of the actions map
 * </pre>
 * <pre>{@code
 * MenuConfig.load(plugin, config.getConfigurationSection("shop"), Map.of(
 *         "buy-diamond", (player, click) -> buy(player))).open(player);
 * }</pre>
 * Each call builds a new menu, so it is safe to load one per viewer.
 */
public final class MenuConfig {

    private MenuConfig() {
    }

    /**
     * @throws IllegalArgumentException if the section is missing or has an unknown material,
     *                                  an out-of-range slot or an action not in {@code actions}
     */
    public static Menu load(Plugin plugin, ConfigurationSection section, Map<String, BiConsumer<Player, ClickType>> actions) {
        if (section == null) {
            throw new IllegalArgumentException("Missing menu config section");
        }
        int rows = section.getInt("rows", 3);
        Menu menu = new Menu(plugin, rows, section.getString("title", ""));
        ConfigurationSection items = section.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(key);
                if (item == null) {
                    throw new IllegalArgumentException(items.getCurrentPath() + "." + key + " must be a section");
                }
                Button button = button(item, actions);
                for (int slot : slots(item, rows * 9)) {
                    menu.set(slot, button);
                }
            }
        }
        String fill = section.getString("fill");
        if (fill != null) {
            menu.fill(ItemBuilder.of(material(fill, section.getCurrentPath() + ".fill")).name(" ").build());
        }
        return menu;
    }

    private static Button button(ConfigurationSection item, Map<String, BiConsumer<Player, ClickType>> actions) {
        String path = item.getCurrentPath();
        ItemBuilder builder = ItemBuilder.of(material(item.getString("material", ""), path + ".material"))
                .amount(item.getInt("amount", 1));
        if (item.isString("name")) {
            builder.name(item.getString("name"));
        }
        if (item.isList("lore")) {
            builder.lore(item.getStringList("lore"));
        }
        if (item.isBoolean("glint")) {
            builder.glint(item.getBoolean("glint"));
        }
        String modelName = item.getString("model");
        if (modelName != null) {
            NamespacedKey model = NamespacedKey.fromString(modelName);
            if (model == null) {
                throw new IllegalArgumentException("Invalid model '" + modelName + "' at " + path + ".model");
            }
            builder.itemModel(model);
        }
        ItemStack stack = builder.build();
        String action = item.getString("action");
        if (action == null) {
            return Button.display(stack);
        }
        BiConsumer<Player, ClickType> handler = actions.get(action);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown action '" + action + "' at " + path + ".action; known: " + actions.keySet());
        }
        return Button.of(stack, handler);
    }

    private static List<Integer> slots(ConfigurationSection item, int size) {
        List<Integer> slots = item.isList("slots") ? item.getIntegerList("slots") : List.of(item.getInt("slot", -1));
        for (int slot : slots) {
            if (slot < 0 || slot >= size) {
                throw new IllegalArgumentException("Slot " + slot + " at " + item.getCurrentPath() + " must be 0 to " + (size - 1));
            }
        }
        return slots;
    }

    private static Material material(String name, String path) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Unknown item material '" + name + "' at " + path);
        }
        return material;
    }
}
