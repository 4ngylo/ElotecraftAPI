package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.Permissible;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Items given to both fighters: a full player inventory (storage, armor and off hand). Immutable:
 * items are copied in and out, and the {@code with...} methods return a changed copy.
 *
 * @param displayName MiniMessage, set by admins
 * @param permission  needed to pick the kit, or null for everyone
 * @param arenaCategories arena categories duels with this kit may use; empty for any arena
 * @param rules       the game rules this kit sets ({@link KitRule#BUILD} and {@link KitRule#DAMAGE} among them), each
 *                    value fitting its {@link KitRule}; unset ones use config.yml {@code rules.kit-defaults}
 * @param rewards     paid for a won duel with this kit on top of config.yml's {@code rewards}
 * @param effects     potion effects fighters get at the countdown, without particles: for the whole fight or some seconds
 * @param mode        how a duel is won: knockouts, or the bridge and bed fight modes (two sides, build kits only)
 */
public record Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items,
                  Set<String> arenaCategories, Map<KitRule, Object> rules, Rewards rewards,
                  List<PotionEffect> effects, Mode mode) {

    public static final Material DEFAULT_ICON = Material.IRON_SWORD;
    /** The name of every player's custom kit ({@link CustomKits}); admin kits cannot use it. */
    public static final String CUSTOM = "custom";
    /** The highest effect amplifier: 0 is level I, 2 is level III. */
    public static final int MAX_AMPLIFIER = 2;
    /** The longest effect in seconds; 0 lasts the whole fight. */
    public static final int MAX_EFFECT_SECONDS = 9999;
    private static final int TICKS_PER_SECOND = 20;

    /**
     * How a fight with two sides is won. {@code BRIDGE}: knocked-out fighters come back at their spawn, and walking into
     * the other side's goal wins the round; placed blocks stay between rounds. {@code BED_FIGHT}: knocked-out fighters
     * come back while their side's bed stands, and an enemy may break it. Both need the arena's goals or beds.
     */
    public enum Mode {
        NORMAL, BRIDGE, BED_FIGHT;

        /** Lower case with -, for kits.yml, commands and messages.yml. */
        public String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public static Optional<Mode> byKey(String key) {
            for (Mode mode : values()) {
                if (mode.key().equalsIgnoreCase(key)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    /** The extra money and commands for the winner and the loser of a duel with a kit; set in kits.yml only. */
    public record Rewards(Reward win, Reward loss) {

        public static final Rewards NONE = new Rewards(Reward.NONE, Reward.NONE);
    }

    public Kit {
        items = items.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList();
        arenaCategories = Set.copyOf(arenaCategories);
        rules.forEach((rule, value) -> {
            if (!rule.accepts(value)) {
                throw new IllegalArgumentException("Invalid value for kit rule " + rule.key() + ": " + value);
            }
        });
        rules = Map.copyOf(rules);
        effects = List.copyOf(effects);
        mode = mode == null ? Mode.NORMAL : mode;
    }

    /** A normal kit. */
    public Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items,
               Set<String> arenaCategories, Map<KitRule, Object> rules, Rewards rewards, List<PotionEffect> effects) {
        this(name, displayName, icon, permission, items, arenaCategories, rules, rewards, effects, Mode.NORMAL);
    }

    /** A kit without effects. */
    public Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items,
               Set<String> arenaCategories, Map<KitRule, Object> rules, Rewards rewards) {
        this(name, displayName, icon, permission, items, arenaCategories, rules, rewards, List.of());
    }

    /** A kit with every game rule at its default and no rewards of its own. */
    public Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items,
               Set<String> arenaCategories) {
        this(name, displayName, icon, permission, items, arenaCategories, Map.of(), Rewards.NONE);
    }

    /** A kit holding a copy of everything in {@code inventory}, armor and off hand included. */
    static Kit of(String name, Material icon, PlayerInventory inventory) {
        return new Kit(name, name, icon, null, Arrays.asList(inventory.getContents()), Set.of());
    }

    /**
     * A player's custom kit: {@code items} with everything else (rules, arenas, permission, rewards, effects) from
     * {@code base}, the kit whose items they were picked from.
     */
    static Kit custom(Kit base, String displayName, List<ItemStack> items) {
        return new Kit(CUSTOM, displayName, base.icon, base.permission, items, base.arenaCategories, base.rules, base.rewards, base.effects, base.mode);
    }

    /** Whether this is a player's custom kit, which no registry holds. */
    public boolean isCustom() {
        return CUSTOM.equals(name);
    }

    /**
     * Whether duels with this kit may use {@code arena}: the kit takes any arena, or they share a category; and the
     * arena has the goals or beds the kit's mode needs.
     */
    public boolean accepts(Arena arena) {
        boolean points = switch (mode) {
            case NORMAL -> true;
            case BRIDGE -> arena.points().hasGoals();
            case BED_FIGHT -> arena.points().hasBeds();
        };
        return points && (arenaCategories.isEmpty() || !Collections.disjoint(arenaCategories, arena.categories()));
    }

    @Override
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::clone).toList();
    }

    /** The flag {@code rule} for duels with this kit: the kit's value, else its default. */
    public boolean flag(KitRule rule, Settings settings) {
        return rules.get(rule) instanceof Boolean value ? value : rule.defaultFlag(settings);
    }

    /** The number {@code rule} for duels with this kit: the kit's value, else its default; empty to leave the game as it is. */
    public OptionalInt number(KitRule rule, Settings settings) {
        return rules.get(rule) instanceof Integer value ? OptionalInt.of(value) : rule.defaultNumber(settings);
    }

    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    public boolean canUse(Permissible permissible) {
        return permission == null || permissible.hasPermission(permission);
    }

    /** Replaces {@code player}'s whole inventory with this kit. */
    public void apply(Player player) {
        apply(player, items);
    }

    /** Replaces {@code player}'s whole inventory with {@code layout}: a kit's items, slot by slot. */
    public static void apply(Player player, List<ItemStack> layout) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = slot < layout.size() ? layout.get(slot) : null;
            contents[slot] = item == null ? ItemStack.empty() : item.clone();
        }
        inventory.setContents(contents);
    }

    /** Whether {@code layout} holds exactly this kit's items, in any slots: nothing added, removed or changed. */
    public boolean sameItems(List<ItemStack> layout) {
        return counts(items).equals(counts(layout));
    }

    private static Map<ItemStack, Integer> counts(List<ItemStack> items) {
        Map<ItemStack, Integer> counts = new HashMap<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                counts.merge(item.asOne(), item.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    public Kit withItems(PlayerInventory inventory) {
        return new Kit(name, displayName, icon, permission, Arrays.asList(inventory.getContents()), arenaCategories, rules, rewards, effects, mode);
    }

    public Kit withMode(Mode newMode) {
        return new Kit(name, displayName, icon, permission, items, arenaCategories, rules, rewards, effects, newMode);
    }

    public Kit withIcon(Material newIcon) {
        return new Kit(name, displayName, newIcon, permission, items, arenaCategories, rules, rewards, effects, mode);
    }

    public Kit withDisplayName(String newDisplayName) {
        return new Kit(name, newDisplayName, icon, permission, items, arenaCategories, rules, rewards, effects, mode);
    }

    /** @param newPermission null for everyone */
    public Kit withPermission(String newPermission) {
        return new Kit(name, displayName, icon, newPermission, items, arenaCategories, rules, rewards, effects, mode);
    }

    /** @param newCategories empty for any arena */
    public Kit withArenaCategories(Set<String> newCategories) {
        return new Kit(name, displayName, icon, permission, items, newCategories, rules, rewards, effects, mode);
    }

    public Kit withRewards(Rewards newRewards) {
        return new Kit(name, displayName, icon, permission, items, arenaCategories, rules, newRewards, effects, mode);
    }

    /**
     * @param value fitting {@code rule}, or null for its default
     * @throws IllegalArgumentException if {@code value} does not fit {@code rule}
     */
    public Kit withRule(KitRule rule, Object value) {
        Map<KitRule, Object> changed = new EnumMap<>(KitRule.class);
        changed.putAll(rules);
        if (value == null) {
            changed.remove(rule);
        } else {
            changed.put(rule, value);
        }
        return new Kit(name, displayName, icon, permission, items, arenaCategories, changed, rewards, effects, mode);
    }

    /**
     * Gives {@code type} at {@code amplifier} for {@code seconds}, replacing the kit's effect of that type.
     *
     * @param seconds 0 for the whole fight
     * @throws IllegalArgumentException if {@code amplifier} is not 0 to {@value #MAX_AMPLIFIER} or {@code seconds} not 0
     *                                  to {@value #MAX_EFFECT_SECONDS}
     */
    public Kit withEffect(PotionEffectType type, int amplifier, int seconds) {
        List<PotionEffect> changed = new ArrayList<>(withoutEffect(type).effects);
        changed.add(effect(type, amplifier, seconds));
        return new Kit(name, displayName, icon, permission, items, arenaCategories, rules, rewards, changed, mode);
    }

    public Kit withoutEffect(PotionEffectType type) {
        List<PotionEffect> changed = effects.stream().filter(effect -> !effect.getType().equals(type)).toList();
        return new Kit(name, displayName, icon, permission, items, arenaCategories, rules, rewards, changed, mode);
    }

    /** The kit's effect of {@code type}, if it gives one. */
    public Optional<PotionEffect> effectOf(PotionEffectType type) {
        return effects.stream().filter(effect -> effect.getType().equals(type)).findFirst();
    }

    /** The effect called {@code name}, e.g. {@code speed} or {@code minecraft:jump_boost}. */
    public static Optional<PotionEffectType> effectType(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.strip().toLowerCase(Locale.ROOT));
        return key == null ? Optional.empty() : Optional.ofNullable(Registry.MOB_EFFECT.get(key));
    }

    /**
     * An effect as kits give it, without particles.
     *
     * @param seconds 0 for the whole fight
     * @throws IllegalArgumentException if {@code amplifier} or {@code seconds} is out of range
     */
    public static PotionEffect effect(PotionEffectType type, int amplifier, int seconds) {
        if (amplifier < 0 || amplifier > MAX_AMPLIFIER || seconds < 0 || seconds > MAX_EFFECT_SECONDS) {
            throw new IllegalArgumentException("Effect amplifier must be 0 to " + MAX_AMPLIFIER + " and seconds 0 to "
                    + MAX_EFFECT_SECONDS + ", got " + amplifier + " and " + seconds);
        }
        return new PotionEffect(type, seconds == 0 ? PotionEffect.INFINITE_DURATION : seconds * TICKS_PER_SECOND, amplifier,
                false, false, true);
    }

    /** Gives the effects that last some seconds, when the fight starts, so the countdown does not use them up. */
    public void applyTimedEffects(Player player) {
        player.addPotionEffects(effects.stream().filter(effect -> !effect.isInfinite()).toList());
    }

    /** How long a kit's {@code effect} lasts in seconds; 0 for the whole fight. */
    public static int seconds(PotionEffect effect) {
        return effect.isInfinite() ? 0 : effect.getDuration() / TICKS_PER_SECOND;
    }

    /** The name a kit's effect is stored and typed by, e.g. {@code speed}; the namespace only when not minecraft. */
    public static String effectName(PotionEffectType type) {
        return type.getKey().asMinimalString();
    }

    /**
     * Readies a fighter after {@link #apply}: this kit's whole-fight effects, its {@link KitRule#MAX_HEALTH} at full health and the
     * {@link KitRule#SATURATION} effect. {@link PlayerSnapshot} undoes the maximum health when the fight ends.
     */
    public void applyStatus(Player player, Settings settings) {
        OptionalInt maxHealth = number(KitRule.MAX_HEALTH, settings);
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth.isPresent() && maxHealth.getAsInt() > 0 && health != null) {
            health.removeModifier(PlayerSnapshot.KIT_MAX_HEALTH);
            health.addModifier(new AttributeModifier(PlayerSnapshot.KIT_MAX_HEALTH, maxHealth.getAsInt() - health.getBaseValue(),
                    AttributeModifier.Operation.ADD_NUMBER));
            player.setHealth(health.getValue());
        }
        player.addPotionEffects(effects.stream().filter(PotionEffect::isInfinite).toList());
        if (flag(KitRule.SATURATION, settings)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
    }
}
