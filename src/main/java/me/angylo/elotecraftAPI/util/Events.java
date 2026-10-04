package me.angylo.elotecraftAPI.util;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * Lambda event listeners without a listener class.
 * Stop listening with {@code HandlerList.unregisterAll(listener)} on the returned listener.
 */
public final class Events {

    private Events() {
    }

    public static <E extends Event> Listener listen(Plugin plugin, Class<E> type, Consumer<? super E> handler) {
        return listen(plugin, type, EventPriority.NORMAL, false, handler);
    }

    public static <E extends Event> Listener listen(Plugin plugin, Class<E> type, EventPriority priority,
                                                    boolean ignoreCancelled, Consumer<? super E> handler) {
        Listener listener = new Listener() {
        };
        Bukkit.getPluginManager().registerEvent(type, listener, priority, (ignored, event) -> {
            // Bukkit may pass events of other types sharing the same handler list.
            if (type.isInstance(event)) {
                handler.accept(type.cast(event));
            }
        }, plugin, ignoreCancelled);
        return listener;
    }
}
