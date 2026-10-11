package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.LocalizedFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.KitRating;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** {@code /duel ratings}: the viewer's rating in each kit they played ranked. Layout in menus.yml {@code ratings}. */
public final class RatingsMenu {

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final StatsService stats;

    public RatingsMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, KitRegistry kits,
                       StatsService stats) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
        this.stats = stats;
    }

    public void open(Player viewer) {
        ConfigurationSection section = menus.get(viewer).getConfigurationSection("ratings");
        try {
            Effects effects = settings.get().effects();
            Divisions divisions = settings.get().ranked().divisions();
            PlayerStats own = stats.cached(viewer.getUniqueId()).orElse(PlayerStats.empty(viewer.getName()));
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(kits.all().stream().filter(kit -> own.ratings().containsKey(kit.name())).map(kit -> {
                KitRating rating = own.ratings().get(kit.name());
                return Button.display(MenuLayout.icon(kit.icon(), section.getConfigurationSection("kit"), "lore", false,
                        Placeholder.component("kit", Text.mm(kit.displayName())),
                        Placeholder.unparsed("elo", String.valueOf(rating.elo())),
                        Placeholder.unparsed("peak", String.valueOf(rating.peak())),
                        Placeholder.component("division", divisions.name(rating.elo())),
                        Placeholder.unparsed("wins", String.valueOf(rating.wins())),
                        Placeholder.unparsed("losses", String.valueOf(rating.losses()))));
            }).toList());
            int overall = own.overallElo(kits.names());
            MenuLayout.place(menu, section, "overall", (player, click) -> { },
                    Placeholder.unparsed("elo", String.valueOf(overall)), Placeholder.component("division", divisions.name(overall)));
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "ratings", e);
        }
    }
}
