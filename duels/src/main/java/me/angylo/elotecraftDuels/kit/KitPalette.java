package me.angylo.elotecraftDuels.kit;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The items the kit editor offers, from menus.yml {@code kit-editor-armor} and {@code kit-editor-categories}, and so the
 * only items a custom kit may hold ({@link #allows}). Read again whenever menus.yml is. Main thread only.
 */
public final class KitPalette {

    /** The armor slots of the editor, helmet first, and their menus.yml keys. */
    public static final List<EquipmentSlot> ARMOR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final List<String> ARMOR_KEYS = List.of("helmet", "chestplate", "leggings", "boots");
    /** Potions with no effect: left out of the potions and tipped arrows. */
    private static final Set<PotionType> PLAIN = Set.of(PotionType.WATER, PotionType.MUNDANE, PotionType.THICK, PotionType.AWKWARD);
    private static final Registry<Enchantment> ENCHANTMENTS = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
    private static final Set<Enchantment> CURSES_AND_MENDING = Set.of(Enchantment.MENDING, Enchantment.VANISHING_CURSE, Enchantment.BINDING_CURSE);

    /** How the potions of a category are shown: drinkable, splash or lingering. */
    public enum PotionForm {
        POTION(Material.POTION), SPLASH(Material.SPLASH_POTION), LINGERING(Material.LINGERING_POTION);

        private final Material material;

        PotionForm(Material material) {
            this.material = material;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** An item shown in the bottom row of a category's pages, at {@code slot}. */
    public record Fixed(int slot, ItemStack item) {
    }

    /**
     * A category of the item selector.
     *
     * @param items  its items, without the potions
     * @param potions whether it shows every potion in a {@link PotionForm}
     */
    public record Category(String key, ConfigurationSection section, List<ItemStack> items, boolean potions, List<Fixed> bottom) {
    }

    private record Data(Map<EquipmentSlot, List<ItemStack>> armor, List<Category> categories, List<ItemStack> allowed) {
    }

    private final Logger logger;
    private final Supplier<? extends ConfigurationSection> menus;
    private ConfigurationSection readFrom;
    private Data data;

    public KitPalette(Logger logger, Supplier<? extends ConfigurationSection> menus) {
        this.logger = logger;
        this.menus = menus;
    }

    /** The pieces of armor offered for {@code slot} (one of {@link #ARMOR}). */
    public List<ItemStack> armor(EquipmentSlot slot) {
        return copies(data().armor.getOrDefault(slot, List.of()));
    }

    public List<Category> categories() {
        return data().categories;
    }

    /** The items {@code category} shows, its potions in {@code form}. */
    public List<ItemStack> items(Category category, PotionForm form) {
        List<ItemStack> items = new ArrayList<>(copies(category.items()));
        if (category.potions()) {
            items.addAll(potions(form.material));
        }
        return items;
    }

    /**
     * Whether {@code item} may be in a custom kit: an offered item, any amount up to a stack, with enchantments it can
     * get in survival (or exactly those the offered item has), any damage and unbreakable or not.
     */
    public boolean allows(ItemStack item) {
        if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize() || !fairDamage(item)) {
            return false;
        }
        ItemStack plain = plain(item);
        Map<Enchantment, Integer> enchantments = item.getEnchantments();
        boolean fairEnchantments = enchantable(plain) && fairEnchantments(plain, enchantments);
        return data().allowed.stream().anyMatch(offered -> plain(offered).isSimilar(plain)
                && (fairEnchantments || offered.getEnchantments().equals(enchantments)));
    }

    /** Whether {@code item} can be enchanted in survival: a weapon, tool, piece of armor and the like. */
    public static boolean enchantable(ItemStack item) {
        return item.getType() != Material.BOOK && item.getType() != Material.ENCHANTED_BOOK
                && ENCHANTMENTS.stream().anyMatch(enchantment -> enchantment.canEnchantItem(item));
    }

    /** The enchantments {@code item} can get in survival, without mending and the curses; see {@link #extras}. */
    public static List<Enchantment> enchantments(ItemStack item) {
        return ENCHANTMENTS.stream()
                .filter(enchantment -> !CURSES_AND_MENDING.contains(enchantment) && enchantment.canEnchantItem(item)).toList();
    }

    /** Mending and the curses, those {@code item} can get. */
    public static List<Enchantment> extras(ItemStack item) {
        return List.of(Enchantment.MENDING, Enchantment.VANISHING_CURSE, Enchantment.BINDING_CURSE).stream()
                .filter(enchantment -> enchantment.canEnchantItem(item)).toList();
    }

    private static boolean fairEnchantments(ItemStack item, Map<Enchantment, Integer> enchantments) {
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            if (!enchantment.canEnchantItem(item) || entry.getValue() < 1 || entry.getValue() > enchantment.getMaxLevel()
                    || enchantments.keySet().stream().anyMatch(other -> !other.equals(enchantment) && other.conflictsWith(enchantment))) {
                return false;
            }
        }
        return true;
    }

    /** How much durability {@code item} has when new; 0 if it never wears out. */
    public static int maxDurability(ItemStack item) {
        return item.getItemMeta() instanceof Damageable damageable && damageable.hasMaxDamage() ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
    }

    private static boolean fairDamage(ItemStack item) {
        if (!(item.getItemMeta() instanceof Damageable damageable) || !damageable.hasDamageValue()) {
            return true;
        }
        return damageable.getDamage() >= 0 && damageable.getDamage() < maxDurability(item);
    }

    /** {@code item} as one, without what the editor lets players change: enchantments, damage and unbreakable. */
    private static ItemStack plain(ItemStack item) {
        ItemStack plain = item.asOne();
        plain.removeEnchantments();
        plain.editMeta(meta -> {
            meta.setUnbreakable(false);
            if (meta instanceof Damageable damageable) {
                damageable.resetDamage();
            }
        });
        return plain;
    }

    private Data data() {
        ConfigurationSection root = menus.get();
        if (data == null || root != readFrom) {
            readFrom = root;
            data = read(root);
        }
        return data;
    }

    private Data read(ConfigurationSection root) {
        List<String> problems = new ArrayList<>();
        List<ItemStack> allowed = new ArrayList<>();
        Map<EquipmentSlot, List<ItemStack>> armor = new EnumMap<>(EquipmentSlot.class);
        ConfigurationSection armorSection = root.getConfigurationSection("kit-editor-armor");
        for (int i = 0; i < ARMOR.size(); i++) {
            List<ItemStack> pieces = armorSection == null ? List.of() : items(armorSection.getList(ARMOR_KEYS.get(i), List.of()),
                    "kit-editor-armor." + ARMOR_KEYS.get(i), problems);
            armor.put(ARMOR.get(i), pieces);
            allowed.addAll(pieces);
        }
        List<Category> categories = new ArrayList<>();
        ConfigurationSection section = root.getConfigurationSection("kit-editor-categories.categories");
        for (String key : section == null ? Set.<String>of() : section.getKeys(false)) {
            ConfigurationSection category = section.getConfigurationSection(key);
            if (category == null) {
                continue;
            }
            String path = category.getCurrentPath();
            List<ItemStack> items = new ArrayList<>(items(category.getList("items", List.of()), path + ".items", problems));
            if (category.getBoolean("tipped-arrows")) {
                items.addAll(potions(Material.TIPPED_ARROW));
            }
            List<Fixed> bottom = new ArrayList<>();
            for (Object entry : category.getList("bottom", List.of())) {
                int slot = entry instanceof Map<?, ?> map && map.get("slot") instanceof Integer number ? number : -1;
                ItemStack item = item(entry, path + ".bottom", problems);
                if (slot < 46 || slot > 52) {
                    problems.add(path + ".bottom: slot must be 46 to 52");
                } else if (item != null) {
                    bottom.add(new Fixed(slot, item));
                }
            }
            boolean potions = category.getBoolean("potions");
            categories.add(new Category(key, category, List.copyOf(items), potions, List.copyOf(bottom)));
            allowed.addAll(items);
            bottom.forEach(fixed -> allowed.add(fixed.item()));
            if (potions) {
                for (PotionForm form : PotionForm.values()) {
                    allowed.addAll(potions(form.material));
                }
            }
        }
        problems.forEach(problem -> logger.warning("menus.yml: skipping a kit editor item: " + problem));
        return new Data(armor, List.copyOf(categories), List.copyOf(allowed));
    }

    private static List<ItemStack> items(List<?> entries, String path, List<String> problems) {
        List<ItemStack> items = new ArrayList<>();
        for (Object entry : entries) {
            ItemStack item = item(entry, path, problems);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** A {@code MATERIAL}, or a map with {@code material}, {@code name}, {@code enchants} and {@code entity}; null if invalid. */
    private static ItemStack item(Object entry, String path, List<String> problems) {
        Map<?, ?> map = entry instanceof Map<?, ?> found ? found : Map.of("material", String.valueOf(entry));
        Material material = Material.matchMaterial(String.valueOf(map.get("material")));
        if (material == null || !material.isItem() || material.isAir()) {
            problems.add(path + ": '" + map.get("material") + "' is not an item");
            return null;
        }
        ItemStack item = ItemStack.of(material);
        if (map.get("name") instanceof String name) {
            item.editMeta(meta -> meta.itemName(Text.mm(name)));
        }
        if (map.get("enchants") instanceof Map<?, ?> enchants) {
            for (Map.Entry<?, ?> enchant : enchants.entrySet()) {
                NamespacedKey key = NamespacedKey.fromString(String.valueOf(enchant.getKey()).toLowerCase(Locale.ROOT));
                Enchantment enchantment = key == null ? null : ENCHANTMENTS.get(key);
                if (enchantment == null || !(enchant.getValue() instanceof Integer level) || level < 1 || level > Short.MAX_VALUE) {
                    problems.add(path + ": bad enchant " + enchant.getKey() + ": " + enchant.getValue());
                    return null;
                }
                item.addUnsafeEnchantment(enchantment, level);
            }
        }
        if (map.get("entity") instanceof String entity) {
            try {
                SpawnEggs.spawning(item, entity);
            } catch (RuntimeException e) {
                problems.add(path + ": " + material + " entity " + entity + ": " + e.getMessage());
                return null;
            }
        }
        return item;
    }

    /** A {@code material} (potion or tipped arrow) of every potion with an effect. */
    private static List<ItemStack> potions(Material material) {
        return Registry.POTION.stream().filter(type -> !PLAIN.contains(type)).map(type -> {
            ItemStack item = ItemStack.of(material);
            item.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(type));
            return item;
        }).toList();
    }

    private static List<ItemStack> copies(List<ItemStack> items) {
        return items.stream().map(ItemStack::clone).toList();
    }
}
