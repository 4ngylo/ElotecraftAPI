package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.stats.KitRating;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Picks a kit: to challenge someone, or to join (or leave) its unranked or ranked queue; right-click
 * previews a kit's items. Layouts in menus.yml {@code kits}, {@code ranked-queue}, {@code unranked-queue}
 * and {@code kit-preview}.
 */
public final class KitMenu {

    private static final int PREVIEW_ROWS = 6;
    private static final int BOTTOM_ROW = (PREVIEW_ROWS - 1) * 9;
    private static final String RANKED_PERMISSION = "duels.queue.ranked";

    /** What clicking a kit is for; changes its lore. */
    public enum Mode {
        CHALLENGE, QUEUE, RANKED, EDIT
    }

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final MatchManager matches;
    private final QueueManager queues;
    private final StatsService stats;

    public KitMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, KitRegistry kits,
                   MatchManager matches, QueueManager queues, StatsService stats) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
        this.matches = matches;
        this.queues = queues;
        this.stats = stats;
    }

    /**
     * Shows the kits {@code viewer} may use; {@code onChoose} runs after the menu closes. The queues have their
     * own menus, {@code ranked-queue} and {@code unranked-queue}; challenges and the editor use {@code kits}.
     */
    public void open(Player viewer, Mode mode, Consumer<Kit> onChoose) {
        open(viewer, mode, List.of(), onChoose);
    }

    /** Like {@link #open(Player, Mode, Consumer)}, with {@code extra} kits (the viewer's custom kits) after the others. */
    public void open(Player viewer, Mode mode, List<Kit> extra, Consumer<Kit> onChoose) {
        List<Kit> usable = Stream.concat(kits.all().stream().filter(kit -> kit.canUse(viewer) && !kit.isEmpty()), extra.stream()).toList();
        if (usable.isEmpty()) {
            messages.send(viewer, "general.no-kits");
            return;
        }
        String key = switch (mode) {
            case RANKED -> "ranked-queue";
            case QUEUE -> "unranked-queue";
            case CHALLENGE, EDIT -> "kits";
        };
        ConfigurationSection section = menus.get().getConfigurationSection(key);
        try {
            Effects effects = settings.get().effects();
            boolean queue = mode == Mode.QUEUE || mode == Mode.RANKED;
            boolean ranked = mode == Mode.RANKED;
            QueueManager.QueueId queued = queues.queued(viewer.getUniqueId()).orElse(null);
            PlayerStats own = stats.cached(viewer.getUniqueId()).orElse(PlayerStats.empty(viewer.getName()));
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(usable.stream().map(kit -> {
                boolean inQueue = queue && new QueueManager.QueueId(kit.name(), ranked).equals(queued);
                String lore = switch (mode) {
                    case EDIT -> "edit-lore";
                    case QUEUE, RANKED -> inQueue ? "queued-lore" : "lore";
                    case CHALLENGE -> "lore";
                };
                // Challenging shows everyone queued for the kit; a queue menu shows that queue.
                int waiting = queue ? queues.size(kit.name(), ranked) : queues.size(kit.name(), false) + queues.size(kit.name(), true);
                String fighting = String.valueOf(matches.fightingWith(kit.name()));
                return Button.of(MenuLayout.icon(kit.icon(), section.getConfigurationSection("kit"), lore, inQueue,
                                MenuLayout.with(ratingTags(own.elo(kit.name()), own.ratings().get(kit.name())),
                                        Placeholder.component("kit", Text.mm(kit.displayName())),
                                        Placeholder.unparsed("queued", String.valueOf(waiting)),
                                        Placeholder.unparsed("dueling", fighting),
                                        Placeholder.unparsed("fighting", fighting),
                                        Placeholder.component("building", messages.get(viewer, kit.build() ? "general.kit-build" : "general.kit-no-build")))),
                        click(mode, MenuLayout.choose(plugin, effects, player -> onChoose.accept(kit)),
                                MenuLayout.choose(plugin, effects, player -> preview(player, kit, mode, onChoose))));
            }).toList());
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            if (ranked) {
                MenuLayout.place(menu, section, "rating", (player, click) -> { },
                        ratingTags(own.overallElo(kits.names()), null));
            }
            // The other queue's menu, through its command, which checks the permission and does the joining.
            if (queue && (ranked || viewer.hasPermission(RANKED_PERMISSION))) {
                MenuLayout.place(menu, section, "switch",
                        MenuLayout.choose(plugin, effects, player -> player.performCommand(ranked ? "duel queue" : "duel ranked")));
            }
            MenuLayout.fill(menu, section);
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Invalid " + key + " menu in menus.yml: " + e.getMessage());
            messages.send(viewer, "general.menu-error");
        }
    }

    /** {@code <elo>} and {@code <division>}, and the ranked {@code <wins>} and {@code <losses>} of {@code rating} (0 if none). */
    private TagResolver[] ratingTags(int elo, KitRating rating) {
        return new TagResolver[]{
                Placeholder.unparsed("elo", String.valueOf(elo)),
                Placeholder.component("division", settings.get().ranked().divisions().name(elo)),
                Placeholder.unparsed("wins", String.valueOf(rating == null ? 0 : rating.wins())),
                Placeholder.unparsed("losses", String.valueOf(rating == null ? 0 : rating.losses()))};
    }

    /** Right-click previews, except in the kit editor, whose click already shows the items. */
    private static BiConsumer<Player, ClickType> click(Mode mode, BiConsumer<Player, ClickType> choose,
                                                       BiConsumer<Player, ClickType> preview) {
        return mode == Mode.EDIT ? choose : (player, click) -> (click.isRightClick() ? preview : choose).accept(player, click);
    }

    /** The kit's items, read-only; the back button opens the kits menu again with the same choice. */
    private void preview(Player viewer, Kit kit, Mode mode, Consumer<Kit> onChoose) {
        ConfigurationSection section = menus.get().getConfigurationSection("kit-preview");
        try {
            if (section == null) {
                throw new IllegalArgumentException("Missing kit-preview section");
            }
            Menu menu = new Menu(plugin, PREVIEW_ROWS, Text.mm(section.getString("title", ""),
                    Placeholder.component("kit", Text.mm(kit.displayName()))));
            List<ItemStack> items = kit.items();
            for (int slot = 0; slot < items.size(); slot++) {
                int shownAt = previewSlot(slot);
                if (shownAt >= 0 && !items.get(slot).isEmpty()) {
                    menu.set(shownAt, items.get(slot));
                }
            }
            ConfigurationSection back = section.getConfigurationSection("back");
            if (back != null) {
                menu.set(BOTTOM_ROW + back.getInt("slot", 4), Button.of(MenuConfig.item(back),
                        MenuLayout.choose(plugin, settings.get().effects(), player -> open(player, mode, onChoose))));
            }
            if (section.isConfigurationSection("filler")) {
                menu.fill(MenuConfig.item(section.getConfigurationSection("filler")));
            }
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "kit-preview", e);
        }
    }

    /**
     * Where a player inventory slot shows in the preview: storage on the top three rows, the hotbar under it,
     * then helmet to boots and the off hand; -1 for none.
     */
    public static int previewSlot(int inventorySlot) {
        if (inventorySlot < 9) {
            return 27 + inventorySlot;
        }
        if (inventorySlot < 36) {
            return inventorySlot - 9;
        }
        if (inventorySlot < 40) {
            return 75 - inventorySlot;
        }
        return inventorySlot == 40 ? 41 : -1;
    }
}
