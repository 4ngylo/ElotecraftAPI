package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.LocalizedFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.FfaStats;
import me.angylo.elotecraftDuels.stats.Ranking;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.IntStream;

/**
 * {@code /duel leaderboard}: a menu of boards (wins, overall rating, each kit's rating), each opening the board's best
 * players as heads. Layouts in menus.yml {@code leaderboard} and {@code leaderboard-board}.
 */
public final class LeaderboardMenu {

    /** The board of the most wins. */
    public static final String WINS = "wins";
    /** The board of the best overall ratings. */
    public static final String OVERALL = "elo";
    /** The board of the most free-for-all kills in every kit; {@code ffa:<kit>} is one kit's. */
    public static final String FFA = "ffa";
    private static final String FFA_PREFIX = FFA + ":";
    /** Players on a board: four rows of a centered menu. */
    private static final int PLAYERS = 28;

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final StatsService stats;
    private final FfaStats ffaStats;

    public LeaderboardMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, KitRegistry kits,
                           StatsService stats, FfaStats ffaStats) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
        this.stats = stats;
        this.ffaStats = ffaStats;
    }

    /** The boards to pick from; a click runs {@code /duel leaderboard <board>}, so its lookup limit applies. */
    public void open(Player viewer) {
        ConfigurationSection section = menus.get(viewer).getConfigurationSection("leaderboard");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(kits.all().stream().filter(kit -> !kit.disabled()).map(kit -> Button.of(
                    MenuLayout.icon(kit.icon(), section.getConfigurationSection("kit"), "lore", false,
                            Placeholder.component("kit", Text.mm(kit.displayName()))),
                    board(effects, kit.name()))).toList());
            MenuLayout.place(menu, section, WINS, board(effects, WINS));
            MenuLayout.place(menu, section, "overall", board(effects, OVERALL));
            MenuLayout.place(menu, section, FFA, MenuLayout.choose(plugin, effects, this::openFfa));
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "leaderboard", e);
        }
    }

    /** The free-for-all boards: every kit's kills together, and each kit's; layout in menus.yml {@code leaderboard-ffa}. */
    public void openFfa(Player viewer) {
        ConfigurationSection section = menus.get(viewer).getConfigurationSection("leaderboard-ffa");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(kits.all().stream().filter(kit -> !kit.disabled()).map(kit -> Button.of(
                    MenuLayout.icon(kit.icon(), section.getConfigurationSection("kit"), "lore", false,
                            Placeholder.component("kit", Text.mm(kit.displayName()))),
                    board(effects, FFA_PREFIX + kit.name()))).toList());
            MenuLayout.place(menu, section, "all", board(effects, FFA));
            MenuLayout.place(menu, section, "back", MenuLayout.choose(plugin, effects, this::open));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "leaderboard-ffa", e);
        }
    }

    /**
     * Loads {@code board} ({@value #WINS}, {@value #OVERALL}, {@value #FFA}, {@code ffa:<kit>} or a kit's name) and opens
     * it once loaded.
     *
     * @return false if there is no such board
     */
    public boolean openBoard(Player viewer, String board) {
        String key = board.toLowerCase(Locale.ROOT);
        if (key.equals(WINS)) {
            show(viewer, "wins", section -> section.getString("values.wins", WINS), stats.top(PLAYERS).thenApply(top -> top.stream()
                    .map(player -> new Ranking(player.name(), 0, player.wins(), player.losses())).toList()));
            return true;
        }
        if (key.equals(OVERALL)) {
            show(viewer, "rating", section -> section.getString("values.overall", OVERALL), stats.topByElo(kits.names(), PLAYERS));
            return true;
        }
        if (key.equals(FFA)) {
            show(viewer, "ffa", section -> section.getString("values.ffa", FFA), ffaStats.top(null, PLAYERS));
            return true;
        }
        if (key.startsWith(FFA_PREFIX)) {
            Optional<Kit> ffaKit = kits.get(key.substring(FFA_PREFIX.length()));
            ffaKit.ifPresent(found -> show(viewer, "ffa", section -> section.getString("values.ffa-kit", "<kit>")
                    .replace("<kit>", found.displayName()), ffaStats.top(found.name(), PLAYERS)));
            return ffaKit.isPresent();
        }
        Optional<Kit> kit = kits.get(key);
        kit.ifPresent(found -> show(viewer, "rating", section -> found.displayName(), stats.topByElo(found.name(), PLAYERS)));
        return kit.isPresent();
    }

    private BiConsumer<Player, ClickType> board(Effects effects, String board) {
        return MenuLayout.choose(plugin, effects, player -> player.performCommand("duel leaderboard " + board));
    }

    /**
     * Opens a board with the entry template {@code template} once {@code lines} are loaded.
     *
     * @param name the board's MiniMessage name, from the viewer's {@code leaderboard-board} section
     */
    private void show(Player viewer, String template, Function<ConfigurationSection, String> name,
                      CompletableFuture<List<Ranking>> lines) {
        lines.thenAccept(top -> {
            if (!viewer.isOnline()) {
                return;
            }
            ConfigurationSection section = menus.get(viewer).getConfigurationSection("leaderboard-board");
            try {
                Effects effects = settings.get().effects();
                Divisions divisions = settings.get().ranked().divisions();
                PaginatedMenu menu = MenuLayout.frame(plugin, section, MenuLayout.plain("board", name.apply(section)));
                menu.items(IntStream.range(0, top.size()).mapToObj(rank -> Button.display(head(top.get(rank),
                        section.getConfigurationSection(template), Placeholder.unparsed("rank", String.valueOf(rank + 1)),
                        Placeholder.unparsed("player", top.get(rank).name()),
                        Placeholder.unparsed("elo", String.valueOf(top.get(rank).elo())),
                        Placeholder.component("division", divisions.name(top.get(rank).elo())),
                        Placeholder.unparsed("wins", String.valueOf(top.get(rank).wins())),
                        Placeholder.unparsed("losses", String.valueOf(top.get(rank).losses())),
                        Placeholder.unparsed("kills", String.valueOf(top.get(rank).wins())),
                        Placeholder.unparsed("deaths", String.valueOf(top.get(rank).losses())),
                        Placeholder.unparsed("ratio", ratio(top.get(rank)))))).toList());
                MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
                MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
                menu.open(viewer);
            } catch (IllegalArgumentException e) {
                MenuLayout.menuError(plugin, messages, viewer, "leaderboard-board", e);
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not load the duel leaderboard", error);
            if (viewer.isOnline()) {
                messages.send(viewer, "stats.error");
            }
            return null;
        });
    }

    /** Kills per death of a free-for-all line; the kills themselves before a first death. */
    private static String ratio(Ranking line) {
        return String.format(Locale.ROOT, "%.2f", line.losses() == 0 ? line.wins() : (double) line.wins() / line.losses());
    }

    /** The player's head, with their skin if the server knows them, and the entry template. */
    private static ItemStack head(Ranking line, ConfigurationSection template, TagResolver... tags) {
        ItemStack head = MenuLayout.icon(Material.PLAYER_HEAD, template, "lore", false, tags);
        OfflinePlayer owner = Bukkit.getOfflinePlayerIfCached(line.name());
        if (owner != null) {
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        }
        return head;
    }
}
