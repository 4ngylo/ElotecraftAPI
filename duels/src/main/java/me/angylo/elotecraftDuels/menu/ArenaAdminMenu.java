package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.LocalizedFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaPool;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
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

import static me.angylo.elotecraftDuels.menu.MenuLayout.value;
import static me.angylo.elotecraftDuels.menu.MenuLayout.with;

/**
 * Arena setup in menus: every arena (copies are counted, not listed), and a settings menu per arena with
 * submenus for its points, its bridge and bed fight points, and its snapshot and copies. Like {@link KitAdminMenu}, each button runs the matching {@code /duels arena} command as
 * the clicking player; points are taken where the player stands, and changes that are hard to undo ask to confirm.
 * Layouts in menus.yml {@code arena-admin}, {@code arena-settings}, {@code arena-points}, {@code arena-modes} and
 * {@code arena-upkeep}.
 */
public final class ArenaAdminMenu {

    private static final String SETTINGS = "arena-settings";
    private static final String SUBMENU_PREFIX = "arena-";
    /** The submenus of arena-settings, each opened by its button named without the prefix. */
    private static final List<String> SUBMENUS = List.of("arena-points", "arena-modes", "arena-upkeep");

    private final Plugin plugin;
    private final Messages messages;
    private final LocalizedFile menus;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final ArenaPool pool;

