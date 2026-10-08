package me.angylo.elotecraftAPI.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Routes chat answers to {@link ChatInput} and anvil events to {@link AnvilInput}. Registered once by ElotecraftAPI. */
public final class InputListener implements Listener {

    /** LOWEST so the answer is taken before chat plugins format or broadcast it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (ChatInput.answer(event.getPlayer().getUniqueId(), Text.plain(event.message()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ChatInput.discard(event.getPlayer().getUniqueId());
        AnvilInput.discard(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInput.prepare(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        AnvilInput.click(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        AnvilInput.drag(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        AnvilInput.close(event);
    }
}
