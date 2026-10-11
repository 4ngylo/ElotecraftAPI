package me.angylo.elotecraftDuels;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ShieldMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The cosmetics of config.yml {@code cosmetics}, which players pick in {@code /duel cosmetics}: kill effects and kill
 * messages (shown when they knock someone out), death messages (when they are knocked out without a kill message),
 * win sounds, and armor trims and shield patterns put on their kit. A player's pick is kept in their player data, like
 * {@link PlayerOptions}; a pick that is no longer configured, or whose permission they lost, counts as none.
 *
 * @param entries     the picks of each kind, in config order
 * @param killEffects the sounds and particles of the kill effects, by id
 * @param winSounds   the sounds of the win sounds, by id
 */
public record Cosmetics(Map<Kind, List<Cosmetic>> entries, Effects killEffects, Effects winSounds) {

    /** What players pick from; each is kept under its own key. */
    public enum Kind {
        KILL_EFFECT, KILL_MESSAGE, DEATH_MESSAGE, WIN_SOUND, ARMOR_TRIM, SHIELD_PATTERN;

        private final NamespacedKey chosen = Objects.requireNonNull(NamespacedKey.fromString("elotecraftduels:" + key()));

        /** The menus.yml section and the {@code /duel cosmetics} argument, e.g. {@code kill-effect}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** The config.yml {@code cosmetics} list, e.g. {@code kill-effects}. */
        String section() {
            return key() + "s";
        }

        /** For messages: the messages.yml section of their texts, e.g. {@code kill-messages}; else null. */
        public String texts() {
            return this == KILL_MESSAGE || this == DEATH_MESSAGE ? section() : null;
        }
    }

    /**
     * One pick: shown with {@code icon} and {@code name} (MiniMessage) in the menu, and only for players with
     * {@code permission}, if set. A message's text is messages.yml {@code <kind texts>.<id>}.
     *
     * @param lightning a kill effect's harmless lightning strike
     * @param trim      an armor trim's pattern and material, else null
     * @param shield    a shield pattern's base color and layers, else null
     */
    public record Cosmetic(String id, Material icon, String name, String permission, boolean lightning, ArmorTrim trim,
                           ShieldDesign shield) {

        public boolean allowed(Player player) {
            return permission == null || player.hasPermission(permission);
        }
    }

    /** A shield's base color and banner layers, bottom first. */
    public record ShieldDesign(DyeColor base, List<Pattern> patterns) {
    }

