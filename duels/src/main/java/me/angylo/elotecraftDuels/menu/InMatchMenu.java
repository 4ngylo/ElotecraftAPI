package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;

/**
 * A menu that opens for players in a match or the kit editor, who {@code ProtectionListener} keeps out of every other
 * inventory: the spectators' fighter menu and the custom kit items.
 */
public class InMatchMenu extends PaginatedMenu {

    InMatchMenu(Plugin plugin, int rows, Component title) {
        super(plugin, rows, title);
    }
}
