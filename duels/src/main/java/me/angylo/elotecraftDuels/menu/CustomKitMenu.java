package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.CustomKits;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitEditor;
import me.angylo.elotecraftDuels.kit.KitLayouts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /duel customkit}: a button per custom kit slot, a click opening that kit in the {@link KitEditor}
 * (menus.yml {@code custom-kits}).
 */
public final class CustomKitMenu {

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final CustomKits customKits;

    public CustomKitMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, CustomKits customKits) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.customKits = customKits;
    }

    /** {@code player}'s custom kit slots. */
    public void openSlots(Player player) {
        Optional<Kit> base = customKits.base();
        if (base.isEmpty()) {
            messages.send(player, "custom-kit.disabled");
            return;
        }
        ConfigurationSection section = menus.get().getConfigurationSection("custom-kits");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            List<Button> buttons = new ArrayList<>();
            for (int slot = 1; slot <= customKits.slots(player); slot++) {
                String command = "duel customkit " + slot;
                buttons.add(Button.of(slotIcon(section, base.get(), player, slot), MenuLayout.choose(plugin, effects,
                        clicker -> clicker.performCommand(command))));
            }
            menu.items(buttons);
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "custom-kits", e);
        }
    }

    private ItemStack slotIcon(ConfigurationSection section, Kit base, Player player, int slot) {
        Optional<KitLayouts.Saved> saved = customKits.saved(player, slot);
        List<ItemStack> items = saved.map(KitLayouts.Saved::items).orElse(List.of());
        long filled = items.stream().filter(item -> !item.isEmpty()).count();
        TagResolver slotTag = Placeholder.unparsed("slot", String.valueOf(slot));
        if (filled == 0) {
            return MenuConfig.item(section.getConfigurationSection("empty"), slotTag);
        }
        Component state = MenuLayout.value(section, customKits.fits(items) ? "ready" : "outdated");
        TagResolver name = Placeholder.unparsed("name", saved.map(KitLayouts.Saved::name)
                .orElse(menus.get().getString("kit-editor.values.custom-name", "Custom kit <slot>").replace("<slot>", String.valueOf(slot))));
        return MenuLayout.icon(base.icon(), section.getConfigurationSection("kit"), "lore", false, slotTag, name,
                Placeholder.unparsed("items", String.valueOf(filled)), Placeholder.component("state", state));
    }
}
