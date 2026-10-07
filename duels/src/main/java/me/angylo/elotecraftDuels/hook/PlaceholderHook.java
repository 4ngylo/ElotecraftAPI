package me.angylo.elotecraftDuels.hook;

import me.angylo.elotecraftDuels.Duels;
import org.bukkit.Bukkit;

/**
 * Registers the {@code %duels_...%} placeholders when PlaceholderAPI is installed. Its classes are only
 * touched in {@link Papi}, which loads only while it is enabled, so the plugin runs without it.
 */
public final class PlaceholderHook {

    /** The registered expansion, typed as Object so this class loads without PlaceholderAPI. */
    private final Object expansion;

    private PlaceholderHook(Object expansion) {
        this.expansion = expansion;
    }

    public static PlaceholderHook register(Duels duels) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return new PlaceholderHook(null);
        }
        return new PlaceholderHook(Papi.register(duels));
    }

    public boolean registered() {
        return expansion != null;
    }

    public void unregister() {
        if (expansion != null) {
            Papi.unregister(expansion);
        }
    }

    private static final class Papi {

        static Object register(Duels duels) {
            DuelsExpansion expansion = new DuelsExpansion(duels);
            expansion.register();
            return expansion;
        }

        static void unregister(Object expansion) {
            ((DuelsExpansion) expansion).unregister();
        }
    }
}
