package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.input.ChatInput;
import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ItemBuilder;
import me.angylo.elotecraftAPI.util.Messages;
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

import java.time.Duration;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Builds the menus.yml parts the menus share: page frame, bottom-row buttons and entry icons. */
final class MenuLayout {

    private static final int MIN_ROWS = 2;
    private static final int MAX_ROWS = 6;
    private static final Duration ANSWER_TIME = Duration.ofSeconds(60);

    private MenuLayout() {
    }

    /**
     * A paginated menu with the section's title, rows and page arrows. Tags in the title are filled from {@code tags}.
     *
     * @throws IllegalArgumentException if the section is missing or invalid
     */
    static PaginatedMenu frame(Plugin plugin, ConfigurationSection section, TagResolver... tags) {
        if (section == null) {
            throw new IllegalArgumentException("Missing menu section in menus.yml");
        }
        int rows = section.getInt("rows", 4);
        if (rows < MIN_ROWS || rows > MAX_ROWS) {
            throw new IllegalArgumentException(section.getCurrentPath() + ".rows must be " + MIN_ROWS + " to " + MAX_ROWS);
        }
        PaginatedMenu menu = new PaginatedMenu(plugin, rows, Text.mm(section.getString("title", ""), tags));
        if (section.isConfigurationSection("previous")) {
            menu.previousButton(MenuConfig.item(section.getConfigurationSection("previous")));
        }
        if (section.isConfigurationSection("next")) {
            menu.nextButton(MenuConfig.item(section.getConfigurationSection("next")));
        }
        return menu;
    }

    /**
     * Places the button {@code key} in the bottom row at its {@code slot} (1 to 7), if it is configured.
     * Tags in its name and lore are filled from {@code tags}.
     */
    static void place(PaginatedMenu menu, ConfigurationSection section, String key, BiConsumer<Player, ClickType> action,
                      TagResolver... tags) {
        ConfigurationSection button = section.getConfigurationSection(key);
        if (button == null) {
            return;
        }
        int slot = button.getInt("slot", -1);
        if (slot < 1 || slot > 7) {
            throw new IllegalArgumentException(button.getCurrentPath() + ".slot must be 1 to 7");
        }
        menu.set(menu.getInventory().getSize() - 9 + slot, Button.of(MenuConfig.item(button, tags), action));
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