package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;

/**
 * A menu that opens for players in a match, who {@code ProtectionListener} keeps out of every other inventory: the
 * spectators' fighter menu.
 */
public class InMatchMenu extends PaginatedMenu {

    InMatchMenu(Plugin plugin, int rows, Component title) {
        super(plugin, rows, title);
    }
}
