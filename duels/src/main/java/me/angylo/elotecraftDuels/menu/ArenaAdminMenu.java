package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static me.angylo.elotecraftDuels.menu.MenuLayout.value;
import static me.angylo.elotecraftDuels.menu.MenuLayout.with;

/**
 * Arena setup in menus: every arena built by hand (pregen copies are counted, not listed), and a settings
 * menu per arena. Like {@link KitAdminMenu}, each button runs the matching {@code /duels arena} command as
 * the clicking player; points are taken where the player stands. Layouts in menus.yml {@code arena-admin}
 * and {@code arena-settings}.
 */
public final class ArenaAdminMenu {

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;

    public ArenaAdminMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, ArenaRegistry arenas) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.arenas = arenas;
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
        ConfigurationSection section = menus.get().getConfigurationSection("arena-admin");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(arenas.all().stream().filter(arena -> arena.copy() == null).map(arena -> Button.of(listIcon(viewer, section, arena),
                    MenuLayout.choose(plugin, effects, player -> openSettings(player, arena.name())))).toList());
            MenuLayout.place(menu, section, "create", MenuLayout.choose(plugin, effects, player ->
                    MenuLayout.ask(plugin, messages, player, "admin.arena.prompt-create", new TagResolver[0], text -> {
                        run(player, "create " + text);
                        openList(player);
                    })));
            MenuLayout.place(menu, section, "import", MenuLayout.choose(plugin, effects, player ->
                    MenuLayout.ask(plugin, messages, player, "admin.arena.prompt-import", new TagResolver[0], text -> run(player, "import " + text))));
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            MenuLayout.fill(menu, section);
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "arena-admin", e);
        }
    }

    /** Shows the settings of the arena {@code name}, or the arena list if it is gone. */
    public void openSettings(Player viewer, String name) {
        Arena arena = arenas.get(name).orElse(null);
        if (arena == null) {
            openList(viewer);
            return;
        }
        try {
            new Editor(menus.get().getConfigurationSection("arena-settings"), settings.get().effects(), viewer, arena).menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "arena-settings", e);
        }
    }

    private ItemStack listIcon(Player viewer, ConfigurationSection section, Arena arena) {
        return MenuLayout.icon(arena.icon(), section.getConfigurationSection("arena"), "lore", false, with(arenaTags(arena),
                Placeholder.unparsed("world", arena.world()),
                Placeholder.component("status", status(messages, viewer, arena)),
                Placeholder.component("categories", categories(section, arena)),
                Placeholder.unparsed("copies", String.valueOf(arenas.copiesOf(arena.name()).size()))));
    }

    /** Runs {@code /duels arena <args>} as {@code player}. */
    private static void run(Player player, String args) {
        player.performCommand("duels arena " + args);
    }

    /** One open settings menu; redrawn in place after each change. */
    private final class Editor {

        private final ConfigurationSection section;
        private final Effects effects;
        private final Player viewer;
        private final String name;
        private final PaginatedMenu menu;

        Editor(ConfigurationSection section, Effects effects, Player viewer, Arena arena) {
            this.section = section;
            this.effects = effects;
            this.viewer = viewer;
            this.name = arena.name();
            this.menu = MenuLayout.frame(plugin, section, arenaTags(arena));
            MenuLayout.place(menu, section, "back", MenuLayout.choose(plugin, effects, ArenaAdminMenu.this::openList));
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            draw(arena);
            MenuLayout.fill(menu, section);
        }

        private void draw(Arena arena) {
            List<Button> buttons = new ArrayList<>();
            buttons.add(Button.of(entry("status", arena, status(messages, viewer, arena)), change("info")));
            buttons.add(Button.of(entry("enabled", arena, value(section, arena.enabled() ? "on" : "off")), change("toggle")));
            buttons.add(Button.of(entry("spawn-1", arena, position(arena.spawn1())), change("setspawn", "1")));
            buttons.add(Button.of(entry("spawn-2", arena, position(arena.spawn2())), change("setspawn", "2")));
            buttons.add(Button.of(entry("corner-1", arena, position(arena.corner1())), change("setcorner", "1")));
            buttons.add(Button.of(entry("corner-2", arena, position(arena.corner2())), change("setcorner", "2")));
            buttons.add(Button.of(entry("box", arena, Component.empty()), change("setbox")));
            buttons.add(Button.of(entry("spectator", arena, position(arena.spectator())), change("setspectator")));
            buttons.add(Button.of(entry("center", arena, position(arena.center())), change("setcenter")));
            buttons.add(Button.of(entry("ffa-spawns", arena, Component.text(arena.extraSpawns().size())),
                    split(change("addspawn"), confirmed(change("clearspawns")))));
            Arena.ModePoints points = arena.points();
            buttons.add(Button.of(entry("goal-1", arena, position(points.goal1())), change("setgoal", "1")));
            buttons.add(Button.of(entry("goal-2", arena, position(points.goal2())), change("setgoal", "2")));
            buttons.add(Button.of(entry("bed-1", arena, position(points.bed1())), change("setbed", "1")));
            buttons.add(Button.of(entry("bed-2", arena, position(points.bed2())), change("setbed", "2")));
            buttons.add(Button.of(MenuLayout.icon(arena.icon(), section.getConfigurationSection("icon"), "lore", false, arenaTags(arena)),
                    change("seticon")));
            buttons.add(Button.of(entry("name", arena, Text.mm(arena.displayName())), prompt("setname", "", "admin.arena.prompt-name", arena)));
            buttons.add(Button.of(entry("categories", arena, categories(section, arena)),
                    split(prompt("category", "add", "admin.arena.prompt-category-add", arena),
                            prompt("category", "remove", "admin.arena.prompt-category-remove", arena))));
            buttons.add(Button.of(entry("build-limit", arena, arena.buildLimit() == null ? value(section, "none")
                    : Component.text(arena.buildLimit())), split(prompt("buildlimit", "", "admin.arena.prompt-build-limit", arena),
                    change("buildlimit", "none"))));
            buttons.add(Button.of(entry("teleport", arena, Component.empty()), MenuLayout.choose(plugin, effects, player -> run(player, "tp " + name))));
            buttons.add(Button.of(entry("snapshot", arena, Component.empty()), change("snapshot")));
            buttons.add(Button.of(entry("reset", arena, Component.empty()), confirmed(change("reset"))));
            buttons.add(Button.of(entry("pregen", arena, Component.text(arenas.copiesOf(name).size())),
                    split(prompt("pregen", "", "admin.arena.prompt-pregen", arena), confirmed(change("pregen", "clear")))));
            buttons.add(Button.of(entry("delete", arena, Component.empty()), confirmed(MenuLayout.choose(plugin, effects, player -> {
                run(player, "delete " + name);
                openList(player);
            }))));
            menu.items(buttons);
        }

        private ItemStack entry(String key, Arena arena, Component value) {
            return MenuConfig.item(section.getConfigurationSection(key), with(arenaTags(arena), Placeholder.component("value", value)));
        }

        private Component position(Arena.Position position) {
            return position == null ? value(section, "not-set")
                    : Component.text((int) Math.floor(position.x()) + " " + (int) Math.floor(position.y()) + " " + (int) Math.floor(position.z()));
        }

        /** Runs {@code /duels arena <sub> <arena> [args]} and redraws. */
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

        /** Closes the menu, asks in chat for the rest of {@code /duels arena <sub> <arena> [args]}, runs it and reopens. */
        private BiConsumer<Player, ClickType> prompt(String sub, String args, String promptKey, Arena arena) {
            return MenuLayout.choose(plugin, effects, player -> MenuLayout.ask(plugin, messages, player, promptKey, arenaTags(arena), text -> {
                run(player, (sub + " " + name + " " + args).strip() + " " + text);
                openSettings(player, name);
            }));
        }
    }

    /** Left-click runs {@code left}; right-click (shift or not) runs {@code right}. */
    private static BiConsumer<Player, ClickType> split(BiConsumer<Player, ClickType> left, BiConsumer<Player, ClickType> right) {
        return (player, click) -> (click.isRightClick() ? right : left).accept(player, click);
    }

    /** Runs {@code action} only on shift + right-click, for changes that are hard to undo. */
    private static BiConsumer<Player, ClickType> confirmed(BiConsumer<Player, ClickType> action) {
        return (player, click) -> {
            if (click == ClickType.SHIFT_RIGHT) {
                action.accept(player, click);
            }
        };
    }

    private static Component categories(ConfigurationSection section, Arena arena) {
        return arena.categories().isEmpty() ? value(section, "none") : Component.text(String.join(", ", arena.categories().stream().sorted().toList()));
    }

    private static TagResolver[] arenaTags(Arena arena) {
        return new TagResolver[]{Placeholder.unparsed("id", arena.name()), Placeholder.component("arena", Text.mm(arena.displayName()))};
    }
}
