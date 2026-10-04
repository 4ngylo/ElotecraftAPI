package me.angylo.elotecraftAPI;

import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.hologram.Hologram;
import me.angylo.elotecraftAPI.hud.Bossbars;
import me.angylo.elotecraftAPI.hud.Sidebar;
import me.angylo.elotecraftAPI.input.ChatInput;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * Removes what a plugin created through ElotecraftAPI when that plugin disables (commands, chat
 * prompts, boss bars, sidebars, holograms) and forgets per-player state when a player quits.
 * Registered once by ElotecraftAPI.
 */
public final class CleanupListener implements Listener {

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        CommandBuilder.unregisterAll(event.getPlugin());
        ChatInput.cancelAll(event.getPlugin());
        Bossbars.hideAll(event.getPlugin());
        Sidebar.hideAll(event.getPlugin());
        Hologram.removeAll(event.getPlugin());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Sidebar.forget(event.getPlayer().getUniqueId());
    }
}
