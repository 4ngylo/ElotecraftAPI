package me.angylo.elotecraftDuels;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Kill effects and kill messages from config.yml {@code cosmetics}, which players pick in {@code /duel cosmetics}.
 * A player's pick is kept in their player data, like {@link PlayerOptions}; a pick that is no longer configured,
 * or whose permission they lost, counts as none.
 *
 * @param effects the sounds and particles of the kill effects, by id
 */
public record Cosmetics(List<Cosmetic> killEffects, List<Cosmetic> killMessages, Effects effects) {

    /** What players pick from; each is kept under its own key. */
    public enum Kind {
        KILL_EFFECT, KILL_MESSAGE;

        private final NamespacedKey chosen = Objects.requireNonNull(NamespacedKey.fromString("elotecraftduels:" + key()));

        /** The menus.yml section and the {@code /duel cosmetics} argument, e.g. {@code kill-effect}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * One pick: shown with {@code icon} and {@code name} (MiniMessage) in the menu, and only for players with
     * {@code permission}, if set. A kill message's text is messages.yml {@code kill-messages.<id>}.
     *
     * @param lightning a kill effect's harmless lightning strike
     */
    public record Cosmetic(String id, Material icon, String name, String permission, boolean lightning) {

        public boolean allowed(Player player) {
            return permission == null || player.hasPermission(permission);
        }
    }

    static Cosmetics load(ConfigurationSection config, Logger logger) {
        ConfigurationSection effects = config.getConfigurationSection("cosmetics.kill-effects");
        return new Cosmetics(entries(effects, logger), entries(config.getConfigurationSection("cosmetics.kill-messages"), logger),
                Effects.load(effects, logger));
    }

    public List<Cosmetic> all(Kind kind) {
        return kind == Kind.KILL_EFFECT ? killEffects : killMessages;
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
            effects.playAt(location, listeners, effect.id());
        });
    }

    private static List<Cosmetic> entries(ConfigurationSection section, Logger logger) {
        List<Cosmetic> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            Material icon = entry == null ? null : Material.matchMaterial(entry.getString("icon", ""));
            if (icon == null || !icon.isItem() || icon.isAir()) {
                logger.warning("config.yml " + section.getCurrentPath() + "." + id + " needs an item as icon; ignoring it");
                continue;
            }
            String permission = entry.getString("permission", "");
            entries.add(new Cosmetic(id, icon, entry.getString("name", id), permission.isBlank() ? null : permission,
                    entry.getBoolean("lightning", false)));
        }
        return List.copyOf(entries);
    }
}