    static Cosmetics load(ConfigurationSection config, Logger logger) {
        Map<Kind, List<Cosmetic>> entries = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            entries.put(kind, entries(kind, config.getConfigurationSection("cosmetics." + kind.section()), logger));
        }
        return new Cosmetics(entries, Effects.load(config.getConfigurationSection("cosmetics.kill-effects"), logger),
                Effects.load(config.getConfigurationSection("cosmetics.win-sounds"), logger));
    }

    public List<Cosmetic> all(Kind kind) {
        return entries.getOrDefault(kind, List.of());
    }

    /** What {@code player} picked, if it is still there and allowed. */
    public Optional<Cosmetic> chosen(Player player, Kind kind) {
        String id = player.getPersistentDataContainer().get(kind.chosen, PersistentDataType.STRING);
        return all(kind).stream().filter(cosmetic -> cosmetic.id().equals(id) && cosmetic.allowed(player)).findFirst();
    }

    /** @param cosmetic null for none */
    public static void choose(Player player, Kind kind, Cosmetic cosmetic) {
        if (cosmetic == null) {
            player.getPersistentDataContainer().remove(kind.chosen);
        } else {
            player.getPersistentDataContainer().set(kind.chosen, PersistentDataType.STRING, cosmetic.id());
        }
    }

    /** {@code killer}'s kill effect, if any, where {@code victim} is, heard by {@code listeners}. */
    public void playKillEffect(Player killer, Player victim, Collection<Player> listeners) {
        chosen(killer, Kind.KILL_EFFECT).ifPresent(effect -> {
            Location location = victim.getLocation();
            if (effect.lightning()) {
                location.getWorld().strikeLightningEffect(location);
            }
            killEffects.playAt(location, listeners, effect.id());
        });
    }

    /** {@code winner}'s win sound, if any, heard by {@code listeners}. */
    public void playWinSound(Player winner, Collection<Player> listeners) {
        chosen(winner, Kind.WIN_SOUND).ifPresent(sound -> winSounds.playAt(winner.getLocation(), listeners, sound.id()));
    }

    /** Puts {@code player}'s armor trim on their trimmable armor and their shield pattern on their shields. */
    public void dress(Player player) {
        Optional<ArmorTrim> trim = chosen(player, Kind.ARMOR_TRIM).map(Cosmetic::trim);
        Optional<ShieldDesign> shield = chosen(player, Kind.SHIELD_PATTERN).map(Cosmetic::shield);
        if (trim.isEmpty() && shield.isEmpty()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (trim.isPresent() && Tag.ITEMS_TRIMMABLE_ARMOR.isTagged(item.getType())) {
                item.editMeta(ArmorMeta.class, meta -> meta.setTrim(trim.get()));
            } else if (shield.isPresent() && item.getType() == Material.SHIELD) {
                item.editMeta(ShieldMeta.class, meta -> {
                    meta.setBaseColor(shield.get().base());
                    meta.setPatterns(shield.get().patterns());
                });
            } else {
                continue;
            }
            inventory.setItem(slot, item);
        }
    }

    private static List<Cosmetic> entries(Kind kind, ConfigurationSection section, Logger logger) {
        List<Cosmetic> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            String path = "config.yml " + section.getCurrentPath() + "." + id;
            Material icon = entry == null ? null : Material.matchMaterial(entry.getString("icon", ""));
            if (icon == null || !icon.isItem() || icon.isAir()) {
                logger.warning(path + " needs an item as icon; ignoring it");
                continue;
            }
            try {
                ArmorTrim trim = kind == Kind.ARMOR_TRIM ? new ArmorTrim(
                        lookup(RegistryKey.TRIM_MATERIAL, entry.getString("material", ""), TrimMaterial.class),
                        lookup(RegistryKey.TRIM_PATTERN, entry.getString("pattern", ""), TrimPattern.class)) : null;
                ShieldDesign shield = kind == Kind.SHIELD_PATTERN ? shield(entry) : null;
                String permission = entry.getString("permission", "");
                entries.add(new Cosmetic(id, icon, entry.getString("name", id), permission.isBlank() ? null : permission,
                        entry.getBoolean("lightning", false), trim, shield));
            } catch (IllegalArgumentException e) {
                logger.warning(path + ": " + e.getMessage() + "; ignoring it");
            }
        }
        return List.copyOf(entries);
    }

    /** {@code base: RED} and {@code patterns: ["stripe_top WHITE", ...]}, bottom layer first. */
    private static ShieldDesign shield(ConfigurationSection entry) {
        DyeColor base = color(entry.getString("base", ""));
        List<Pattern> patterns = new ArrayList<>();
        for (String layer : entry.getStringList("patterns")) {
            String[] parts = layer.trim().split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("each pattern is \"<pattern> <COLOR>\", not \"" + layer + "\"");
            }
            patterns.add(new Pattern(color(parts[1]), lookup(RegistryKey.BANNER_PATTERN, parts[0], PatternType.class)));
        }
        return new ShieldDesign(base, List.copyOf(patterns));
    }

    private static DyeColor color(String name) {
        try {
            return DyeColor.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown dye color \"" + name + "\"");
        }
    }

    private static <T extends Keyed> T lookup(RegistryKey<T> registry, String name, Class<T> type) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        T found = key == null ? null : RegistryAccess.registryAccess().getRegistry(registry).get(key);
        if (found == null) {
            throw new IllegalArgumentException("unknown " + type.getSimpleName() + " \"" + name + "\"");
        }
        return found;
    }
}
