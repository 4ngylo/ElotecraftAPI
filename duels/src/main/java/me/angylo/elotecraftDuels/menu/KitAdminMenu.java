package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.input.AnvilInput;
import me.angylo.elotecraftAPI.menu.Button;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.menu.MenuConfig;
import me.angylo.elotecraftAPI.menu.PaginatedMenu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.kit.KitRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
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

    private static final Duration ANVIL_TIME = Duration.ofSeconds(60);
    private static final String ANVIL_TEXT = "#";

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
            MenuLayout.place(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.place(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, "kit-admin", e);
        }
    }

    /** Shows the settings of the kit {@code name}, or the kit list if it is gone. */
    public void openSettings(Player viewer, String name) {
        open(viewer, name, "kit-settings", Editor::new);
    }

    /** Shows the game rules of the kit {@code name}, or the kit list if it is gone. */
    public void openRules(Player viewer, String name) {
        open(viewer, name, "kit-rules", RulesEditor::new);
    }

    /** Shows every potion effect for the kit {@code name}, the ones it gives first, or the kit list if it is gone. */
    public void openEffects(Player viewer, String name) {
        open(viewer, name, "kit-effects", EffectsEditor::new);
    }

    private void open(Player viewer, String name, String key, ViewMaker maker) {
        Kit kit = kits.get(name).orElse(null);
        if (kit == null) {
            openList(viewer);
            return;
        }
        try {
            ConfigurationSection section = menus.get().getConfigurationSection(key);
            maker.make(section, settings.get().effects(), kit).open(viewer);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, viewer, key, e);
        }
    }

    private ItemStack listIcon(Player viewer, ConfigurationSection section, Kit kit) {
        long items = kit.items().stream().filter(item -> !item.isEmpty()).count();
        return MenuLayout.icon(kit.icon(), section.getConfigurationSection("kit"), "lore", false, with(kitTags(kit),
                Placeholder.component("permission", permission(section, kit)),
                Placeholder.component("building", messages.get(viewer, kit.flag(KitRule.BUILD, settings.get()) ? "general.kit-build" : "general.kit-no-build")),
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

    /** Asks {@code player} for text in an anvil; tests swap {@link #anvil}, as MockBukkit opens no anvil. */
    @FunctionalInterface
    public interface AnvilAsk {
        CompletableFuture<Optional<String>> ask(Plugin plugin, Player player, Component title, String initialText);
    }

    public static AnvilAsk anvil = (plugin, player, title, initialText) -> AnvilInput.ask(plugin, player, title, initialText, ANVIL_TIME);

    /**
     * Asks in an anvil titled {@code titleKey}, its text starting as {@link #ANVIL_TEXT}; the answer, without that mark, goes
     * to {@code onAnswer} a tick later, outside the anvil's click event, and closing the anvil runs {@code onCancel} instead.
     */
    private void askAnvil(Player player, String titleKey, TagResolver[] tags, Consumer<String> onAnswer, Consumer<Player> onCancel) {
        anvil.ask(plugin, player, messages.get(player, titleKey, tags), ANVIL_TEXT).thenAccept(answer -> {
            if (plugin.isEnabled()) {
                Tasks.sync(plugin, () -> {
                    if (player.isOnline()) {
                        answer.map(text -> text.replace(ANVIL_TEXT, "").strip())
                                .ifPresentOrElse(onAnswer, () -> onCancel.accept(player));
                    }
                });
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Menu anvil answer failed", error);
            return null;
        });
    }

    /** Builds one of the kit views. */
    @FunctionalInterface
    private interface ViewMaker {
        KitView make(ConfigurationSection section, Effects effects, Kit kit);
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
            this.menu = MenuLayout.fixed(plugin, section, MenuLayout.plain("kit", kit.displayName()));
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
            MenuLayout.put(menu, section, "save", entry("save", kit, Component.empty()), change("save"));
            MenuLayout.put(menu, section, "edit", entry("edit", kit, Component.empty()),
                    MenuLayout.choose(plugin, effects, player -> run(player, "edit " + name)));
            MenuLayout.put(menu, section, "rules", MenuConfig.item(section.getConfigurationSection("rules"), with(kitTags(kit),
                    Placeholder.unparsed("rules", String.valueOf(kit.rules().size())))),
                    MenuLayout.choose(plugin, effects, player -> openRules(player, name)));
            MenuLayout.put(menu, section, "effects", entry("effects", kit, Component.text(kit.effects().size())),
                    MenuLayout.choose(plugin, effects, player -> openEffects(player, name)));
            MenuLayout.put(menu, section, "load", entry("load", kit, Component.empty()),
                    MenuLayout.choose(plugin, effects, player -> run(player, "load " + name)));
        }
    }

    /** One open game rules menu: a button per rule, redrawn in place after each change. */
    private final class RulesEditor extends KitView {

        private final PaginatedMenu menu;

        RulesEditor(ConfigurationSection section, Effects effects, Kit kit) {
            super(section, effects, kit);
            this.menu = MenuLayout.frame(plugin, section, MenuLayout.plain("kit", kit.displayName()));
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
            OptionalInt number = kit.number(rule, settings.get());
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

    /**
     * One open effects menu: a potion per effect, the kit's own first. Left-click asks the amplifier, then the seconds, in
     * two anvils and runs {@code /duels kit effect}; right-click takes a given effect away.
     */
    private final class EffectsEditor extends KitView {

        private final PaginatedMenu menu;

        EffectsEditor(ConfigurationSection section, Effects effects, Kit kit) {
            super(section, effects, kit);
            this.menu = MenuLayout.frame(plugin, section, MenuLayout.plain("kit", kit.displayName()));
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
            openEffects(player, name);
        }

        @Override
        void draw(Kit kit) {
            menu.items(Registry.MOB_EFFECT.stream()
                    .sorted(Comparator.comparing((PotionEffectType type) -> kit.effectOf(type).isEmpty()).thenComparing(Kit::effectName))
                    .map(type -> button(kit, type)).toList());
        }

        private Button button(Kit kit, PotionEffectType type) {
            ConfigurationSection template = section.getConfigurationSection("effect");
            Optional<PotionEffect> given = kit.effectOf(type);
            int seconds = given.map(Kit::seconds).orElse(0);
            ItemStack icon = MenuLayout.icon(MenuLayout.material(template, "material"), template, given.isPresent() ? "given-lore" : "lore",
                    given.isPresent(), with(kitTags(kit), Placeholder.unparsed("effect", Kit.effectName(type)),
                            Placeholder.unparsed("amplifier", String.valueOf(given.map(PotionEffect::getAmplifier).orElse(0))),
                            Placeholder.component("duration", seconds == 0 ? value(section, "whole-fight") : Component.text(seconds + "s"))));
            icon.editMeta(PotionMeta.class, meta -> meta.setColor(type.getColor()));
            BiConsumer<Player, ClickType> ask = MenuLayout.choose(plugin, effects, player -> askAmplifier(player, kit, type));
            return Button.of(icon, (player, click) -> {
                if (click.isRightClick() && given.isPresent()) {
                    change("effect", Kit.effectName(type) + " remove").accept(player, click);
                } else {
                    ask.accept(player, click);
                }
            });
        }

        private void askAmplifier(Player player, Kit kit, PotionEffectType type) {
            TagResolver[] tags = with(kitTags(kit), Placeholder.unparsed("effect", Kit.effectName(type)),
                    Placeholder.unparsed("max", String.valueOf(Kit.MAX_AMPLIFIER)),
                    Placeholder.unparsed("max_seconds", String.valueOf(Kit.MAX_EFFECT_SECONDS)));
            askAnvil(player, "admin.kit.anvil-amplifier", tags, amplifier -> {
                if (Args.integer(amplifier, 0, Kit.MAX_AMPLIFIER).isEmpty()) {
                    messages.send(player, "admin.kit.effect-usage", tags);
                    reopen(player);
                    return;
                }
                askAnvil(player, "admin.kit.anvil-duration", tags, seconds -> {
                    run(player, "effect " + name + " " + Kit.effectName(type) + " " + amplifier + " " + seconds);
                    reopen(player);
                }, this::reopen);
            }, this::reopen);
        }
    }

    private static Component permission(ConfigurationSection section, Kit kit) {
        return kit.permission() == null ? value(section, "everyone") : Component.text(kit.permission());
    }

    private static TagResolver[] kitTags(Kit kit) {
        return new TagResolver[]{Placeholder.unparsed("id", kit.name()), Placeholder.component("kit", Text.mm(kit.displayName()))};
    }
}
