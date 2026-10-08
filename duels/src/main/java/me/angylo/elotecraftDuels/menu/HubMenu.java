package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.event.EventManager;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /duel} and {@code /duel menu <name>}: the menus.yml {@code hub} menus, buttons at fixed slots that run a
 * command as the player, so the commands' checks apply. Every hub menu gets the viewer's stats and the queue, fight
 * and event counts as tags.
 */
public final class HubMenu {

    /** The menu {@code /duel} opens. */
    public static final String MAIN = "main";
    private static final String HUB = "hub";

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final StatsService stats;
    private final QueueManager queues;
    private final MatchManager matches;
    private final EventManager events;

    public HubMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, KitRegistry kits,
                   StatsService stats, QueueManager queues, MatchManager matches, EventManager events) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
        this.stats = stats;
        this.queues = queues;
        this.matches = matches;
        this.events = events;
    }

    /** The hub menus' names, for tab completion. */
    public List<String> names() {
        ConfigurationSection hub = menus.get().getConfigurationSection(HUB);
        return hub == null ? List.of() : List.copyOf(hub.getKeys(false));
    }

    /** Opens the hub menu {@code name}, or says there is none. */
    public void open(Player viewer, String name) {
        if (!names().contains(name)) {
            messages.send(viewer, "general.no-menu", Placeholder.unparsed("menu", name));
            return;
        }
        ConfigurationSection section = menus.get().getConfigurationSection(HUB + "." + name);
        try {
            TagResolver[] tags = tags(viewer);
            Menu menu = MenuLayout.fixed(plugin, section, tags);
            ConfigurationSection buttons = section.getConfigurationSection("buttons");
            for (String key : buttons == null ? List.<String>of() : buttons.getKeys(false)) {
                String permission = buttons.getString(key + ".permission", "");
                if (!permission.isEmpty() && !viewer.hasPermission(permission)) {
                    continue;
                }
                ItemStack icon = MenuConfig.item(buttons.getConfigurationSection(key), tags);
                if (icon.getType() == Material.PLAYER_HEAD) {
                    icon.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(viewer));
                }
                MenuLayout.put(menu, buttons, key, icon, MenuLayout.command(plugin, settings.get().effects(), buttons, key));
            }
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, HUB + "." + name, e);
        }
    }

    private TagResolver[] tags(Player viewer) {
        PlayerStats own = stats.cached(viewer.getUniqueId()).orElse(PlayerStats.empty(viewer.getName()));
        int elo = own.overallElo(kits.names());
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
                Placeholder.unparsed("events", String.valueOf(events.openEvents().size()))};
    }

    private int queued(boolean ranked) {
        return kits.names().stream().mapToInt(kit -> queues.size(kit, ranked)).sum();
    }
}
