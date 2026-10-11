package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.util.LocalizedFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.event.EventManager;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.SeasonEnder;
import me.angylo.elotecraftDuels.stats.Seasons;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * {@code /duel}, {@code /duel menu <name>} and {@code /duels}: the menus.yml {@code hub} menus, buttons at fixed slots
 * that run a command as the player, so the commands' checks apply; a menu or button with a {@code permission} is only
 * for players who have it, and a button with {@code confirm: true} asks first. Every hub menu gets the viewer's stats and the queue, fight
 * and event counts and the season running as tags.
 */
public final class HubMenu {

    /** The menu {@code /duel} opens. */
    public static final String MAIN = "main";
    private static final String HUB = "hub";

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final StatsService stats;
    private final QueueManager queues;
    private final MatchManager matches;
    private final EventManager events;
    private final Seasons seasons;

    public HubMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, KitRegistry kits,
                   StatsService stats, QueueManager queues, MatchManager matches, EventManager events, Seasons seasons) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
        this.stats = stats;
        this.queues = queues;
        this.matches = matches;
        this.events = events;
        this.seasons = seasons;
    }

    /** The menu {@code /duels} opens. */
    public static final String ADMIN = "admin";

    /** The hub menus {@code viewer} may open: those without a {@code permission}, or whose permission they have. */
    public List<String> names(Permissible viewer) {
        ConfigurationSection hub = menus.get().getConfigurationSection(HUB);
        return hub == null ? List.of() : hub.getKeys(false).stream().filter(name -> allowed(viewer, hub, name + ".permission")).toList();
    }

    private static boolean allowed(Permissible viewer, ConfigurationSection section, String permissionKey) {
        String permission = section.getString(permissionKey, "");
        return permission.isEmpty() || viewer.hasPermission(permission);
    }

    /** Opens the hub menu {@code name}, or says there is none (also when the viewer may not open it). */
    public void open(Player viewer, String name) {
        if (!names(viewer).contains(name)) {
            messages.send(viewer, "general.no-menu", Placeholder.unparsed("menu", name));
            return;
        }
        ConfigurationSection section = menus.get(viewer).getConfigurationSection(HUB + "." + name);
        try {
            TagResolver[] tags = tags(viewer);
            Menu menu = MenuLayout.fixed(plugin, section, tags);
            ConfigurationSection buttons = section.getConfigurationSection("buttons");
            for (String key : buttons == null ? List.<String>of() : buttons.getKeys(false)) {
                if (allowed(viewer, buttons, key + ".permission")) {
                    put(menu, buttons, key, viewer, name, tags);
                }
            }
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, HUB + "." + name, e);
        }
    }

    /** The button {@code key}; with {@code confirm: true} its command runs only once confirmed, and cancelling reopens the menu. */
    private void put(Menu menu, ConfigurationSection buttons, String key, Player viewer, String name, TagResolver[] tags) {
        ItemStack icon = MenuConfig.item(buttons.getConfigurationSection(key), tags);
        if (icon.getType() == Material.PLAYER_HEAD) {
            icon.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(viewer));
        }
        Effects effects = settings.get().effects();
        String command = buttons.getString(key + ".command", "").strip();
        BiConsumer<Player, ClickType> action = buttons.getBoolean(key + ".confirm") && !command.isEmpty()
                ? MenuLayout.confirm(plugin, messages, menus, effects, MenuLayout.name(buttons, key, tags),
                        player -> player.performCommand(command), player -> open(player, name))
                : MenuLayout.command(plugin, effects, buttons, key);
        MenuLayout.put(menu, buttons, key, icon, action);
    }

    private TagResolver[] tags(Player viewer) {
        PlayerStats own = stats.cached(viewer.getUniqueId()).orElse(PlayerStats.empty(viewer.getName()));
        int elo = own.overallElo(kits.names());
        Seasons.Info season = seasons.info();
        return new TagResolver[]{
                Placeholder.unparsed("wins", String.valueOf(own.wins())),
                Placeholder.unparsed("losses", String.valueOf(own.losses())),
                Placeholder.unparsed("rate", String.valueOf(own.winRate())),
                Placeholder.unparsed("streak", String.valueOf(own.winStreak())),
                Placeholder.unparsed("best", String.valueOf(own.bestWinStreak())),
                Placeholder.unparsed("elo", String.valueOf(elo)),
                Placeholder.component("division", settings.get().ranked().divisions().name(elo)),
                Placeholder.unparsed("queued", String.valueOf(queued(false))),
                Placeholder.unparsed("ranked", String.valueOf(queued(true))),
                Placeholder.unparsed("fights", String.valueOf(matches.activeMatches())),
                Placeholder.unparsed("events", String.valueOf(events.openEvents().size())),
                Placeholder.unparsed("season", String.valueOf(season.season())),
                Placeholder.component("season_name", SeasonEnder.name(messages, viewer, season.season(), season.name())),
                Placeholder.unparsed("season_days", String.valueOf(SeasonEnder.days(season))),
                Placeholder.component("season_ends", SeasonEnder.left(season).<Component>map(left -> Component.text(SeasonEnder.length(left)))
                        .orElseGet(() -> messages.get(viewer, "admin.season.none"))),
                Placeholder.component("season_auto", messages.get(viewer, season.autoEnd() ? "admin.season.on" : "admin.season.off"))};
    }

    private int queued(boolean ranked) {
        return kits.names().stream().mapToInt(kit -> queues.size(kit, ranked)).sum();
    }
}
