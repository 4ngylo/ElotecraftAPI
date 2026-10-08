package me.angylo.elotecraftDuels.kit;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The 18 kits a fresh install starts with, after the most played practice modes: the classic ones
 * (NoDebuff, Debuff, Gapple, BuildUHC, Classic, Archer, Sumo, Boxing, Combo), the MCTiers ones (Vanilla,
 * UHC, Pot, NethOP, SMP, Sword, Axe, Mace) and Spear. Admins edit them like any kit.
 */
final class DefaultKits {

    /** Player inventory slots: 0-8 hotbar, 9-35 storage, 36-39 boots to helmet, 40 off hand. */
    private static final int SIZE = 41;
    private static final int STORAGE_END = 36;
    private static final int BOOTS = 36;
    private static final int OFF_HAND = 40;
    /** Seconds between ender pearls in the potion kits, as on most practice servers. */
    private static final int PRACTICE_PEARL_COOLDOWN = 15;
    /** Sumo is a best of 3. */
    private static final int SUMO_ROUNDS = 2;
    /** Boxing: the first to land 100 hits wins, as on most practice servers. */
    private static final int BOXING_HITS = 100;

    private DefaultKits() {
    }

    static List<Kit> all() {
        return List.of(
                kit("nodebuff", "<light_purple>NoDebuff", Material.SPLASH_POTION, false, Set.of(), noDebuff(false)).withRule(KitRule.PEARL_COOLDOWN, PRACTICE_PEARL_COOLDOWN),
                kit("debuff", "<dark_green>Debuff", Material.FERMENTED_SPIDER_EYE, false, Set.of(), noDebuff(true)).withRule(KitRule.PEARL_COOLDOWN, PRACTICE_PEARL_COOLDOWN),
                kit("gapple", "<gold>Gapple", Material.ENCHANTED_GOLDEN_APPLE, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 4).slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3))
                        .slot(1, item(Material.GOLDEN_APPLE, 64)).slot(2, potion(Material.POTION, PotionType.STRONG_STRENGTH))
                        .slot(3, potion(Material.POTION, PotionType.STRONG_SWIFTNESS)).slot(4, potion(Material.POTION, PotionType.STRONG_STRENGTH))
                        .slot(5, potion(Material.POTION, PotionType.STRONG_SWIFTNESS)).slot(8, item(Material.COOKED_BEEF, 64))),
                kit("builduhc", "<red>BuildUHC", Material.LAVA_BUCKET, true, Set.of(), new Loadout()
                        .armor("DIAMOND", 2).slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3))
                        .slot(1, ench(Material.BOW, Enchantment.POWER, 3)).slot(2, item(Material.LAVA_BUCKET, 1))
                        .slot(3, item(Material.WATER_BUCKET, 1)).slot(4, item(Material.GOLDEN_APPLE, 6))
                        .slot(5, item(Material.COBBLESTONE, 64)).slot(6, item(Material.OAK_PLANKS, 64))
                        .slot(7, ench(Material.DIAMOND_PICKAXE, Enchantment.EFFICIENCY, 3)).slot(8, item(Material.COOKED_BEEF, 64))
                        .slot(9, item(Material.ARROW, 64)).slot(10, item(Material.LAVA_BUCKET, 1)).slot(11, item(Material.WATER_BUCKET, 1))
                        .slot(12, ench(Material.DIAMOND_AXE, Enchantment.EFFICIENCY, 3))).withRule(KitRule.HUNGER, true)
                        .withRule(KitRule.NATURAL_REGENERATION, false),
                kit("classic", "<aqua>Classic", Material.IRON_SWORD, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 2).slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 2))
                        .slot(1, ench(Material.BOW, Enchantment.POWER, 1)).slot(2, item(Material.GOLDEN_APPLE, 8))
                        .slot(3, item(Material.ENDER_PEARL, 4)).slot(8, item(Material.COOKED_BEEF, 64)).slot(9, item(Material.ARROW, 32))),
                kit("archer", "<green>Archer", Material.BOW, false, Set.of(), new Loadout()
                        .armor("LEATHER", 2).slot(0, ench(Material.BOW, Enchantment.POWER, 5, Enchantment.INFINITY, 1))
                        .slot(1, potion(Material.POTION, PotionType.STRONG_SWIFTNESS)).slot(2, potion(Material.POTION, PotionType.STRONG_SWIFTNESS))
                        .slot(8, item(Material.COOKED_BEEF, 64)).slot(9, item(Material.ARROW, 1))),
                kit("sumo", "<yellow>Sumo", Material.SLIME_BALL, false, Set.of("sumo"), new Loadout()
                        .slot(8, item(Material.COOKED_BEEF, 64))).withDamage(false).withRule(KitRule.ROUNDS_TO_WIN, SUMO_ROUNDS),
                kit("vanilla", "<dark_purple>Vanilla", Material.END_CRYSTAL, true, Set.of(), new Loadout()
                        .armor("NETHERITE", 4).slot(0, ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5))
                        .slot(1, item(Material.END_CRYSTAL, 64)).slot(2, item(Material.OBSIDIAN, 64))
                        .slot(3, item(Material.RESPAWN_ANCHOR, 64)).slot(4, item(Material.GLOWSTONE, 64))
                        .slot(5, item(Material.GOLDEN_APPLE, 64)).slot(6, item(Material.ENDER_PEARL, 16))
                        .slot(7, ench(Material.NETHERITE_PICKAXE, Enchantment.EFFICIENCY, 5)).slot(8, item(Material.EXPERIENCE_BOTTLE, 64))
                        .slot(9, item(Material.TOTEM_OF_UNDYING, 1)).slot(10, item(Material.TOTEM_OF_UNDYING, 1))
                        .slot(11, item(Material.TOTEM_OF_UNDYING, 1)).slot(12, item(Material.END_CRYSTAL, 64))
                        .slot(13, item(Material.OBSIDIAN, 64)).slot(OFF_HAND, item(Material.TOTEM_OF_UNDYING, 1))),
                kit("uhc", "<gold>UHC", Material.WATER_BUCKET, true, Set.of(), new Loadout()
                        .armor("DIAMOND", 3).slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 4))
                        .slot(1, item(Material.DIAMOND_AXE, 1)).slot(2, ench(Material.BOW, Enchantment.POWER, 3))
                        .slot(3, item(Material.CROSSBOW, 1)).slot(4, item(Material.LAVA_BUCKET, 1)).slot(5, item(Material.WATER_BUCKET, 1))
                        .slot(6, item(Material.COBWEB, 16)).slot(7, item(Material.GOLDEN_APPLE, 16)).slot(8, item(Material.COBBLESTONE, 64))
                        .slot(9, item(Material.ARROW, 32)).slot(10, item(Material.LAVA_BUCKET, 1)).slot(11, item(Material.WATER_BUCKET, 1))
                        .slot(12, item(Material.DIAMOND_PICKAXE, 1)).slot(OFF_HAND, item(Material.SHIELD, 1)))
                        .withRule(KitRule.HUNGER, true).withRule(KitRule.NATURAL_REGENERATION, false),
                kit("pot", "<red>Pot", Material.BREWING_STAND, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 4).slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3))
                        .slot(1, item(Material.ENDER_PEARL, 16)).slot(2, potion(Material.POTION, PotionType.STRONG_STRENGTH))
                        .slot(3, potion(Material.POTION, PotionType.STRONG_SWIFTNESS)).slot(4, potion(Material.POTION, PotionType.LONG_FIRE_RESISTANCE))
                        .slot(8, item(Material.COOKED_BEEF, 64)).slot(9, potion(Material.POTION, PotionType.STRONG_STRENGTH))
                        .slot(10, potion(Material.POTION, PotionType.STRONG_SWIFTNESS))
                        .fill(potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING))).withRule(KitRule.PEARL_COOLDOWN, PRACTICE_PEARL_COOLDOWN),
                kit("nethop", "<dark_red>NethOP", Material.NETHERITE_CHESTPLATE, false, Set.of(), new Loadout()
                        .armor("NETHERITE", 4).slot(0, ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2))
                        .slot(1, ench(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5)).slot(2, item(Material.ENCHANTED_GOLDEN_APPLE, 32))
                        .slot(3, potion(Material.POTION, PotionType.STRONG_STRENGTH)).slot(4, potion(Material.POTION, PotionType.STRONG_SWIFTNESS))
                        .slot(5, item(Material.ENDER_PEARL, 16)).slot(6, item(Material.EXPERIENCE_BOTTLE, 64))
                        .slot(7, item(Material.TOTEM_OF_UNDYING, 1)).slot(9, item(Material.TOTEM_OF_UNDYING, 1))
                        .slot(10, item(Material.TOTEM_OF_UNDYING, 1)).slot(11, item(Material.TOTEM_OF_UNDYING, 1))
                        .slot(OFF_HAND, item(Material.TOTEM_OF_UNDYING, 1))),
                kit("smp", "<dark_aqua>SMP", Material.SHIELD, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 3).slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 4))
                        .slot(1, item(Material.DIAMOND_AXE, 1)).slot(2, item(Material.GOLDEN_APPLE, 32)).slot(3, item(Material.ENDER_PEARL, 16))
                        .slot(4, item(Material.COBWEB, 16)).slot(5, item(Material.EXPERIENCE_BOTTLE, 32)).slot(6, item(Material.TOTEM_OF_UNDYING, 1))
                        .slot(8, item(Material.COOKED_BEEF, 64)).slot(9, item(Material.TOTEM_OF_UNDYING, 1))
                        .slot(OFF_HAND, item(Material.SHIELD, 1))),
                kit("sword", "<aqua>Sword", Material.DIAMOND_SWORD, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 0).slot(0, item(Material.DIAMOND_SWORD, 1)).slot(8, item(Material.COOKED_BEEF, 64))),
                kit("axe", "<gray>Axe", Material.DIAMOND_AXE, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 0).slot(0, item(Material.DIAMOND_AXE, 1)).slot(1, item(Material.DIAMOND_SWORD, 1))
                        .slot(2, item(Material.BOW, 1)).slot(3, item(Material.CROSSBOW, 1)).slot(8, item(Material.COOKED_BEEF, 64))
                        .slot(9, item(Material.ARROW, 16)).slot(OFF_HAND, item(Material.SHIELD, 1))),
                kit("mace", "<white>Mace", Material.MACE, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 3).slot(0, ench(Material.MACE, Enchantment.DENSITY, 4, Enchantment.WIND_BURST, 1))
                        .slot(1, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 4)).slot(2, item(Material.WIND_CHARGE, 64))
                        .slot(3, item(Material.GOLDEN_APPLE, 16)).slot(4, item(Material.ENDER_PEARL, 16))
                        .slot(8, item(Material.COOKED_BEEF, 64)).slot(OFF_HAND, item(Material.SHIELD, 1))),
                kit("boxing", "<red>Boxing", Material.LEATHER, false, Set.of(), new Loadout()
                        .slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 1)).slot(8, item(Material.COOKED_BEEF, 64)))
                        .withDamage(false).withRule(KitRule.HITS_TO_WIN, BOXING_HITS),
                kit("combo", "<blue>Combo", Material.PUFFERFISH, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 3).slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3, Enchantment.UNBREAKING, 3))
                        .slot(1, item(Material.ENCHANTED_GOLDEN_APPLE, 64)).slot(2, potion(Material.POTION, PotionType.STRONG_SWIFTNESS))
                        .slot(3, potion(Material.POTION, PotionType.STRONG_STRENGTH)).slot(8, item(Material.COOKED_BEEF, 64)))
                        .withRule(KitRule.HIT_DELAY, false),
                kit("spear", "<dark_aqua>Spear", Material.DIAMOND_SPEAR, false, Set.of(), new Loadout()
                        .armor("DIAMOND", 3).slot(0, ench(Material.DIAMOND_SPEAR, Enchantment.LUNGE, 3))
                        .slot(1, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3)).slot(2, item(Material.GOLDEN_APPLE, 16))
                        .slot(3, item(Material.ENDER_PEARL, 8)).slot(8, item(Material.COOKED_BEEF, 64))
                        .slot(OFF_HAND, item(Material.SHIELD, 1))));
    }

    /** NoDebuff: healing splash potions fill the inventory; Debuff adds poison and slowness to throw. */
    private static Loadout noDebuff(boolean debuff) {
        Loadout loadout = new Loadout().armor("DIAMOND", 2)
                .slot(0, ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3))
                .slot(1, item(Material.ENDER_PEARL, 16)).slot(2, potion(Material.POTION, PotionType.STRONG_SWIFTNESS))
                .slot(3, potion(Material.POTION, PotionType.LONG_FIRE_RESISTANCE)).slot(8, item(Material.COOKED_BEEF, 64))
                .slot(9, potion(Material.POTION, PotionType.STRONG_SWIFTNESS)).slot(10, potion(Material.POTION, PotionType.STRONG_SWIFTNESS));
        if (debuff) {
            loadout.slot(11, potion(Material.SPLASH_POTION, PotionType.POISON)).slot(12, potion(Material.SPLASH_POTION, PotionType.POISON))
                    .slot(13, potion(Material.SPLASH_POTION, PotionType.SLOWNESS)).slot(14, potion(Material.SPLASH_POTION, PotionType.SLOWNESS));
        }
        return loadout.fill(potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING));
    }

    private static Kit kit(String name, String displayName, Material icon, boolean build, Set<String> categories, Loadout loadout) {
        return new Kit(name, displayName, icon, null, Arrays.asList(loadout.slots), build, categories, true);
    }

    private static ItemStack item(Material material, int amount) {
        return ItemStack.of(material, amount);
    }

    /** @param enchantments enchantment, level, enchantment, level... */
    private static ItemStack ench(Material material, Object... enchantments) {
        ItemStack item = ItemStack.of(material);
        ItemMeta meta = item.getItemMeta();
        for (int i = 0; i < enchantments.length; i += 2) {
            meta.addEnchant((Enchantment) enchantments[i], (Integer) enchantments[i + 1], true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack potion(Material material, PotionType type) {
        ItemStack item = ItemStack.of(material);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    /** A full player inventory being filled in. */
    private static final class Loadout {

        private final ItemStack[] slots = new ItemStack[SIZE];

        Loadout slot(int slot, ItemStack item) {
            slots[slot] = item;
            return this;
        }

        /** Boots to helmet of a tier such as DIAMOND, with Protection at {@code protection} (0 for none). */
        Loadout armor(String tier, int protection) {
            String[] pieces = {"_BOOTS", "_LEGGINGS", "_CHESTPLATE", "_HELMET"};
            for (int i = 0; i < pieces.length; i++) {
                Material material = Material.valueOf(tier + pieces[i]);
                slots[BOOTS + i] = protection > 0
                        ? ench(material, Enchantment.PROTECTION, protection, Enchantment.UNBREAKING, 3) : item(material, 1);
            }
            return this;
        }

        /** Puts {@code item} in every empty hotbar and storage slot. */
        Loadout fill(ItemStack item) {
            for (int slot = 0; slot < STORAGE_END; slot++) {
                if (slots[slot] == null) {
                    slots[slot] = item.clone();
                }
            }
            return this;
        }
    }
}
