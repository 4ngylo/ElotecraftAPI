package me.angylo.elotecraftDuels.command;

import me.angylo.elotecraftAPI.command.Args;
import me.angylo.elotecraftAPI.command.CommandBuilder;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Duels;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.menu.KitAdminMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import static me.angylo.elotecraftDuels.command.AdminCommand.menu;
import static me.angylo.elotecraftDuels.command.AdminCommand.with;

/** {@code /duels kit}: kit setup. Shares saving, renaming and icons with {@link AdminCommand}. */
final class KitAdminCommand {

    private static final String ANY = "any";
    private static final String DEFAULT = "default";

    private final AdminCommand admin;
    private final Duels duels;
    private final Messages messages;
    private final KitRegistry kits;
    private final KitAdminMenu kitMenu;

    KitAdminCommand(AdminCommand admin, Duels duels, KitAdminMenu kitMenu) {
        this.admin = admin;
        this.duels = duels;
        this.messages = duels.messages();
        this.kits = duels.kits();
        this.kitMenu = kitMenu;
    }

    CommandBuilder node() {
        BiFunction<CommandSender, String[], List<String>> kitNames = (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args) : List.of();

        return CommandBuilder.create("kit").permission("duels.admin.kit")
                .executes(menu(messages, "command.kit-help", kitMenu::openList,
                        (player, args) -> withKit(player, args, (kit, rest) -> kitMenu.openSettings(player, kit.name()))), kitNames)
                .sub("help", null, (sender, args) -> messages.send(sender, "command.kit-help"))
                .playerSub("create", null, this::createKit)
                .playerSub("save", null, (player, args) -> withKit(player, args, (kit, rest) -> {
                    if (isEmpty(player.getInventory())) {
                        messages.send(player, "admin.kit.empty-inventory");
                        return;
                    }
                    admin.save(player, kits.update(kit.withItems(player.getInventory())), "admin.kit.saved", kitTags(kit));
                }), kitNames)
                .playerSub("load", null, (player, args) -> withKit(player, args, (kit, rest) -> {
                    if (duels.matches().isBusy(player) || !isEmpty(player.getInventory())) {
                        messages.send(player, "admin.kit.inventory-not-empty");
                        return;
                    }
                    kit.apply(player);
                    messages.send(player, "admin.kit.loaded", kitTags(kit));
                }), kitNames)
                .sub("delete", null, (sender, args) -> withKit(sender, args, (kit, rest) ->
                        admin.save(sender, kits.delete(kit.name()), "admin.kit.deleted", kitTags(kit))), kitNames)
                .playerSub("seticon", null, (player, args) -> withKit(player, args, (kit, rest) ->
                        admin.heldIcon(player).ifPresent(icon -> admin.save(player, kits.update(kit.withIcon(icon)),
                                "admin.kit.icon-set", kitTags(kit)))), kitNames)
                .sub("setname", null, (sender, args) -> withKit(sender, args, (kit, rest) -> admin.rename(sender, rest,
                        text -> admin.save(sender, kits.update(kit.withDisplayName(text)), "admin.kit.name-set",
                                kitTags(kit.withDisplayName(text))))), kitNames)
                .sub("setpermission", null, (sender, args) -> withKit(sender, args, (kit, rest) -> setPermission(sender, kit, rest)),
                        (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args)
                                : args.length == 2 ? Args.filter(List.of("none", "duels.kit." + args[0]), args) : List.of())
                .sub("build", null, (sender, args) -> withKit(sender, args, (kit, rest) -> {
                    Kit changed = kit.withBuild(!kit.build());
                    admin.save(sender, kits.update(changed), changed.build() ? "admin.kit.build-on" : "admin.kit.build-off", kitTags(kit));
                }), kitNames)
                .sub("damage", null, (sender, args) -> withKit(sender, args, (kit, rest) -> {
                    Kit changed = kit.withDamage(!kit.damage());
                    admin.save(sender, kits.update(changed), changed.damage() ? "admin.kit.damage-on" : "admin.kit.damage-off", kitTags(kit));
                }), kitNames)
                .sub("effect", null, (sender, args) -> withKit(sender, args, (kit, rest) -> effect(sender, kit, rest)),
                        (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args)
                                : args.length == 2 ? Args.filter(Registry.MOB_EFFECT.stream().map(type -> type.getKey().getKey()).sorted().toList(), args)
                                : args.length == 3 ? Args.filter(List.of("1", "2", "0"), args) : List.of())
                .sub("rule", null, (sender, args) -> withKit(sender, args, (kit, rest) -> kitRule(sender, kit, rest)),
                        (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args)
                                : args.length == 2 ? Args.filter(KitRule.keys(), args)
                                : args.length == 3 ? Args.filter(KitRule.byKey(args[1]).map(KitAdminCommand::ruleValues).orElse(List.of()), args)
                                : List.of())
                .sub("defaults", null, (sender, args) -> messages.send(sender, "admin.kit.defaults-added",
                        Placeholder.unparsed("count", String.valueOf(kits.installDefaults()))))
                .sub("arenas", null, (sender, args) -> withKit(sender, args, (kit, rest) -> kitArenas(sender, kit, rest)),
                        (sender, args) -> args.length == 1 ? Args.filter(kits.names(), args)
                                : Args.filter(args.length == 2 ? withAny(admin.categories()) : admin.categories(), args))
                .sub("list", null, (sender, args) -> listKits(sender));
    }

