package me.angylo.elotecraftAPI.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Routes chat answers to {@link ChatInput}. Registered once by ElotecraftAPI. */
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
    }
}
