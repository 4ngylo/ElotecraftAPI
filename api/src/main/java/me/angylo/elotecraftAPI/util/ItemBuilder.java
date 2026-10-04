package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;

/**
 * Fluent {@link ItemStack} builder. Names and lore are non-italic unless the text says otherwise.
 * {@link #build()} returns a copy, so one builder can produce many items.
 */
public final class ItemBuilder {

    private final ItemStack item;

    private ItemBuilder(ItemStack item) {
        this.item = item;
    }

    /**
     * @throws IllegalArgumentException if {@code material} is air or not an item
     */
    public static ItemBuilder of(Material material) {
        if (material.isAir()) {
            throw new IllegalArgumentException("Cannot build an item from " + material);
        }
        return new ItemBuilder(ItemStack.of(material));
    }

    /** Starts from a copy of {@code item}; the original is not changed. */
    public static ItemBuilder from(ItemStack item) {
        return new ItemBuilder(item.clone());
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder name(Component name) {
        item.editMeta(meta -> meta.displayName(noItalic(name)));
        return this;
    }

    /** MiniMessage name, e.g. {@code "<gold>Ruby Sword"}. */
    public ItemBuilder name(String miniMessage) {
        return name(Text.mm(miniMessage));
    }

    public ItemBuilder lore(Component... lines) {
        item.editMeta(meta -> meta.lore(Arrays.stream(lines).map(ItemBuilder::noItalic).toList()));
        return this;
    }

    /** MiniMessage lore, one string per line. */
    public ItemBuilder lore(String... miniMessageLines) {
        return lore(List.of(miniMessageLines));
    }

    /** MiniMessage lore, e.g. from {@code config.getStringList("lore")}. */
    public ItemBuilder lore(List<String> miniMessageLines) {
        return lore(miniMessageLines.stream().map(Text::mm).toArray(Component[]::new));
    }

    /** Adds an enchantment; levels above the vanilla maximum are allowed. */
    public ItemBuilder enchant(Enchantment enchantment, int level) {
        item.editMeta(meta -> meta.addEnchant(enchantment, level, true));
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        item.editMeta(meta -> meta.addItemFlags(flags));
        return this;
    }

    public ItemBuilder unbreakable(boolean unbreakable) {
        item.editMeta(meta -> meta.setUnbreakable(unbreakable));
        return this;
    }

    /** Forces the enchantment glint on or off; {@code null} restores the default. */
    public ItemBuilder glint(Boolean glint) {
        item.editMeta(meta -> meta.setEnchantmentGlintOverride(glint));
        return this;
    }

    /** Resource-pack model, e.g. {@code new NamespacedKey("elotecraft", "ruby_sword")}. */
    public ItemBuilder itemModel(NamespacedKey model) {
        item.editMeta(meta -> meta.setItemModel(model));
        return this;
    }

    public <P, C> ItemBuilder data(NamespacedKey key, PersistentDataType<P, C> type, C value) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key, type, value));
        return this;
    }

    public ItemStack build() {
        return item.clone();
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
