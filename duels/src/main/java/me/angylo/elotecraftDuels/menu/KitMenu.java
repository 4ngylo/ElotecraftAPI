package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Picks a kit: to challenge someone, or to join (or leave) its unranked or ranked queue. Layout in
 * menus.yml {@code kits}.
 */
public final class KitMenu {

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

    public KitMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, KitRegistry kits,
                   MatchManager matches, QueueManager queues) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
        this.matches = matches;
        this.queues = queues;
    }

    /** Shows the kits {@code viewer} may use; {@code onChoose} runs after the menu closes. */
    public void open(Player viewer, Mode mode, Consumer<Kit> onChoose) {
        List<Kit> usable = kits.all().stream().filter(kit -> kit.canUse(viewer) && !kit.isEmpty()).toList();
        if (usable.isEmpty()) {
            messages.send(viewer, "general.no-kits");
            return;
        }
        ConfigurationSection section = menus.get().getConfigurationSection("kits");
        try {
            Effects effects = settings.get().effects();
            boolean ranked = mode == Mode.RANKED;
            QueueManager.QueueId queued = queues.queued(viewer.getUniqueId()).orElse(null);
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(usable.stream().map(kit -> {
                boolean inQueue = (mode == Mode.QUEUE || mode == Mode.RANKED) && new QueueManager.QueueId(kit.name(), ranked).equals(queued);
                String lore = switch (mode) {
                    case CHALLENGE -> "lore";
                    case EDIT -> "edit-lore";
                    default -> inQueue ? "queued-lore" : "queue-lore";
                };
                // Challenging shows everyone queued for the kit; a queue menu shows that queue.
                int waiting = mode == Mode.CHALLENGE ? queues.size(kit.name(), false) + queues.size(kit.name(), true)
                        : queues.size(kit.name(), ranked);
                return Button.of(MenuLayout.icon(kit.icon(), section.getConfigurationSection("kit"), lore, inQueue,
                                Placeholder.component("kit", Text.mm(kit.displayName())),
                                Placeholder.component("type", messages.get(viewer, ranked ? "queue.type-ranked" : "queue.type-unranked")),
                                Placeholder.unparsed("queued", String.valueOf(waiting)),
                                Placeholder.unparsed("dueling", String.valueOf(matches.fightingWith(kit.name()))),
                                Placeholder.component("building", messages.get(viewer, kit.build() ? "general.kit-build" : "general.kit-no-build"))),
                        MenuLayout.choose(plugin, effects, player -> onChoose.accept(kit)));
            }).toList());
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            MenuLayout.fill(menu, section);
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Invalid kits menu in menus.yml: " + e.getMessage());
            messages.send(viewer, "general.menu-error");
        }
    }
}
