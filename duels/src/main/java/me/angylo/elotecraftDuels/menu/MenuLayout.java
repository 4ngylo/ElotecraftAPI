package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ItemBuilder;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Builds the menus.yml parts both menus share: page frame, bottom-row buttons and entry icons. */
final class MenuLayout {

    private static final int MIN_ROWS = 2;
    private static final int MAX_ROWS = 6;

    private MenuLayout() {
    }

    /**
     * A paginated menu with the section's title, rows and page arrows.
     *
     * @throws IllegalArgumentException if the section is missing or invalid
     */
    static PaginatedMenu frame(Plugin plugin, ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("Missing menu section in menus.yml");
        }
        int rows = section.getInt("rows", 4);
        if (rows < MIN_ROWS || rows > MAX_ROWS) {
            throw new IllegalArgumentException(section.getCurrentPath() + ".rows must be " + MIN_ROWS + " to " + MAX_ROWS);
        }
        PaginatedMenu menu = new PaginatedMenu(plugin, rows, section.getString("title", ""));
        if (section.isConfigurationSection("previous")) {
            menu.previousButton(MenuConfig.item(section.getConfigurationSection("previous")));
        }
        if (section.isConfigurationSection("next")) {
            menu.nextButton(MenuConfig.item(section.getConfigurationSection("next")));
        }
        return menu;
    }

    /** Places the button {@code key} in the bottom row at its {@code slot} (1 to 7), if it is configured. */
    static void place(PaginatedMenu menu, ConfigurationSection section, String key, BiConsumer<Player, ClickType> action) {
        ConfigurationSection button = section.getConfigurationSection(key);
        if (button == null) {
            return;
        }
        int slot = button.getInt("slot", -1);
        if (slot < 1 || slot > 7) {
            throw new IllegalArgumentException(button.getCurrentPath() + ".slot must be 1 to 7");
        }
        menu.set(menu.getInventory().getSize() - 9 + slot, Button.of(MenuConfig.item(button), action));
    }

    /** Fills empty slots with the {@code filler} item, if configured. Call last. */
    static void fill(PaginatedMenu menu, ConfigurationSection section) {
        if (section.isConfigurationSection("filler")) {
            menu.fill(MenuConfig.item(section.getConfigurationSection("filler")));
        }
    }

    /** An entry's icon with the template's name and the lore list {@code loreKey}. */
    static ItemStack icon(Material material, ConfigurationSection template, String loreKey, boolean glint, TagResolver... tags) {
        if (template == null) {
            throw new IllegalArgumentException("Missing entry template in menus.yml");
        }
        return ItemBuilder.of(material)
                .name(Text.mm(template.getString("name", ""), tags))
                .lore(template.getStringList(loreKey).stream().map(line -> Text.mm(line, tags)).toArray(Component[]::new))
                .flags(ItemFlag.HIDE_ATTRIBUTES)
                .glint(glint ? Boolean.TRUE : null)
                .build();
    }

    /**
     * A click that plays the menu sound, closes the menu and then runs {@code action}. Both happen a tick
     * later, since inventories must not be closed or opened inside a click event.
     */
    static BiConsumer<Player, ClickType> choose(Plugin plugin, Effects effects, Consumer<Player> action) {
        return (player, click) -> {
            effects.play(player, "menu-click");
            Tasks.sync(plugin, () -> {
                player.closeInventory();
                action.accept(player);
            });
        };
    }
}
