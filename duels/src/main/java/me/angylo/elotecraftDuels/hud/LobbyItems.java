package me.angylo.elotecraftDuels.hud;

import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Hotbar items in lobby worlds (config.yml and menus.yml {@code lobby-items}) that run a command when
 * right-clicked, for practice servers. {@link #sync} keeps a player's items in line with their state every
 * second, on join, on world changes and after a click. Only items tagged as lobby items are ever placed,
 * swapped or removed, and a slot holding a player's own item is left alone. Main thread only.
 */
public final class LobbyItems implements Listener {

    private static final int HOTBAR_SIZE = 9;
    private static final long NEXT_TICK = 1;

    /** When an item shows: not queued, queued or waiting for an event, always, or never (a removed default). */
    private enum Show {
        IDLE, WAITING, ALWAYS, NEVER
    }

    private record Entry(String id, int slot, Show show, String permission, String command, ItemStack item) {
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final ConfigFile menus;
    private final MatchManager matches;
    private final QueueManager queues;
    private final NamespacedKey key;
    /** Rebuilt when menus.yml was reloaded. */
    private FileConfiguration loadedFrom;
    private List<Entry> entries = List.of();

    public LobbyItems(Plugin plugin, Supplier<Settings> settings, ConfigFile menus, MatchManager matches, QueueManager queues) {
        this.plugin = plugin;
        this.settings = settings;
        this.menus = menus;
        this.matches = matches;
        this.queues = queues;
        this.key = new NamespacedKey(plugin, "lobby-item");
    }

    /** Call every second. */
    public void tick() {
        Bukkit.getOnlinePlayers().forEach(this::sync);
    }

    /**
     * Gives {@code player} the items their state calls for and takes away the tagged items it does not: all of
     * them outside the lobby, in a match or the kit editor, or with the feature off.
     */
    public void sync(Player player) {
        Settings current = settings.get();
        Map<Integer, Entry> wanted = current.lobbyItems().enabled() && !matches.isRestricted(player)
                && current.isLobby(player.getWorld().getName(), current.lobbyItems().worlds()) ? wanted(player) : Map.of();
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            Entry expected = wanted.get(slot);
            if (id(contents[slot]).isPresent() && (expected == null || !expected.item().isSimilar(contents[slot]))) {
                inventory.setItem(slot, null);
            }
        }
        wanted.forEach((slot, entry) -> {
            ItemStack there = inventory.getItem(slot);
            if (there == null || there.isEmpty()) {
                inventory.setItem(slot, entry.item().clone());
            }
        });
    }

    /** Takes every lobby item from every online player; for shutdown. */
    public void stripAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerInventory inventory = player.getInventory();
            ItemStack[] contents = inventory.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                if (id(contents[slot]).isPresent()) {
                    inventory.setItem(slot, null);
                }
            }
        }
    }

    /** The entries {@code player} should hold now, by slot; waiting ones win over the rest in a shared slot. */
    private Map<Integer, Entry> wanted(Player player) {
        boolean waiting = queues.queued(player.getUniqueId()).isPresent() || matches.isBusy(player);
        Map<Integer, Entry> bySlot = new HashMap<>();
        for (Entry entry : entries()) {
            boolean shown = switch (entry.show()) {
                case IDLE -> !waiting;
                case WAITING -> waiting;
                case ALWAYS -> true;
                case NEVER -> false;
            };
            if (shown && (entry.permission() == null || player.hasPermission(entry.permission()))) {
                bySlot.merge(entry.slot(), entry, (first, second) -> second.show() == Show.WAITING ? second : first);
            }
        }
        return bySlot;
    }

    private List<Entry> entries() {
        FileConfiguration file = menus.get();
        if (file != loadedFrom) {
            loadedFrom = file;
            entries = load(file.getConfigurationSection("lobby-items"));
        }
        return entries;
    }

    private List<Entry> load(ConfigurationSection section) {
        List<Entry> loaded = new ArrayList<>();
        if (section == null) {
            return loaded;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection item = section.getConfigurationSection(id);
            if (item == null) {
                continue;
            }
            try {
                int slot = item.getInt("slot", -1);
                if (slot < 0 || slot >= HOTBAR_SIZE) {
                    throw new IllegalArgumentException("slot must be 0 to 8");
                }
                Show show = Show.valueOf(item.getString("show", "always").toUpperCase(Locale.ROOT));
                String command = item.getString("command", "");
                if (command.isBlank()) {
                    throw new IllegalArgumentException("needs a command");
                }
                ItemStack stack = MenuConfig.item(item);
                stack.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id));
                loaded.add(new Entry(id, slot, show, item.getString("permission"), command.strip(), stack));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("menus.yml lobby-items." + id + " left out: " + e.getMessage());
            }
        }
        return loaded;
    }

    private Optional<String> id(ItemStack stack) {
        return stack == null || !stack.hasItemMeta() ? Optional.empty()
                : Optional.ofNullable(stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    private boolean tagged(ItemStack stack) {
        return id(stack).isPresent();
    }

    private void syncSoon(Player player) {
        Tasks.later(plugin, () -> {
            if (player.isOnline()) {
                sync(player);
            }
        }, NEXT_TICK);
    }

    /** Right-click runs the item's command as the player, who needs its permission like when typing it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !tagged(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        String id = id(event.getItem()).orElseThrow();
        Player player = event.getPlayer();
        entries().stream().filter(entry -> entry.id().equals(id)).findFirst()
                .ifPresent(entry -> player.performCommand(entry.command()));
        syncSoon(player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (tagged(event.getPlayer().getInventory().getItem(event.getHand()))) {
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
    public void onClick(InventoryClickEvent event) {
        PlayerInventory own = event.getWhoClicked().getInventory();
        boolean swapsTagged = (event.getHotbarButton() >= 0 && tagged(own.getItem(event.getHotbarButton())))
                || (event.getClick() == ClickType.SWAP_OFFHAND && tagged(own.getItemInOffHand()));
        if (tagged(event.getCurrentItem()) || tagged(event.getCursor()) || swapsTagged) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (tagged(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (tagged(event.getMainHandItem()) || tagged(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::tagged);
    }

    /** After the session listener restored any leftover duel. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        syncSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        syncSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        syncSoon(event.getPlayer());
    }
}
