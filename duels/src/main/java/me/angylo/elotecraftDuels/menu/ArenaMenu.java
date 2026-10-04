package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.match.MatchManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Picks an arena or "random" for a challenge. Layout in menus.yml {@code arenas}. */
public final class ArenaMenu {

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final MatchManager matches;

    public ArenaMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings,
                     ArenaRegistry arenas, MatchManager matches) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.arenas = arenas;
        this.matches = matches;
    }

    /**
     * Shows every ready arena; busy ones say so and cannot be picked.
     *
     * @param onChoose gets the arena, or empty for a random one; runs after the menu closes
     */
    public void open(Player viewer, Consumer<Optional<Arena>> onChoose) {
        List<Arena> ready = arenas.all().stream().filter(Arena::isReady).toList();
        if (ready.isEmpty()) {
            messages.send(viewer, "general.no-free-arena");
            return;
        }
        ConfigurationSection section = menus.get().getConfigurationSection("arenas");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(ready.stream().map(arena -> {
                TagResolver name = Placeholder.component("arena", Text.mm(arena.displayName()));
                boolean busy = !matches.isArenaFree(arena);
                return Button.of(MenuLayout.icon(arena.icon(), section.getConfigurationSection("arena"),
                                busy ? "busy-lore" : "lore", false, name),
                        busy ? (player, click) -> {
                            effects.play(player, "denied");
                            messages.send(player, "general.arena-busy", name);
                        } : MenuLayout.choose(plugin, effects, player -> onChoose.accept(Optional.of(arena))));
            }).toList());
            MenuLayout.place(menu, section, "random", MenuLayout.choose(plugin, effects, player -> onChoose.accept(Optional.empty())));
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            MenuLayout.fill(menu, section);
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Invalid arenas menu in menus.yml: " + e.getMessage());
            messages.send(viewer, "general.menu-error");
        }
    }
}
