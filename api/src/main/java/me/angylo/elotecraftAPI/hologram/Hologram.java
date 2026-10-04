package me.angylo.elotecraftAPI.hologram;

import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Floating text backed by a {@link TextDisplay} that always faces the viewer.
 * <pre>{@code
 * Hologram hologram = Hologram.spawn(plugin, location, "<gold><bold>Shop\n<gray>Right-click to open");
 * hologram.text("<gold><bold>Shop\n<red>Closed");
 * hologram.remove();
 * }</pre>
 * Holograms are not saved with the world, so a crash never leaves orphans behind; spawn them again
 * on startup. They are also removed when their chunk unloads ({@link #isValid()} turns false) and
 * when the plugin disables. Use {@link #entity()} for styling such as background or scale.
 * Main thread only.
 */
public final class Hologram {

    private static final Map<Plugin, Set<Hologram>> SPAWNED = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final TextDisplay display;

    private Hologram(Plugin plugin, TextDisplay display) {
        this.plugin = plugin;
        this.display = display;
    }

    /** @param text MiniMessage; use {@code \n} for more lines */
    public static Hologram spawn(Plugin plugin, Location location, String text) {
        return spawn(plugin, location, Text.mm(text));
    }

    /** @throws IllegalArgumentException if {@code location} has no world */
    public static Hologram spawn(Plugin plugin, Location location, Component text) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Hologram location has no world");
        }
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.text(text);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setPersistent(false);
        });
        Hologram hologram = new Hologram(plugin, display);
        SPAWNED.computeIfAbsent(plugin, key -> ConcurrentHashMap.newKeySet()).add(hologram);
        return hologram;
    }

    /** @param text MiniMessage */
    public Hologram text(String text) {
        return text(Text.mm(text));
    }

    public Hologram text(Component text) {
        display.text(text);
        return this;
    }

    public Hologram teleport(Location location) {
        display.teleport(location);
        return this;
    }

    public void remove() {
        display.remove();
        Set<Hologram> holograms = SPAWNED.get(plugin);
        if (holograms != null) {
            holograms.remove(this);
        }
    }

    /** False once removed or its chunk unloaded. */
    public boolean isValid() {
        return display.isValid();
    }

    /** The underlying entity, for styling (background, scale, shadow, see-through...). */
    public TextDisplay entity() {
        return display;
    }

    /** Removes every hologram {@code plugin} spawned; called automatically when it disables. */
    public static void removeAll(Plugin plugin) {
        Set<Hologram> holograms = SPAWNED.remove(plugin);
        if (holograms != null) {
            holograms.forEach(hologram -> hologram.display.remove());
        }
    }
}