    public ArenaAdminMenu(Plugin plugin, Messages messages, LocalizedFile menus, Supplier<Settings> settings, ArenaRegistry arenas,
                          ArenaPool pool) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.arenas = arenas;
        this.pool = pool;
    }

    /** "ready", or the arena's problems joined with commas. */
    public static Component status(Messages messages, Audience viewer, Arena arena) {
        List<Arena.Problem> problems = arena.problems();
        if (problems.isEmpty()) {
            return messages.get(viewer, "admin.arena.ready");
        }
        return Component.join(JoinConfiguration.commas(true), problems.stream().map(problem -> messages.get(viewer, problem.messageKey())).toList());
    }

    /** Shows every arena built by hand; clicking one opens its settings. */
    public void openList(Player viewer) {
        ConfigurationSection section = menus.get(viewer).getConfigurationSection("arena-admin");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(arenas.all().stream().map(arena -> Button.of(listIcon(viewer, section, arena),
                    MenuLayout.choose(plugin, effects, player -> openSettings(player, arena.name())))).toList());
            MenuLayout.place(menu, section, "create", MenuLayout.choose(plugin, effects, player ->
                    MenuLayout.ask(plugin, messages, player, "admin.arena.prompt-create", new TagResolver[0], text -> {
                        run(player, "create " + text);
                        openList(player);
                    })));
            MenuLayout.place(menu, section, "import", MenuLayout.choose(plugin, effects, player ->
                    MenuLayout.ask(plugin, messages, player, "admin.arena.prompt-import", new TagResolver[0], text -> {
                        run(player, "import " + text);
                        openList(player);
                    })));
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "arena-admin", e);
        }
    }

    /** Shows the settings of the arena {@code name}, or the arena list if it is gone. */
    public void openSettings(Player viewer, String name) {
        open(viewer, name, SETTINGS);
    }

    /** Shows the menu {@code key} (arena-settings or a submenu) of the arena {@code name}, or the arena list if it is gone. */
    private void open(Player viewer, String name, String key) {
        Arena arena = arenas.get(name).orElse(null);
        if (arena == null) {
            openList(viewer);
            return;
        }
        try {
            new Editor(key, settings.get().effects(), viewer, arena).menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, key, e);
        }
    }

    private ItemStack listIcon(Player viewer, ConfigurationSection section, Arena arena) {
        return MenuLayout.icon(arena.icon(), section.getConfigurationSection("arena"), "lore", false, with(arenaTags(arena),
                Placeholder.unparsed("world", arena.world()),
                Placeholder.component("status", status(messages, viewer, arena)),
                Placeholder.component("categories", categories(section, arena)),
                Placeholder.unparsed("copies", String.valueOf(copies(arena.name())))));
    }

    /** Copies of {@code arena}, in use and free. */
    private int copies(String arena) {
        ArenaPool.Count count = pool.count(arena);
        return count.inUse() + count.free();
    }

    /** Runs {@code /duels arena <args>} as {@code player}. */
    private static void run(Player player, String args) {
        player.performCommand("duels arena " + args);
    }

    /**
     * One open arena menu: {@code arena-settings} or one of its submenus. Every arena button is drawn by the same
     * code, and each menu shows the ones its section configures; redrawn in place after each change.
     */
    private final class Editor {

        private final String key;
        private final ConfigurationSection section;
        private final Effects effects;
        private final Player viewer;
        private final String name;
        private final Menu menu;

        Editor(String key, Effects effects, Player viewer, Arena arena) {
            this.key = key;
            this.section = menus.get(viewer).getConfigurationSection(key);
            this.effects = effects;
            this.viewer = viewer;
            this.name = arena.name();
            this.menu = MenuLayout.fixed(plugin, section, MenuLayout.plain("arena", arena.displayName()));
            MenuLayout.put(menu, section, "back", MenuLayout.choose(plugin, effects, player -> {
                if (key.equals(SETTINGS)) {
                    openList(player);
                } else {
                    openSettings(player, name);
                }
            }));
            MenuLayout.put(menu, section, "close", MenuLayout.close(plugin, effects));
            for (String submenu : SUBMENUS) {
                put(submenu.substring(SUBMENU_PREFIX.length()), arena, Component.empty(),
                        MenuLayout.choose(plugin, effects, player -> open(player, name, submenu)));
            }
            draw(arena);
        }

        private void draw(Arena arena) {
            put("status", arena, status(messages, viewer, arena), change("info"));
            put("enabled", arena, value(section, arena.enabled() ? "on" : "off"), change("toggle"));
            if (section.isConfigurationSection("icon")) {
                MenuLayout.put(menu, section, "icon", MenuLayout.icon(arena.icon(), section.getConfigurationSection("icon"), "lore", false,
                        arenaTags(arena)), change("seticon"));
            }
            put("name", arena, Text.mm(arena.displayName()), prompt("setname", "", "admin.arena.prompt-name", arena));
            put("categories", arena, categories(section, arena), split(prompt("category", "add", "admin.arena.prompt-category-add", arena),
                    prompt("category", "remove", "admin.arena.prompt-category-remove", arena)));
            put("build-limit", arena, arena.buildLimit() == null ? value(section, "none") : Component.text(arena.buildLimit()),
                    split(prompt("buildlimit", "", "admin.arena.prompt-build-limit", arena), change("buildlimit", "none")));
            put("teleport", arena, Component.empty(), MenuLayout.choose(plugin, effects, player -> run(player, "tp " + name)));
            put("spawn-1", arena, position(arena.spawn1()), change("setspawn", "1"));
            put("spawn-2", arena, position(arena.spawn2()), change("setspawn", "2"));
            put("spectator", arena, position(arena.spectator()), change("setspectator"));
            put("center", arena, position(arena.center()), change("setcenter"));
            put("corner-1", arena, position(arena.corner1()), change("setcorner", "1"));
            put("corner-2", arena, position(arena.corner2()), change("setcorner", "2"));
            put("box", arena, Component.empty(), change("setbox"));
            put("ffa-spawns", arena, Component.text(arena.extraSpawns().size()), split(change("addspawn"), confirmed("ffa-spawns", arena,
                    player -> run(player, "clearspawns " + name))));
            Arena.ModePoints points = arena.points();
            put("goal-1", arena, position(points.goal1()), change("setgoal", "1"));
            put("goal-2", arena, position(points.goal2()), change("setgoal", "2"));
            put("bed-1", arena, position(points.bed1()), change("setbed", "1"));
            put("bed-2", arena, position(points.bed2()), change("setbed", "2"));
            put("free-for-all", arena, arena.ffa() == null ? value(section, "none") : Component.text(arena.ffa()),
                    split(prompt("ffa", "", "admin.arena.prompt-ffa", arena), change("ffa", "none")));
            put("snapshot", arena, Component.empty(), change("snapshot"));
            put("reset", arena, Component.empty(), confirmed("reset", arena, player -> run(player, "reset " + name)));
            put("pool", arena, Component.text(copies(name)), split(change("pool"), confirmed("pool", arena,
                    player -> run(player, "pool " + name + " clear"))));
            put("delete", arena, Component.empty(), MenuLayout.confirm(plugin, messages, menus, effects,
                    MenuLayout.name(section, "delete", arenaTags(arena)), player -> {
                        run(player, "delete " + name);
                        openList(player);
                    }, this::reopen));
        }

        /** The button {@code button} showing {@code value} as {@code <value>}, if this menu has it. */
        private void put(String button, Arena arena, Component value, BiConsumer<Player, ClickType> action) {
            if (section.isConfigurationSection(button)) {
                MenuLayout.put(menu, section, button, MenuConfig.item(section.getConfigurationSection(button),
                        with(arenaTags(arena), Placeholder.component("value", value))), action);
            }
        }

        private void reopen(Player player) {
            open(player, name, key);
        }

        private Component position(Arena.Position position) {
            return position == null ? value(section, "not-set")
                    : Component.text((int) Math.floor(position.x()) + " " + (int) Math.floor(position.y()) + " " + (int) Math.floor(position.z()));
        }

        /** Runs {@code /duels arena <sub> <arena>} and redraws. */
        private BiConsumer<Player, ClickType> change(String sub) {
            return change(sub, "");
        }

        private BiConsumer<Player, ClickType> change(String sub, String args) {
            return (player, click) -> {
                effects.play(player, "menu-click");
                run(player, (sub + " " + name + " " + args).strip());
                arenas.get(name).ifPresent(this::draw);
            };
        }

        /** Asks to confirm {@code action}, named after the button {@code button}, then comes back here. */
        private BiConsumer<Player, ClickType> confirmed(String button, Arena arena, Consumer<Player> action) {
            return MenuLayout.confirm(plugin, messages, menus, effects, MenuLayout.name(section, button, arenaTags(arena)), player -> {
                action.accept(player);
                reopen(player);
            }, this::reopen);
        }

        /** Closes the menu, asks in chat for the rest of {@code /duels arena <sub> <arena> [args]}, runs it and reopens. */
        private BiConsumer<Player, ClickType> prompt(String sub, String args, String promptKey, Arena arena) {
            return MenuLayout.choose(plugin, effects, player -> MenuLayout.ask(plugin, messages, player, promptKey, arenaTags(arena), text -> {
                run(player, (sub + " " + name + " " + args).strip() + " " + text);
                reopen(player);
            }));
        }
    }

    /** Left-click runs {@code left}; right-click (shift or not) runs {@code right}. */
    private static BiConsumer<Player, ClickType> split(BiConsumer<Player, ClickType> left, BiConsumer<Player, ClickType> right) {
        return (player, click) -> (click.isRightClick() ? right : left).accept(player, click);
    }

    private static Component categories(ConfigurationSection section, Arena arena) {
        return arena.categories().isEmpty() ? value(section, "none") : Component.text(String.join(", ", arena.categories().stream().sorted().toList()));
    }

    private static TagResolver[] arenaTags(Arena arena) {
        return new TagResolver[]{Placeholder.unparsed("id", arena.name()), Placeholder.component("arena", Text.mm(arena.displayName()))};
    }
}
