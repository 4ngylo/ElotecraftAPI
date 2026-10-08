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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /duel customkit}: a button per custom kit slot, a click building that kit in the {@link KitEditor}
 * (menus.yml {@code custom-kits}); and, while building one, {@code /duel customkit items}: the base kit's items, a click
 * putting a copy in the player's inventory and a click on an item in their inventory taking it out
 * (menus.yml {@code custom-kit-items}).
 */
public final class CustomKitMenu implements Listener {

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final CustomKits customKits;
    private final KitEditor editor;

    public CustomKitMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings,
                         CustomKits customKits, KitEditor editor) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.customKits = customKits;
        this.editor = editor;
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
            for (int slot = 1; slot <= customKits.slots(); slot++) {
                String command = "duel customkit " + slot;
                buttons.add(Button.of(slotIcon(section, base.get(), player, slot), MenuLayout.choose(plugin, effects,
                        clicker -> clicker.performCommand(command))));
            }
            menu.items(buttons);
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, clicker -> { }));
            MenuLayout.fill(menu, section);
            menu.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "custom-kits", e);
        }
    }

    private ItemStack slotIcon(ConfigurationSection section, Kit base, Player player, int slot) {
        List<ItemStack> items = customKits.items(player, slot);
        long filled = items.stream().filter(item -> !item.isEmpty()).count();
        TagResolver slotTag = Placeholder.unparsed("slot", String.valueOf(slot));
        if (filled == 0) {
            return MenuConfig.item(section.getConfigurationSection("empty"), slotTag);
        }
        Component state = MenuLayout.value(section, CustomKits.fits(base, items) ? "ready" : "outdated");
        return MenuLayout.icon(base.icon(), section.getConfigurationSection("kit"), "lore", false, slotTag,
                Placeholder.unparsed("items", String.valueOf(filled)), Placeholder.component("state", state));
    }

    /** The base kit's items, for a player building a custom kit. */
    public void openItems(Player player) {
        Optional<Kit> base = editor.buildingFrom(player);
        if (base.isEmpty()) {
            messages.send(player, "custom-kit.not-building");
            return;
        }
        ConfigurationSection section = menus.get().getConfigurationSection("custom-kit-items");
        try {
            Effects effects = settings.get().effects();
            ItemsMenu menu = MenuLayout.frame(section, (rows, title) -> new ItemsMenu(plugin, rows, title));
            menu.items(CustomKits.palette(base.get()).stream().map(item -> Button.of(item, (clicker, click) -> {
                effects.play(clicker, "menu-click");
                // A whole stack in an empty slot: nothing to merge it with.
                int free = clicker.getInventory().firstEmpty();
                if (free < 0) {
                    messages.send(clicker, "custom-kit.inventory-full");
                } else {
                    clicker.getInventory().setItem(free, item.clone());
                }
            })).toList());
            MenuLayout.place(menu, section, "clear", (clicker, click) -> {
                effects.play(clicker, "menu-click");
                clicker.getInventory().clear();
            });
            MenuLayout.place(menu, section, "save", MenuLayout.choose(plugin, effects, clicker -> clicker.performCommand("duel editkit save")));
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, clicker -> { }));
            MenuLayout.fill(menu, section);
            menu.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "custom-kit-items", e);
        }
    }

    /**
     * A click on an item in the player's own inventory while the items menu is open takes it out. After
     * {@code MenuListener}, which cancels every click while a menu is open.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ItemsMenu && event.getRawSlot() >= top.getSize()
                && event.getWhoClicked() instanceof Player player && editor.buildingFrom(player).isPresent()) {
            event.setCancelled(true);
            event.getView().setItem(event.getRawSlot(), null);
        }
    }

    /** The items menu, told apart from the others so clicks below it take items out. */
    private static final class ItemsMenu extends InMatchMenu {

        ItemsMenu(Plugin plugin, int rows, Component title) {
            super(plugin, rows, title);
        }
    }
}
