package me.angylo.elotecraftAPI.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Fluent {@link ItemStack} builder. Names and lore are non-italic unless the text says otherwise.
 * Changes are collected on one {@link ItemMeta} and applied once in {@link #build()}, which returns
 * a new stack each time, so one builder can produce many items.
 */
public final class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    private ItemBuilder(ItemStack item) {
        this.item = item;
        this.meta = item.getItemMeta();
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

    /**
     * Starts from a copy of {@code item}; the original is not changed.
     *
     * @throws IllegalArgumentException if {@code item} is air
     */
    public static ItemBuilder from(ItemStack item) {
        if (item.getType().isAir()) {
            throw new IllegalArgumentException("Cannot build an item from " + item.getType());
        }
        return new ItemBuilder(item.clone());
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder name(Component name) {
        meta.displayName(noItalic(name));
        return this;
    }

    /** MiniMessage name, e.g. {@code "<gold>Ruby Sword"}. */
    public ItemBuilder name(String miniMessage) {
        return name(Text.mm(miniMessage));
    }

    public ItemBuilder lore(Component... lines) {
        meta.lore(Arrays.stream(lines).map(ItemBuilder::noItalic).toList());
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
        meta.addEnchant(enchantment, level, true);
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        meta.addItemFlags(flags);
        return this;
    }

    /**
     * Hides what vanilla adds under the name and lore: attributes (a sword's attack damage and speed) and
     * item details (a potion's effects or "No Effects", banner patterns, book authors). For menu icons.
     */
    // ItemFlag.HIDE_ADDITIONAL_TOOLTIP is deprecated for Paper's data component API (TooltipDisplay), which
    // is experimental in 1.21.11; switch to it once it is stable.
    @SuppressWarnings("deprecation")
    public ItemBuilder hideDetails() {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        return this;
    }

    public ItemBuilder unbreakable(boolean unbreakable) {
        meta.setUnbreakable(unbreakable);
        return this;
    }

    /** Forces the enchantment glint on or off; {@code null} restores the default. */
    public ItemBuilder glint(Boolean glint) {
        meta.setEnchantmentGlintOverride(glint);
        return this;
    }

    /** Resource-pack model, e.g. {@code new NamespacedKey("elotecraft", "ruby_sword")}. */
    public ItemBuilder itemModel(NamespacedKey model) {
        meta.setItemModel(model);
        return this;
    }

    /**
     * Player head showing {@code owner}'s skin.
     *
     * @throws IllegalStateException if the item is not a {@link Material#PLAYER_HEAD}
     */
    public ItemBuilder skull(OfflinePlayer owner) {
        skullMeta().setOwningPlayer(owner);
        return this;
    }

    /**
     * Player head with a custom texture: the base64 "Value" from sites like minecraft-heads.com.
     * Heads with the same texture stack, because the profile id is derived from it.
     *
     * @throws IllegalStateException    if the item is not a {@link Material#PLAYER_HEAD}
     * @throws IllegalArgumentException if {@code base64} is blank
     */
    public ItemBuilder skullTexture(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("Skull texture is empty");
        }
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8)));
        profile.setProperty(new ProfileProperty("textures", base64));
        skullMeta().setPlayerProfile(profile);
        return this;
    }

    public <P, C> ItemBuilder data(NamespacedKey key, PersistentDataType<P, C> type, C value) {
        meta.getPersistentDataContainer().set(key, type, value);
        return this;
    }

    public ItemStack build() {
        ItemStack built = item.clone();
        built.setItemMeta(meta);
        return built;
    }

    private SkullMeta skullMeta() {
        if (!(meta instanceof SkullMeta skull)) {
            throw new IllegalStateException("Skulls need a PLAYER_HEAD, not " + item.getType());
        }
        return skull;
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
