package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.input.ChatInput;
import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.ItemBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Builds the menus.yml parts the menus share: list menus (centered entries, bottom-row buttons), menus of buttons
 * at fixed slots, entry icons and the confirm menu.
 */
final class MenuLayout {

    private static final int MIN_ROWS = 2;
    private static final int MAX_ROWS = 6;
    private static final Duration ANSWER_TIME = Duration.ofSeconds(60);
    private static final String CONFIRM = "confirm";

    private MenuLayout() {
    }

    /**
     * A list menu with the section's title, rows and page arrows; entries are centered unless the section sets
     * {@code centered: false}. Tags in the title are filled from {@code tags}.
     *
     * @throws IllegalArgumentException if the section is missing or invalid
     */
    static PaginatedMenu frame(Plugin plugin, ConfigurationSection section, TagResolver... tags) {
        return frame(section, (rows, title) -> new PaginatedMenu(plugin, rows, title), tags);
    }

    /** Like {@link #frame(Plugin, ConfigurationSection, TagResolver...)}, with the menu made by {@code create} from rows and title. */
    static <M extends PaginatedMenu> M frame(ConfigurationSection section, BiFunction<Integer, Component, M> create, TagResolver... tags) {
        M menu = create.apply(rows(section, MIN_ROWS), Text.mm(section.getString("title", ""), tags));
        if (section.getBoolean("centered", true)) {
            menu.centered();
        }
        if (section.isConfigurationSection("previous")) {
            menu.previousButton(MenuConfig.item(section.getConfigurationSection("previous")));
        }
        if (section.isConfigurationSection("next")) {
            menu.nextButton(MenuConfig.item(section.getConfigurationSection("next")));
        }
        return menu;
    }

    /**
     * A menu of buttons at fixed slots, with the section's title and rows (1 to 6). Tags in the title are filled from {@code tags}.
     *
     * @throws IllegalArgumentException if the section is missing or invalid
     */
    static Menu fixed(Plugin plugin, ConfigurationSection section, TagResolver... tags) {
        return new Menu(plugin, rows(section, 1), Text.mm(section.getString("title", ""), tags));
    }

    private static int rows(ConfigurationSection section, int min) {
        if (section == null) {
            throw new IllegalArgumentException("Missing menu section in menus.yml");
        }
        int rows = section.getInt("rows", 3);
        if (rows < min || rows > MAX_ROWS) {
            throw new IllegalArgumentException(section.getCurrentPath() + ".rows must be " + min + " to " + MAX_ROWS);
        }
        return rows;
    }

    /**
     * Places the button {@code key} in a list menu's bottom row at its {@code slot} (1 to 7), or with {@code row: top}
     * in its top row (0 to 8, centered menus only), if it is configured. Tags in its name and lore are filled from {@code tags}.
     */
    static void place(PaginatedMenu menu, ConfigurationSection section, String key, BiConsumer<Player, ClickType> action,
                      TagResolver... tags) {
        ConfigurationSection button = section.getConfigurationSection(key);
        if (button == null) {
            return;
        }
        boolean top = "top".equalsIgnoreCase(button.getString("row", "bottom"));
        int slot = button.getInt("slot", -1);
        int first = top ? 0 : 1;
        int last = top ? 8 : 7;
        if (slot < first || slot > last) {
            throw new IllegalArgumentException(button.getCurrentPath() + ".slot must be " + first + " to " + last);
        }
        menu.set((top ? 0 : menu.getInventory().getSize() - 9) + slot, Button.of(MenuConfig.item(button, tags), action));
    }

    /**
     * Places the button {@code key} in the bottom row of a menu laid out like an inventory (kit preview, fight inventory)
     * at its {@code slot} (0 to 8), if it is configured. Tags in its name and lore are filled from {@code tags}.
     */
    static void bottom(Menu menu, ConfigurationSection section, String key, BiConsumer<Player, ClickType> action, TagResolver... tags) {
        ConfigurationSection button = section.getConfigurationSection(key);
        if (button == null) {
            return;
        }
        int slot = button.getInt("slot", -1);
        if (slot < 0 || slot > 8) {
            throw new IllegalArgumentException(button.getCurrentPath() + ".slot must be 0 to 8");
        }
        menu.set(menu.getInventory().getSize() - 9 + slot, Button.of(MenuConfig.item(button, tags), action));
    }

    /** Places the button {@code key} at its {@code slot}, if it is configured, with the item it describes. */
    static void put(Menu menu, ConfigurationSection section, String key, BiConsumer<Player, ClickType> action, TagResolver... tags) {
        ConfigurationSection button = section.getConfigurationSection(key);
        if (button != null) {
            menu.set(slot(menu, button), Button.of(MenuConfig.item(button, tags), action));
        }
    }

