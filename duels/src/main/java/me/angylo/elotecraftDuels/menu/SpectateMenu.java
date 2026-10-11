package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.Durations;
import me.angylo.elotecraftAPI.util.LocalizedFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code /duel spectate} without a player: every fight the viewer may watch ({@link Match#watchableBy}), one
 * button each with its kit's icon; a click runs {@code /duel spectate <fighter>}, so the same checks apply.
 * Layout in menus.yml {@code spectate}. While watching, {@link #openFighters} instead: a head per fighter still in the
 * fight, a click teleporting to them (menus.yml {@code spectate-fighters}).
 */
public final class SpectateMenu {

    private static final Set<Match.State> WATCHABLE = Set.of(Match.State.COUNTDOWN, Match.State.FIGHTING, Match.State.ROUND_OVER);

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final MatchManager matches;

    public SpectateMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, MatchManager matches) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.matches = matches;
    }

    public void open(Player viewer) {
        List<Match> fights = matches.running().stream()
                .filter(match -> WATCHABLE.contains(match.state()) && match.watchableBy(viewer))
                .toList();
        if (fights.isEmpty()) {
            messages.send(viewer, "spectate.none");
            return;
        }
        ConfigurationSection section = menus.get(viewer).getConfigurationSection("spectate");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(fights.stream().map(match -> Button.of(MenuLayout.icon(match.kit().icon(), section.getConfigurationSection("fight"),
                    "lore", false, tags(section, match)), MenuLayout.choose(plugin, effects,
                    player -> player.performCommand("duel spectate " + match.fighters().getFirst().getName())))).toList());
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "spectate", e);
        }
    }

    /** The fighters still in {@code match}, for {@code viewer} watching it; a click runs {@code /duel spectate <fighter>}. */
    public void openFighters(Player viewer, Match match) {
        ConfigurationSection section = menus.get(viewer).getConfigurationSection("spectate-fighters");
        try {
            Effects effects = settings.get().effects();
            InMatchMenu menu = MenuLayout.frame(section, (rows, title) -> new InMatchMenu(plugin, rows, title));
            menu.items(match.fighters().stream().filter(match::isAlive).map(fighter -> Button.of(head(section, fighter),
                    MenuLayout.choose(plugin, effects, player -> player.performCommand("duel spectate " + fighter.getName())))).toList());
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "spectate-fighters", e);
        }
    }

    private static ItemStack head(ConfigurationSection section, Player fighter) {
        ItemStack head = MenuLayout.icon(Material.PLAYER_HEAD, section.getConfigurationSection("fighter"), "lore", false,
                Placeholder.unparsed("player", fighter.getName()),
                Placeholder.unparsed("health", String.valueOf((int) Math.ceil(fighter.getHealth()))));
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(fighter));
        return head;
    }

    private static TagResolver[] tags(ConfigurationSection section, Match match) {
        return new TagResolver[]{
                Placeholder.unparsed("fighters", match.teams().stream()
                        .map(team -> team.stream().map(Player::getName).collect(Collectors.joining(", ")))
                        .collect(Collectors.joining(" vs "))),
                Placeholder.component("kit", Text.mm(match.kit().displayName())),
                Placeholder.component("arena", Text.mm(match.arena().displayName())),
                Placeholder.component("type", MenuLayout.value(section, match.type().name().toLowerCase(Locale.ROOT))),
                Placeholder.unparsed("time", Durations.format(Duration.ofSeconds(match.timeLeftSeconds()))),
                Placeholder.unparsed("spectators", String.valueOf(match.spectators().size()))};
    }
}
