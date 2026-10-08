package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Button;
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
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static me.angylo.elotecraftDuels.menu.MenuLayout.value;
import static me.angylo.elotecraftDuels.menu.MenuLayout.with;

/**
 * Kit setup in menus: every kit, and a settings menu per kit. Each button runs the matching
 * {@code /duels kit} command as the clicking player, so permissions, checks, saving and messages stay
 * in KitAdminCommand; names, permissions and numbers are asked for in chat. Layouts in menus.yml
 * {@code kit-admin} and {@code kit-settings}.
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
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            MenuLayout.fill(menu, section);
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "kit-admin", e);
        }
    }

    /** Shows the settings of the kit {@code name}, or the kit list if it is gone. */
    public void openSettings(Player viewer, String name) {
        Kit kit = kits.get(name).orElse(null);
        if (kit == null) {
            openList(viewer);
            return;
        }
        try {
            new Editor(menus.get().getConfigurationSection("kit-settings"), settings.get().effects(), kit).menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "kit-settings", e);
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

    /** One open settings menu; redrawn in place after each change. */
    private final class Editor {

        private final ConfigurationSection section;
        private final Effects effects;
        private final String name;
        private final PaginatedMenu menu;

        Editor(ConfigurationSection section, Effects effects, Kit kit) {
            this.section = section;
            this.effects = effects;
            this.name = kit.name();
            this.menu = MenuLayout.frame(plugin, section, kitTags(kit));
            MenuLayout.place(menu, section, "back", MenuLayout.choose(plugin, effects, KitAdminMenu.this::openList));
            MenuLayout.place(menu, section, "close", MenuLayout.choose(plugin, effects, player -> { }));
            draw(kit);
            MenuLayout.fill(menu, section);
        }

        private void draw(Kit kit) {
            List<Button> buttons = new ArrayList<>();
            buttons.add(Button.of(entry("build", kit, onOff(kit.build())), change("build")));
            buttons.add(Button.of(entry("damage", kit, onOff(kit.damage())), change("damage")));
            buttons.add(Button.of(MenuLayout.icon(kit.icon(), section.getConfigurationSection("icon"), "lore", false, kitTags(kit)),
                    change("seticon")));
            buttons.add(Button.of(entry("name", kit, Text.mm(kit.displayName())), prompt("setname", "admin.kit.prompt-name", kit)));
            buttons.add(Button.of(entry("permission", kit, permission(section, kit)),
                    prompt("setpermission", "admin.kit.prompt-permission", kit)));
            buttons.add(Button.of(entry("arenas", kit, kit.arenaCategories().isEmpty() ? value(section, "any")
                    : Component.text(String.join(", ", kit.arenaCategories().stream().sorted().toList()))),
                    prompt("arenas", "admin.kit.prompt-arenas", kit)));
            buttons.add(Button.of(entry("save", kit, Component.empty()), change("save")));
            buttons.add(Button.of(entry("load", kit, Component.empty()), MenuLayout.choose(plugin, effects, player -> run(player, "load " + name))));
            buttons.add(Button.of(entry("delete", kit, Component.empty()), (player, click) -> {
                if (click == ClickType.SHIFT_RIGHT) {
                    MenuLayout.choose(plugin, effects, clicker -> {
                        run(clicker, "delete " + name);
                        openList(clicker);
                    }).accept(player, click);
                }
            }));
            for (KitRule rule : KitRule.values()) {
                buttons.add(rule(kit, rule));
            }
            menu.items(buttons);
        }

        private ItemStack entry(String key, Kit kit, Component value) {
            return MenuConfig.item(section.getConfigurationSection(key), with(kitTags(kit), Placeholder.component("value", value)));
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
            ItemStack icon = MenuLayout.icon(material(template, kind), template,
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

        /** Runs {@code /duels kit <sub> <kit>} and redraws. */
        private BiConsumer<Player, ClickType> change(String sub) {
            return change(sub, "");
        }

        private BiConsumer<Player, ClickType> change(String sub, String args) {
            return (player, click) -> {
                effects.play(player, "menu-click");
                run(player, (sub + " " + name + " " + args).strip());
                kits.get(name).ifPresent(this::draw);
            };
        }

        /** Closes the menu, asks in chat for the rest of {@code /duels kit <sub> <kit>}, runs it and reopens. */
        private BiConsumer<Player, ClickType> prompt(String sub, String promptKey, Kit kit) {
            return prompt(sub, "", promptKey, kit);
        }

        private BiConsumer<Player, ClickType> prompt(String sub, String args, String promptKey, Kit kit, TagResolver... tags) {
            return MenuLayout.choose(plugin, effects, player -> ask(player, promptKey, with(kitTags(kit), tags), text -> {
                run(player, (sub + " " + name + " " + args).strip() + " " + text);
                openSettings(player, name);
            }));
        }

        private Component onOff(boolean on) {
            return value(section, on ? "on" : "off");
        }
    }

    private static Component permission(ConfigurationSection section, Kit kit) {
        return kit.permission() == null ? value(section, "everyone") : Component.text(kit.permission());
    }

    private static Material material(ConfigurationSection template, String key) {
        String raw = template == null ? "" : template.getString(key, "");
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("kit-settings.rule." + key + " is not an item: '" + raw + "'");
        }
        return material;
    }

    private static TagResolver[] kitTags(Kit kit) {
        return new TagResolver[]{Placeholder.unparsed("id", kit.name()), Placeholder.component("kit", Text.mm(kit.displayName()))};
    }
}
