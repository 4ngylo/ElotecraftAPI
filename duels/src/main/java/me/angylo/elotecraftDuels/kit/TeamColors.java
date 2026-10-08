package me.angylo.elotecraftDuels.kit;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/**
 * Side colors for bridge and bed fight: side 1 (team 0) red, side 2 (team 1) blue. Leather armor is dyed,
 * wool and terracotta (not glazed) become the side's color; everything else stays as the kit has it.
 */
public final class TeamColors {

    private static final Color[] ARMOR = {Color.RED, Color.BLUE};
    private static final Material[] WOOL = {Material.RED_WOOL, Material.BLUE_WOOL};
    private static final Material[] TERRACOTTA = {Material.RED_TERRACOTTA, Material.BLUE_TERRACOTTA};

    private TeamColors() {
    }

    /** Colors the items in {@code inventory} for {@code team} (0 or 1); other teams are left alone. */
    public static void apply(PlayerInventory inventory, int team) {
        if (team < 0 || team >= ARMOR.length) {
            return;
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.isEmpty()) {
                contents[slot] = colored(item, team);
            }
        }
        inventory.setContents(contents);
    }

    private static ItemStack colored(ItemStack item, int team) {
        Material type = item.getType();
        if (Tag.WOOL.isTagged(type)) {
            return item.withType(WOOL[team]);
        }
        // The terracotta tag holds plain and dyed terracotta, not glazed.
        if (Tag.TERRACOTTA.isTagged(type)) {
            return item.withType(TERRACOTTA[team]);
        }
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(ARMOR[team]);
            ItemStack dyed = item.clone();
            dyed.setItemMeta(meta);
            return dyed;
        }
        return item;
    }
}
