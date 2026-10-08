package me.angylo.elotecraftDuels.hud;

import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.menu.SpectateMenu;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * The item in a watcher's inventory (a spectator, or a knocked-out fighter, in spectator mode) that opens the
 * fighter menu when clicked: spectator mode has no usable hotbar, but the inventory opens and its clicks reach
 * the server. Given and taken away from {@link #tick}; the player's own inventory comes back when they stop
 * watching, as it does for any match. Layout in menus.yml {@code spectate-fighters.item}. Main thread only.
 */
public final class WatchItem implements Listener {

    private static final int INVENTORY_SIZE = 36;

    private record Entry(int slot, ItemStack item) {
    }

    private final Plugin plugin;
    private final ConfigFile menus;
    private final MatchManager matches;
    private final SpectateMenu spectateMenu;
    private final NamespacedKey key;
    /** Rebuilt when menus.yml was reloaded; null when the item is missing or invalid. */
    private FileConfiguration loadedFrom;
    private Entry entry;

    public WatchItem(Plugin plugin, ConfigFile menus, MatchManager matches, SpectateMenu spectateMenu) {
        this.plugin = plugin;
        this.menus = menus;
        this.matches = matches;
        this.spectateMenu = spectateMenu;
        this.key = new NamespacedKey(plugin, "watch-item");
    }

    /** Call every second. */
    public void tick() {
        Bukkit.getOnlinePlayers().forEach(this::sync);
    }

    /** Gives a watcher the item in its slot, if the slot is free of it; anyone else loses it. */
    void sync(Player player) {
        Entry wanted = watching(player).isPresent() && player.getGameMode() == GameMode.SPECTATOR ? entry() : null;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (tagged(inventory.getItem(slot)) && (wanted == null || slot != wanted.slot())) {
                inventory.setItem(slot, null);
            }
        }
        if (wanted != null && !tagged(inventory.getItem(wanted.slot()))) {
            inventory.setItem(wanted.slot(), wanted.item().clone());
        }
    }

    private Optional<Match> watching(Player player) {
        return matches.matchOf(player).filter(match -> match.isWatching(player));
    }

    private Entry entry() {
        FileConfiguration file = menus.get();
        if (file != loadedFrom) {
            loadedFrom = file;
            entry = load(file.getConfigurationSection("spectate-fighters.item"));
        }
        return entry;
    }

    private Entry load(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        try {
            int slot = section.getInt("slot", -1);
            if (slot < 0 || slot >= INVENTORY_SIZE) {
                throw new IllegalArgumentException("slot must be 0 to " + (INVENTORY_SIZE - 1));
            }
            ItemStack item = MenuConfig.item(section);
            item.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true));
            return new Entry(slot, item);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("menus.yml spectate-fighters.item left out: " + e.getMessage());
            return null;
        }
    }

    private boolean tagged(ItemStack stack) {
        return stack != null && stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(key);
    }

    /** A click on the item opens the fighter menu; the item never moves. Not ignoring cancelled: protections may cancel first. */
    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        PlayerInventory own = event.getWhoClicked().getInventory();
        boolean swapsTagged = (event.getHotbarButton() >= 0 && tagged(own.getItem(event.getHotbarButton())))
                || (event.getClick() == ClickType.SWAP_OFFHAND && tagged(own.getItemInOffHand()));
        if (!tagged(event.getCurrentItem()) && !tagged(event.getCursor()) && !swapsTagged) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && tagged(event.getCurrentItem())) {
            // Inventories must not be opened inside a click event.
            Tasks.sync(plugin, () -> watching(player).ifPresent(match -> spectateMenu.openFighters(player, match)));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (tagged(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (tagged(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (tagged(event.getMainHandItem()) || tagged(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }
}
