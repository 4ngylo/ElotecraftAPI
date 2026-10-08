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
import me.angylo.elotecraftDuels.kit.KitRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static me.angylo.elotecraftDuels.menu.MenuLayout.value;
import static me.angylo.elotecraftDuels.menu.MenuLayout.with;

/**
 * Kit setup in menus: every kit, and a settings menu and a game rules menu per kit. Each button runs the matching
 * {@code /duels kit} command as the clicking player, so permissions, checks, saving and messages stay
 * in KitAdminCommand; names, permissions and numbers are asked for in chat, and deleting asks to confirm.
 * Layouts in menus.yml {@code kit-admin}, {@code kit-settings} and {@code kit-rules}.
 */
public final class KitAdminMenu {

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;
    private final KitRegistry kits;

    public KitAdminMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings, KitRegistry kits) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
        this.kits = kits;
    }

    /** Shows every kit; clicking one opens its settings. */
    public void openList(Player viewer) {
        ConfigurationSection section = menus.get().getConfigurationSection("kit-admin");
        try {
            Effects effects = settings.get().effects();
            PaginatedMenu menu = MenuLayout.frame(plugin, section);
            menu.items(kits.all().stream().map(kit -> Button.of(listIcon(viewer, section, kit),
                    MenuLayout.choose(plugin, effects, player -> openSettings(player, kit.name())))).toList());
            MenuLayout.place(menu, section, "create", MenuLayout.choose(plugin, effects, player ->
                    ask(player, "admin.kit.prompt-create", new TagResolver[0], text -> {
                        run(player, "create " + text);
                        openList(player);
                    })));
            MenuLayout.place(menu, section, "defaults", MenuLayout.choose(plugin, effects, player -> {
                run(player, "defaults");
                openList(player);
            }));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "kit-admin", e);
        }
    }

    /** Shows the settings of the kit {@code name}, or the kit list if it is gone. */
    public void openSettings(Player viewer, String name) {
        open(viewer, name, "kit-settings", false);
    }

    /** Shows the game rules of the kit {@code name}, or the kit list if it is gone. */
    public void openRules(Player viewer, String name) {
        open(viewer, name, "kit-rules", true);
    }

    private void open(Player viewer, String name, String key, boolean rules) {
        Kit kit = kits.get(name).orElse(null);
        if (kit == null) {
            openList(viewer);
            return;
        }
        try {
            ConfigurationSection section = menus.get().getConfigurationSection(key);
            Effects effects = settings.get().effects();
            (rules ? new RulesEditor(section, effects, kit) : new Editor(section, effects, kit)).open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, key, e);
        }
    }

    private ItemStack listIcon(Player viewer, ConfigurationSection section, Kit kit) {
        long items = kit.items().stream().filter(item -> !item.isEmpty()).count();
        return MenuLayout.icon(kit.icon(), section.getConfigurationSection("kit"), "lore", false, with(kitTags(kit),
                Placeholder.component("permission", permission(section, kit)),
                Placeholder.component("building", messages.get(viewer, kit.build() ? "general.kit-build" : "general.kit-no-build")),
                Placeholder.unparsed("items", String.valueOf(items)),
                Placeholder.unparsed("rules", String.valueOf(kit.rules().size()))));
    }

    /** Runs {@code /duels kit <args>} as {@code player}. */
    private static void run(Player player, String args) {
        player.performCommand("duels kit " + args);
    }

    private void ask(Player player, String promptKey, TagResolver[] tags, Consumer<String> onAnswer) {
        MenuLayout.ask(plugin, messages, player, promptKey, tags, onAnswer);
    }

    /** What the settings and rules menus share: running a change on the kit and redrawing, or asking in chat. */
    private abstract class KitView {

        final ConfigurationSection section;
        final Effects effects;
        final String name;

        KitView(ConfigurationSection section, Effects effects, Kit kit) {
            this.section = section;
            this.effects = effects;
            this.name = kit.name();
        }

        abstract void open(Player viewer);

        abstract void draw(Kit kit);

        /** Opens this view again, after a chat answer or a cancelled confirm. */
        abstract void reopen(Player player);

        ItemStack entry(String key, Kit kit, Component value) {
            return MenuConfig.item(section.getConfigurationSection(key), with(kitTags(kit), Placeholder.component("value", value)));
        }

        /** Runs {@code /duels kit <sub> <kit>} and redraws. */
        BiConsumer<Player, ClickType> change(String sub) {
            return change(sub, "");
        }

        BiConsumer<Player, ClickType> change(String sub, String args) {
            return (player, click) -> {
                effects.play(player, "menu-click");
                run(player, (sub + " " + name + " " + args).strip());
                kits.get(name).ifPresent(this::draw);
            };
        }

        /** Closes the menu, asks in chat for the rest of {@code /duels kit <sub> <kit> [args]}, runs it and reopens. */
        BiConsumer<Player, ClickType> prompt(String sub, String args, String promptKey, Kit kit, TagResolver... tags) {
            return MenuLayout.choose(plugin, effects, player -> ask(player, promptKey, with(kitTags(kit), tags), text -> {
                run(player, (sub + " " + name + " " + args).strip() + " " + text);
                reopen(player);
            }));
        }

        Component onOff(boolean on) {
            return value(section, on ? "on" : "off");
        }
    }

    /** One open settings menu: buttons at fixed slots, redrawn in place after each change. */
    private final class Editor extends KitView {

        private final Menu menu;

        Editor(ConfigurationSection section, Effects effects, Kit kit) {
            super(section, effects, kit);
            this.menu = MenuLayout.fixed(plugin, section, kitTags(kit));
            MenuLayout.put(menu, section, "back", MenuLayout.choose(plugin, effects, KitAdminMenu.this::openList));
            MenuLayout.put(menu, section, "close", MenuLayout.close(plugin, effects));
            MenuLayout.put(menu, section, "delete", MenuLayout.confirm(plugin, messages, menus, effects,
                    MenuLayout.name(section, "delete", kitTags(kit)), player -> {
                        run(player, "delete " + name);
                        openList(player);
                    }, this::reopen), kitTags(kit));
            draw(kit);
        }

        @Override
        void open(Player viewer) {
            menu.open(viewer);
        }

        @Override
        void reopen(Player player) {
            openSettings(player, name);
        }

        @Override
        void draw(Kit kit) {
            MenuLayout.put(menu, section, "icon", MenuLayout.icon(kit.icon(), section.getConfigurationSection("icon"), "lore", false,
                    kitTags(kit)), change("seticon"));
            MenuLayout.put(menu, section, "name", entry("name", kit, Text.mm(kit.displayName())),
                    prompt("setname", "", "admin.kit.prompt-name", kit));
            MenuLayout.put(menu, section, "permission", entry("permission", kit, permission(section, kit)),
                    prompt("setpermission", "", "admin.kit.prompt-permission", kit));
            MenuLayout.put(menu, section, "arenas", entry("arenas", kit, kit.arenaCategories().isEmpty() ? value(section, "any")
                    : Component.text(String.join(", ", kit.arenaCategories().stream().sorted().toList()))),
                    prompt("arenas", "", "admin.kit.prompt-arenas", kit));
            MenuLayout.put(menu, section, "mode", entry("mode", kit, value(section, "mode-" + kit.mode().key())), change("mode"));
            MenuLayout.put(menu, section, "build", entry("build", kit, onOff(kit.build())), change("build"));
            MenuLayout.put(menu, section, "damage", entry("damage", kit, onOff(kit.damage())), change("damage"));
            MenuLayout.put(menu, section, "save", entry("save", kit, Component.empty()), change("save"));
            MenuLayout.put(menu, section, "rules", MenuConfig.item(section.getConfigurationSection("rules"), with(kitTags(kit),
                    Placeholder.unparsed("rules", String.valueOf(kit.rules().size())))),
                    MenuLayout.choose(plugin, effects, player -> openRules(player, name)));
            MenuLayout.put(menu, section, "load", entry("load", kit, Component.empty()),
                    MenuLayout.choose(plugin, effects, player -> run(player, "load " + name)));
        }
    }

    /** One open game rules menu: a button per rule, redrawn in place after each change. */
    private final class RulesEditor extends KitView {

        private final PaginatedMenu menu;

        RulesEditor(ConfigurationSection section, Effects effects, Kit kit) {
            super(section, effects, kit);
            this.menu = MenuLayout.frame(plugin, section, kitTags(kit));
            MenuLayout.place(menu, section, "back", MenuLayout.choose(plugin, effects, player -> openSettings(player, name)));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            draw(kit);
        }

        @Override
        void open(Player viewer) {
            menu.open(viewer);
        }

        @Override
        void reopen(Player player) {
            openRules(player, name);
        }

        @Override
        void draw(Kit kit) {
            menu.items(Arrays.stream(KitRule.values()).map(rule -> rule(kit, rule)).toList());
        }

        /** Flags: left-click flips the value, right-click resets it. Numbers: left-click asks for one. */
        private Button rule(Kit kit, KitRule rule) {
            ConfigurationSection template = section.getConfigurationSection("rule");
            boolean flagOn = rule.isFlag() && kit.flag(rule, settings.get());
            OptionalInt number = kit.number(rule);
            Component value = rule.isFlag() ? onOff(flagOn)
                    : number.isPresent() ? Component.text(rule.format(number.getAsInt())) : value(section, "vanilla");
            boolean set = kit.rules().containsKey(rule);
            String kind = rule.isFlag() ? (flagOn ? "flag-on" : "flag-off") : rule.isSeconds() ? "seconds" : "number";
            ItemStack icon = MenuLayout.icon(MenuLayout.material(template, kind), template,
                    rule.isFlag() ? "flag-lore" : kind + "-lore", set, with(kitTags(kit), Placeholder.unparsed("rule", rule.key()),
                            Placeholder.component("value", value), Placeholder.component("state", value(section, set ? "set" : "default"))));
            BiConsumer<Player, ClickType> numberPrompt = prompt("rule", rule.key(), "admin.kit.prompt-" + kind, kit,
                    Placeholder.unparsed("rule", rule.key()), Placeholder.unparsed("max", String.valueOf(rule.max())));
            return Button.of(icon, (player, click) -> {
                if (click.isRightClick()) {
                    change("rule", rule.key() + " default").accept(player, click);
                } else if (rule.isFlag()) {
                    change("rule", rule.key() + " " + !flagOn).accept(player, click);
                } else {
                    numberPrompt.accept(player, click);
                }
            });
        }
    }

    private static Component permission(ConfigurationSection section, Kit kit) {
        return kit.permission() == null ? value(section, "everyone") : Component.text(kit.permission());
    }

    private static TagResolver[] kitTags(Kit kit) {
        return new TagResolver[]{Placeholder.unparsed("id", kit.name()), Placeholder.component("kit", Text.mm(kit.displayName()))};
    }
}
