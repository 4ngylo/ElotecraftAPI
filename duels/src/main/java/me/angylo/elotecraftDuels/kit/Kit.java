package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.arena.Arena;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.Permissible;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Items given to both fighters: a full player inventory (storage, armor and off hand). Immutable:
 * items are copied in and out, and the {@code with...} methods return a changed copy.
 *
 * @param displayName MiniMessage, set by admins
 * @param permission  needed to pick the kit, or null for everyone
 * @param build       whether fighters may place blocks, and break the ones placed during the duel
 * @param arenaCategories arena categories duels with this kit may use; empty for any arena
 */
public record Kit(String name, String displayName, Material icon, String permission, List<ItemStack> items, boolean build,
                  Set<String> arenaCategories) {

    public static final Material DEFAULT_ICON = Material.IRON_SWORD;

    public Kit {
        items = items.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList();
        arenaCategories = Set.copyOf(arenaCategories);
    }

    /** A kit holding a copy of everything in {@code inventory}, armor and off hand included. */
    static Kit of(String name, Material icon, PlayerInventory inventory) {
        return new Kit(name, name, icon, null, Arrays.asList(inventory.getContents()), false, Set.of());
    }

    /** Whether duels with this kit may use {@code arena}: the kit takes any arena, or they share a category. */
    public boolean accepts(Arena arena) {
        return arenaCategories.isEmpty() || !Collections.disjoint(arenaCategories, arena.categories());
    }

    @Override
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::clone).toList();
    }

    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    public boolean canUse(Permissible permissible) {
        return permission == null || permissible.hasPermission(permission);
    }

    /** Replaces {@code player}'s whole inventory with this kit. */
    public void apply(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = slot < items.size() ? items.get(slot).clone() : ItemStack.empty();
        }
        inventory.setContents(contents);
    }

    public Kit withItems(PlayerInventory inventory) {
        return new Kit(name, displayName, icon, permission, Arrays.asList(inventory.getContents()), build, arenaCategories);
    }

    public Kit withIcon(Material newIcon) {
        return new Kit(name, displayName, newIcon, permission, items, build, arenaCategories);
    }

    public Kit withDisplayName(String newDisplayName) {
        return new Kit(name, newDisplayName, icon, permission, items, build, arenaCategories);
    }

    /** @param newPermission null for everyone */
    public Kit withPermission(String newPermission) {
        return new Kit(name, displayName, icon, newPermission, items, build, arenaCategories);
    }

    public Kit withBuild(boolean newBuild) {
        return new Kit(name, displayName, icon, permission, items, newBuild, arenaCategories);
    }

    /** @param newCategories empty for any arena */
    public Kit withArenaCategories(Set<String> newCategories) {
        return new Kit(name, displayName, icon, permission, items, build, newCategories);
    }
}
