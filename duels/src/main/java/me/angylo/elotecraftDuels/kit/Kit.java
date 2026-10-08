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
 * @param build       whether fighters may place blocks, and break the ones placed during the duel
 * @param arenaCategories arena categories duels with this kit may use; empty for any arena
 * @param damage      false for knockback-only fights such as Sumo: hits never hurt, falling off the arena loses
 * @param rules       the game rules this kit sets, each value fitting its {@link KitRule}; unset ones use their default
 * @param rewards     paid for a won duel with this kit on top of config.yml's {@code rewards}
 * @param effects     potion effects fighters have for the whole fight, without particles
 */
public record Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items, boolean build,
                  Set<String> arenaCategories, boolean damage, Map<KitRule, Object> rules, Rewards rewards,
                  List<PotionEffect> effects) {

    public static final Material DEFAULT_ICON = Material.IRON_SWORD;
    /** The name of every player's custom kit ({@link CustomKits}); admin kits cannot use it. */
    public static final String CUSTOM = "custom";
    public static final int MAX_EFFECT_LEVEL = 10;

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
    }

    /** A kit without effects. */
    public Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items, boolean build,
               Set<String> arenaCategories, boolean damage, Map<KitRule, Object> rules, Rewards rewards) {
        this(name, displayName, icon, permission, items, build, arenaCategories, damage, rules, rewards, List.of());
    }

    /** A kit with every game rule at its default and no rewards of its own. */
    public Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items, boolean build,
               Set<String> arenaCategories, boolean damage) {
        this(name, displayName, icon, permission, items, build, arenaCategories, damage, Map.of(), Rewards.NONE);
    }

    /** A kit holding a copy of everything in {@code inventory}, armor and off hand included. */
    static Kit of(String name, Material icon, PlayerInventory inventory) {
        return new Kit(name, name, icon, null, Arrays.asList(inventory.getContents()), false, Set.of(), true);
    }

    /**
     * A player's custom kit: {@code items} with everything else (rules, arenas, permission, rewards, effects) from
     * {@code base}, the kit whose items they were picked from.
     */
    static Kit custom(Kit base, String displayName, List<ItemStack> items) {
        return new Kit(CUSTOM, displayName, base.icon, base.permission, items, base.build, base.arenaCategories, base.damage,
                base.rules, base.rewards, base.effects);
    }

    /** Whether this is a player's custom kit, which no registry holds. */
    public boolean isCustom() {
        return CUSTOM.equals(name);
    }

    /** Whether duels with this kit may use {@code arena}: the kit takes any arena, or they share a category. */
    public boolean accepts(Arena arena) {
        return arenaCategories.isEmpty() || !Collections.disjoint(arenaCategories, arena.categories());
    }

    @Override
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::clone).toList();
    }

    /** The flag {@code rule} for duels with this kit: the kit's value, else its default. */
    public boolean flag(KitRule rule, Settings settings) {
        return rules.get(rule) instanceof Boolean value ? value : rule.defaultFlag(settings);
    }

    /** The number {@code rule} is set to, or empty to leave the game as it is. */
    public OptionalInt number(KitRule rule) {
        return rules.get(rule) instanceof Integer value ? OptionalInt.of(value) : OptionalInt.empty();
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
        return new Kit(name, displayName, icon, permission, Arrays.asList(inventory.getContents()), build, arenaCategories, damage, rules, rewards, effects);
    }

    public Kit withIcon(Material newIcon) {
        return new Kit(name, displayName, newIcon, permission, items, build, arenaCategories, damage, rules, rewards, effects);
    }

    public Kit withDisplayName(String newDisplayName) {
        return new Kit(name, newDisplayName, icon, permission, items, build, arenaCategories, damage, rules, rewards, effects);
    }

    /** @param newPermission null for everyone */
    public Kit withPermission(String newPermission) {
        return new Kit(name, displayName, icon, newPermission, items, build, arenaCategories, damage, rules, rewards, effects);
    }

    public Kit withBuild(boolean newBuild) {
        return new Kit(name, displayName, icon, permission, items, newBuild, arenaCategories, damage, rules, rewards, effects);
    }

    public Kit withDamage(boolean newDamage) {
        return new Kit(name, displayName, icon, permission, items, build, arenaCategories, newDamage, rules, rewards, effects);
    }

    /** @param newCategories empty for any arena */
    public Kit withArenaCategories(Set<String> newCategories) {
        return new Kit(name, displayName, icon, permission, items, build, newCategories, damage, rules, rewards, effects);
    }

    public Kit withRewards(Rewards newRewards) {
        return new Kit(name, displayName, icon, permission, items, build, arenaCategories, damage, rules, newRewards, effects);
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
        return new Kit(name, displayName, icon, permission, items, build, arenaCategories, damage, changed, rewards, effects);
    }

    /**
     * @param level 1 and up; 0 removes {@code type}
     * @throws IllegalArgumentException if {@code level} is below 0 or above {@value #MAX_EFFECT_LEVEL}
     */
    public Kit withEffect(PotionEffectType type, int level) {
        if (level < 0 || level > MAX_EFFECT_LEVEL) {
            throw new IllegalArgumentException("Effect level must be 0 to " + MAX_EFFECT_LEVEL + ", got " + level);
        }
        List<PotionEffect> changed = new ArrayList<>(effects.stream().filter(effect -> !effect.getType().equals(type)).toList());
        if (level > 0) {
            changed.add(effect(type, level));
        }
        return new Kit(name, displayName, icon, permission, items, build, arenaCategories, damage, rules, rewards, changed);
    }

    /** The effect called {@code name}, e.g. {@code speed} or {@code minecraft:jump_boost}. */
    public static Optional<PotionEffectType> effectType(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.strip().toLowerCase(Locale.ROOT));
        return key == null ? Optional.empty() : Optional.ofNullable(Registry.MOB_EFFECT.get(key));
    }

    /** An endless effect of {@code type} at {@code level} (1 and up), as kits give them. */
    public static PotionEffect effect(PotionEffectType type, int level) {
        return new PotionEffect(type, PotionEffect.INFINITE_DURATION, level - 1, false, false, true);
    }

    /**
     * Readies a fighter after {@link #apply}: this kit's effects, its {@link KitRule#MAX_HEALTH} at full health and the
     * {@link KitRule#SATURATION} effect. {@link PlayerSnapshot} undoes the maximum health when the fight ends.
     */
    public void applyStatus(Player player, Settings settings) {
        OptionalInt maxHealth = number(KitRule.MAX_HEALTH);
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth.isPresent() && maxHealth.getAsInt() > 0 && health != null) {
            health.removeModifier(PlayerSnapshot.KIT_MAX_HEALTH);
            health.addModifier(new AttributeModifier(PlayerSnapshot.KIT_MAX_HEALTH, maxHealth.getAsInt() - health.getBaseValue(),
                    AttributeModifier.Operation.ADD_NUMBER));
            player.setHealth(health.getValue());
        }
        player.addPotionEffects(effects);
        if (flag(KitRule.SATURATION, settings)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
    }
}
