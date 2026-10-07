package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.Permissible;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
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
 */
public record Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items, boolean build,
                  Set<String> arenaCategories, boolean damage, Map<KitRule, Object> rules) {

    public static final Material DEFAULT_ICON = Material.IRON_SWORD;

    public Kit {
        items = items.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList();
        arenaCategories = Set.copyOf(arenaCategories);
        rules.forEach((rule, value) -> {
            if (!rule.accepts(value)) {
                throw new IllegalArgumentException("Invalid value for kit rule " + rule.key() + ": " + value);
            }
        });
        rules = Map.copyOf(rules);
    }

    /** A kit with every game rule at its default. */
    public Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items, boolean build,
               Set<String> arenaCategories, boolean damage) {
        this(name, displayName, icon, permission, items, build, arenaCategories, damage, Map.of());
    }

    /** A kit holding a copy of everything in {@code inventory}, armor and off hand included. */
    static Kit of(String name, Material icon, PlayerInventory inventory) {
        return new Kit(name, name, icon, null, Arrays.asList(inventory.getContents()), false, Set.of(), true);
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

    /** The seconds {@code rule} is set to, or empty to leave vanilla alone. */
    public OptionalInt seconds(KitRule rule) {
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
        return new Kit(name, displayName, icon, permission, Arrays.asList(inventory.getContents()), build, arenaCategories, damage, rules);
    }

    public Kit withIcon(Material newIcon) {
        return new Kit(name, displayName, newIcon, permission, items, build, arenaCategories, damage, rules);
    }

    public Kit withDisplayName(String newDisplayName) {
        return new Kit(name, newDisplayName, icon, permission, items, build, arenaCategories, damage, rules);
    }

    /** @param newPermission null for everyone */
    public Kit withPermission(String newPermission) {
        return new Kit(name, displayName, icon, newPermission, items, build, arenaCategories, damage, rules);
    }

    public Kit withBuild(boolean newBuild) {
        return new Kit(name, displayName, icon, permission, items, newBuild, arenaCategories, damage, rules);
    }

    public Kit withDamage(boolean newDamage) {
        return new Kit(name, displayName, icon, permission, items, build, arenaCategories, newDamage, rules);
    }

    /** @param newCategories empty for any arena */
    public Kit withArenaCategories(Set<String> newCategories) {
        return new Kit(name, displayName, icon, permission, items, build, newCategories, damage, rules);
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
        return new Kit(name, displayName, icon, permission, items, build, arenaCategories, damage, changed);
    }
}