    /** Places the button {@code key} at its {@code slot}, if it is configured, showing {@code icon}. */
    static void put(Menu menu, ConfigurationSection section, String key, ItemStack icon, BiConsumer<Player, ClickType> action) {
        ConfigurationSection button = section.getConfigurationSection(key);
        if (button != null) {
            menu.set(slot(menu, button), Button.of(icon, action));
        }
    }

    private static int slot(Menu menu, ConfigurationSection button) {
        int slot = button.getInt("slot", -1);
        if (slot < 0 || slot >= menu.getInventory().getSize()) {
            throw new IllegalArgumentException(button.getCurrentPath() + ".slot must be 0 to " + (menu.getInventory().getSize() - 1));
        }
        return slot;
    }

    /** An entry's icon with the template's name and the lore list {@code loreKey}. */
    static ItemStack icon(Material material, ConfigurationSection template, String loreKey, boolean glint, TagResolver... tags) {
        if (template == null) {
            throw new IllegalArgumentException("Missing entry template in menus.yml");
        }
        return ItemBuilder.of(material)
                .name(Text.mm(template.getString("name", ""), tags))
                .lore(template.getStringList(loreKey).stream().map(line -> Text.mm(line, tags)).toArray(Component[]::new))
                .hideDetails()
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

    /** A click that only closes the menu. */
    static BiConsumer<Player, ClickType> close(Plugin plugin, Effects effects) {
        return choose(plugin, effects, player -> { });
    }

    /**
     * A click that closes the menu and runs the button {@code key}'s {@code command} as the player (without the /),
     * e.g. a back button's {@code duel menu play}; without one it only closes, or does nothing with {@code close: false}.
     */
    static BiConsumer<Player, ClickType> command(Plugin plugin, Effects effects, ConfigurationSection section, String key) {
        String command = section.getString(key + ".command", "").strip();
        if (command.isEmpty() && !section.getBoolean(key + ".close", true)) {
            return (player, click) -> { };
        }
        return choose(plugin, effects, player -> {
            if (!command.isEmpty()) {
                player.performCommand(command);
            }
        });
    }

    /**
     * A click that opens the menus.yml {@code confirm} menu for {@code what} (the clicked button's name);
     * its accept button runs {@code onYes}, its deny button {@code onNo} (usually reopening the menu it came from).
     */
    static BiConsumer<Player, ClickType> confirm(Plugin plugin, Messages messages, ConfigFile menus, Effects effects, Component what,
                                                 Consumer<Player> onYes, Consumer<Player> onNo) {
        return choose(plugin, effects, player -> {
            try {
                ConfigurationSection section = menus.get().getConfigurationSection(CONFIRM);
                TagResolver action = Placeholder.component("action", what);
                Menu menu = fixed(plugin, section, action);
                put(menu, section, "what", (clicker, click) -> { }, action);
                put(menu, section, "accept", choose(plugin, effects, onYes), action);
                put(menu, section, "deny", choose(plugin, effects, onNo), action);
                menu.open(player);
            } catch (IllegalArgumentException e) {
                menuError(plugin, messages, player, CONFIRM, e);
            }
        });
    }

    /**
     * Asks {@code player} in chat, for the admin menus; {@code onAnswer} runs on the main thread, never after
     * cancel, timeout or quit.
     */
    static void ask(Plugin plugin, Messages messages, Player player, String promptKey, TagResolver[] tags, Consumer<String> onAnswer) {
        ChatInput.ask(plugin, player, messages.get(player, promptKey, tags), ANSWER_TIME)
                .thenAccept(answer -> answer.filter(text -> player.isOnline()).ifPresent(onAnswer))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Menu chat answer failed", error);
                    return null;
                });
    }

    /** Logs a broken menus.yml section and tells the viewer. */
    static void menuError(Plugin plugin, Messages messages, Player viewer, String key, IllegalArgumentException e) {
        plugin.getLogger().log(Level.WARNING, "Invalid " + key + " menu in menus.yml: " + e.getMessage());
        messages.send(viewer, "general.menu-error");
    }

    /** The item material named by {@code template}'s {@code key}. */
    static Material material(ConfigurationSection template, String key) {
        String raw = template == null ? "" : template.getString(key, "");
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException((template == null ? key : template.getCurrentPath() + "." + key) + " is not an item: '" + raw + "'");
        }
        return material;
    }

    /** The name of the button {@code key}, as {@link #confirm} shows it; the key itself if it has none. */
    static Component name(ConfigurationSection section, String key, TagResolver... tags) {
        return Text.mm(section.getString(key + ".name", key), tags);
    }

    /** A text from the section's {@code values}, in MiniMessage. */
    static Component value(ConfigurationSection section, String key) {
        return Text.mm(section.getString("values." + key, key));
    }

    static TagResolver[] with(TagResolver[] tags, TagResolver... more) {
        TagResolver[] all = Arrays.copyOf(tags, tags.length + more.length);
        System.arraycopy(more, 0, all, tags.length, more.length);
        return all;
    }
}
