package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.LocalizedFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.stats.MatchHistory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /duel history}: a player's latest 1v1 or 2v2 duels, newest first, with a button switching between them. Layout
 * in menus.yml {@code history}.
 */
public final class HistoryMenu {

    /** The {@code /duel history} categories. */
    public static final String DUELS = "1v1";
    public static final String TEAM_DUELS = "2v2";

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;

    public HistoryMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, KitRegistry kits) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
    }

    /**
     * @param player whose duels these are, for the title
     * @param team   whether {@code entries} are 2v2 duels
     */
    public void open(Player viewer, String player, List<MatchHistory.Entry> entries, boolean team) {
        ConfigurationSection section = menus.get(viewer).getConfigurationSection("history");
        try {
            PaginatedMenu menu = MenuLayout.frame(plugin, section, Placeholder.unparsed("player", player));
            long now = System.currentTimeMillis();
            menu.items(entries.stream().map(entry -> Button.display(icon(section, entry, now))).toList());
            // The button showing the other category; its command runs /duel history <player> <category>.
            String other = team ? "duels" : "team-duels";
            if (section.isConfigurationSection(other)) {
                MenuLayout.place(menu, section, other, MenuLayout.choose(plugin, settings.get().effects(), clicker ->
                        clicker.performCommand("duel history " + player + " " + (team ? DUELS : TEAM_DUELS))));
            }
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, settings.get().effects(), section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, settings.get().effects()));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "history", e);
        }
    }

    /** The kit's icon (paper if the kit was deleted) with the {@code won} or {@code lost} template. */
    private ItemStack icon(ConfigurationSection section, MatchHistory.Entry entry, long now) {
        Optional<Kit> kit = kits.get(entry.kit());
        Component rating = entry.ranked() ? Component.text((entry.won() ? "+" : "-") + entry.eloChange())
                : MenuLayout.value(section, "unranked");
        String template = (entry.teammates().isEmpty() ? "" : "team-") + (entry.won() ? "won" : "lost");
        return MenuLayout.icon(kit.map(Kit::icon).orElse(Material.PAPER), section.getConfigurationSection(template),
                "lore", false,
                Placeholder.unparsed("opponent", entry.against()),
                Placeholder.unparsed("teammates", entry.teammates()),
                Placeholder.component("kit", kit.map(found -> Text.mm(found.displayName())).orElse(Component.text(entry.kit()))),
                Placeholder.unparsed("arena", entry.arena()),
                Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(entry.seconds()))),
                Placeholder.unparsed("ago", Durations.format(roughly(Duration.ofMillis(Math.max(0, now - entry.endedAt()))))),
                Placeholder.component("rating", rating),
                Placeholder.component("how", MenuLayout.value(section, entry.reason().toLowerCase(Locale.ROOT))),
                Placeholder.unparsed("health", String.format(Locale.ROOT, "%.1f", entry.health() / 2)));
    }

    /** {@code duration} in its largest unit only: 3d rather than 3d4h12m. */
    public static Duration roughly(Duration duration) {
        if (duration.toDays() > 0) {
            return Duration.ofDays(duration.toDays());
        }
        if (duration.toHours() > 0) {
            return Duration.ofHours(duration.toHours());
        }
        return duration.toMinutes() > 0 ? Duration.ofMinutes(duration.toMinutes()) : Duration.ofSeconds(duration.toSeconds());
    }
}
