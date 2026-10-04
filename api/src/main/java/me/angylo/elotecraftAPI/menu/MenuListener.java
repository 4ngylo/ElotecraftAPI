package me.angylo.elotecraftAPI.menu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.function.Predicate;

/**
 * Routes clicks to {@link Menu}s. Registered once by ElotecraftAPI.
 */
public final class MenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == top) {
            menu.handleClick(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        close(owner -> owner.equals(event.getPlugin()));
    }

    /** Closes every open menu; once ElotecraftAPI disables, nothing protects them. */
    public static void closeAll() {
        close(owner -> true);
    }

    private static void close(Predicate<Plugin> ownerFilter) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu
                    && ownerFilter.test(menu.plugin())) {
                player.closeInventory();
            }
        }
    }
}
