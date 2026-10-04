package me.angylo.elotecraftDuels;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Sound and particle effects from config.yml's {@code effects} section, played by name.
 * A missing or invalid effect plays nothing; invalid ones are logged when loaded.
 */
public final class Effects {

    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 500;
    private static final float MAX_VOLUME = 10f;
    private static final float MIN_PITCH = 0.5f;
    private static final float MAX_PITCH = 2f;

    /** Either part may be null. */
    private record Effect(Sound sound, Particle particle, int count) {
    }

    private final Map<String, Effect> effects;

    private Effects(Map<String, Effect> effects) {
        this.effects = Map.copyOf(effects);
    }

    static Effects load(ConfigurationSection section, Logger logger) {
        Map<String, Effect> loaded = new HashMap<>();
        if (section != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection effect = section.getConfigurationSection(name);
                if (effect == null) {
                    logger.warning("config.yml effects." + name + " must be a section like {sound: ui.button.click}; ignoring it");
                    continue;
                }
                loaded.put(name, new Effect(sound(effect, logger), particle(effect, logger),
                        Math.clamp(effect.getInt("count", DEFAULT_COUNT), 1, MAX_COUNT)));
            }
        }
        return new Effects(loaded);
    }

    /** Plays {@code name} to {@code player}; its particles appear around them for everyone nearby. */
    public void play(Player player, String name) {
        Effect effect = effects.get(name);
        if (effect == null) {
            return;
        }
        if (effect.sound() != null) {
            player.playSound(effect.sound());
        }
        if (effect.particle() != null) {
            player.getWorld().spawnParticle(effect.particle(), player.getLocation().add(0, 1, 0), effect.count(),
                    0.4, 0.8, 0.4, 0.05);
        }
    }

    private static Sound sound(ConfigurationSection effect, Logger logger) {
        String id = effect.getString("sound");
        if (id == null) {
            return null;
        }
        if (!Key.parseable(id)) {
            logger.warning("config.yml " + effect.getCurrentPath() + ".sound '" + id + "' is not a valid sound id; ignoring it");
            return null;
        }
        float volume = (float) Math.clamp(effect.getDouble("volume", 1), 0, MAX_VOLUME);
        float pitch = (float) Math.clamp(effect.getDouble("pitch", 1), MIN_PITCH, MAX_PITCH);
        return Sound.sound(Key.key(id), Sound.Source.MASTER, volume, pitch);
    }

    private static Particle particle(ConfigurationSection effect, Logger logger) {
        String name = effect.getString("particle");
        if (name == null) {
            return null;
        }
        try {
            Particle particle = Particle.valueOf(name.strip().toUpperCase(Locale.ROOT));
            if (particle.getDataType() == Void.class) {
                return particle;
            }
            logger.warning("config.yml " + effect.getCurrentPath() + ".particle " + particle + " needs extra data; ignoring it");
        } catch (IllegalArgumentException e) {
            logger.warning("config.yml " + effect.getCurrentPath() + ".particle '" + name + "' is not a particle; ignoring it");
        }
        return null;
    }
}