    private void createKit(Player player, String[] args) {
        String name = Args.get(args, 0).toLowerCase(Locale.ROOT);
        if (!ArenaRegistry.validName(name)) {
            messages.send(player, "admin.invalid-name");
            return;
        }
        if (kits.get(name).isPresent()) {
            messages.send(player, "admin.kit.exists", Placeholder.unparsed("id", name));
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (isEmpty(inventory)) {
            messages.send(player, "admin.kit.empty-inventory");
            return;
        }
        Material hand = inventory.getItemInMainHand().getType();
        admin.save(player, kits.create(name, hand.isAir() ? Kit.DEFAULT_ICON : hand, inventory), "admin.kit.created",
                Placeholder.unparsed("id", name));
    }

    /** {@code arenas <kit> <category...>|any}: which arena categories the kit's duels use. */
    private void kitArenas(CommandSender sender, Kit kit, String[] rest) {
        if (rest.length == 0) {
            messages.send(sender, "admin.kit.arenas-usage");
            return;
        }
        if (rest.length == 1 && rest[0].equalsIgnoreCase(ANY)) {
            admin.save(sender, kits.update(kit.withArenaCategories(Set.of())), "admin.kit.arenas-any", kitTags(kit));
            return;
        }
        Set<String> categories = new TreeSet<>();
        for (String raw : rest) {
            String category = raw.toLowerCase(Locale.ROOT);
            if (!ArenaRegistry.validName(category)) {
                messages.send(sender, "admin.kit.arenas-usage");
                return;
            }
            categories.add(category);
        }
        admin.save(sender, kits.update(kit.withArenaCategories(categories)), "admin.kit.arenas-set",
                with(kitTags(kit), Placeholder.unparsed("categories", String.join(", ", categories))));
    }

    /** {@code rule <kit> [rule] [value|default]}: lists the kit's game rules, or sets one. */
    private void kitRule(CommandSender sender, Kit kit, String[] rest) {
        if (rest.length == 0) {
            messages.send(sender, "admin.kit.rules-header", kitTags(kit));
            for (KitRule rule : KitRule.values()) {
                messages.send(sender, kit.rules().containsKey(rule) ? "admin.kit.rule-entry" : "admin.kit.rule-entry-default",
                        Placeholder.unparsed("rule", rule.key()), Placeholder.component("value", ruleValue(sender, kit, rule)));
            }
            return;
        }
        Optional<KitRule> rule = KitRule.byKey(rest[0]);
        if (rule.isEmpty() || rest.length != 2) {
            messages.send(sender, "admin.kit.rule-usage", Placeholder.unparsed("rules", String.join(", ", KitRule.keys())));
            return;
        }
        Object value = null;
        if (!rest[1].equalsIgnoreCase(DEFAULT)) {
            try {
                value = rule.get().parse(rest[1]);
            } catch (IllegalArgumentException e) {
                messages.send(sender, rule.get().isFlag() ? "admin.kit.rule-flag-usage"
                                : rule.get().isSeconds() ? "admin.kit.rule-seconds-usage" : "admin.kit.rule-number-usage",
                        Placeholder.unparsed("rule", rule.get().key()), Placeholder.unparsed("max", String.valueOf(rule.get().max())));
                return;
            }
        }
        Kit changed = kit.withRule(rule.get(), value);
        admin.save(sender, kits.update(changed), "admin.kit.rule-set", with(kitTags(kit), Placeholder.unparsed("rule", rule.get().key()),
                Placeholder.component("value", ruleValue(sender, changed, rule.get()))));
    }

    /** {@code effect <kit> [effect level]}: lists the kit's potion effects, or sets one; level 0 removes it. */
    private void effect(CommandSender sender, Kit kit, String[] rest) {
        if (rest.length == 0) {
            messages.send(sender, "admin.kit.effects", with(kitTags(kit), Placeholder.unparsed("effects", kit.effects().isEmpty() ? "-"
                    : String.join(", ", kit.effects().stream().map(effect -> effect.getType().getKey().getKey() + " " + (effect.getAmplifier() + 1)).toList()))));
            return;
        }
        Optional<PotionEffectType> type = Kit.effectType(rest[0]);
        OptionalInt level = rest.length == 2 ? Args.integer(rest[1], 0, Kit.MAX_EFFECT_LEVEL) : OptionalInt.empty();
        if (type.isEmpty() || level.isEmpty() || level.getAsInt() < 0 || level.getAsInt() > Kit.MAX_EFFECT_LEVEL) {
            messages.send(sender, "admin.kit.effect-usage", Placeholder.unparsed("max", String.valueOf(Kit.MAX_EFFECT_LEVEL)));
            return;
        }
        admin.save(sender, kits.update(kit.withEffect(type.get(), level.getAsInt())),
                level.getAsInt() == 0 ? "admin.kit.effect-removed" : "admin.kit.effect-set",
                with(kitTags(kit), Placeholder.unparsed("effect", type.get().getKey().getKey()),
                        Placeholder.unparsed("level", String.valueOf(level.getAsInt()))));
    }

    /** true or false, a number, or vanilla for an unset number. */
    private Component ruleValue(CommandSender viewer, Kit kit, KitRule rule) {
        if (rule.isFlag()) {
            return Component.text(kit.flag(rule, duels.settings()));
        }
        OptionalInt number = kit.number(rule);
        return number.isPresent() ? Component.text(rule.format(number.getAsInt())) : messages.get(viewer, "admin.kit.rule-vanilla");
    }

    private static List<String> ruleValues(KitRule rule) {
        return rule.isFlag() ? List.of("true", "false", DEFAULT)
                : rule.isSeconds() ? List.of("0", "15", DEFAULT)
                : List.of("0", rule == KitRule.ROUNDS_TO_WIN ? "2" : "100", DEFAULT);
    }

    private static List<String> withAny(List<String> categories) {
        List<String> all = new ArrayList<>(categories);
        all.add(ANY);
        return all;
    }

    private void setPermission(CommandSender sender, Kit kit, String[] rest) {
        String permission = Args.get(rest, 0).toLowerCase(Locale.ROOT);
        if (rest.length != 1) {
            messages.send(sender, "admin.kit.invalid-permission");
        } else if (permission.equals("none")) {
            admin.save(sender, kits.update(kit.withPermission(null)), "admin.kit.permission-cleared", kitTags(kit));
        } else if (KitRegistry.validPermission(permission)) {
            admin.save(sender, kits.update(kit.withPermission(permission)), "admin.kit.permission-set",
                    with(kitTags(kit), Placeholder.unparsed("permission", permission)));
        } else {
            messages.send(sender, "admin.kit.invalid-permission");
        }
    }

    private void listKits(CommandSender sender) {
        List<Kit> all = kits.all();
        if (all.isEmpty()) {
            messages.send(sender, "admin.kit.list-empty");
            return;
        }
        messages.send(sender, "admin.kit.list-header", Placeholder.unparsed("count", String.valueOf(all.size())));
        for (Kit kit : all) {
            messages.send(sender, "admin.kit.list-entry", with(kitTags(kit),
                    Placeholder.unparsed("permission", kit.permission() == null ? "" : kit.permission())));
        }
    }

    private void withKit(CommandSender sender, String[] args, BiConsumer<Kit, String[]> action) {
        Optional<Kit> kit = kits.get(Args.get(args, 0));
        if (kit.isEmpty()) {
            messages.send(sender, "general.kit-not-found", Placeholder.unparsed("kit", Args.get(args, 0)));
            return;
        }
        action.accept(kit.get(), Arrays.copyOfRange(args, 1, args.length));
    }

    private static boolean isEmpty(PlayerInventory inventory) {
        return Arrays.stream(inventory.getContents()).allMatch(item -> item == null || item.isEmpty());
    }

    private static TagResolver[] kitTags(Kit kit) {
        return new TagResolver[]{Placeholder.unparsed("id", kit.name()), Placeholder.component("kit", Text.mm(kit.displayName()))};
    }
}
