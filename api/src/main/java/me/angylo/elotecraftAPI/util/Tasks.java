package me.angylo.elotecraftAPI.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Scheduler shortcuts. Always pass your own plugin so tasks are cancelled when it disables.
 * Delays and periods are in ticks (20 ticks = 1 second).
 */
public final class Tasks {

    private Tasks() {
    }

    public static BukkitTask sync(Plugin plugin, Runnable task) {
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Runs off the main thread. Do not touch the world, entities or inventories from {@code task}. */
    public static BukkitTask async(Plugin plugin, Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public static BukkitTask later(Plugin plugin, Runnable task, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public static BukkitTask timer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    /**
     * Runs {@code supplier} off the main thread (DB, HTTP, file I/O) and completes the
     * returned future on the main thread, so non-async callbacks such as
     * {@code thenAccept} may use the Bukkit API. Exceptions complete the future exceptionally.
     * If the plugin disables first, the future never completes.
     */
    public static <T> CompletableFuture<T> supplyAsync(Plugin plugin, Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, task -> async(plugin, task))
                .whenCompleteAsync((result, error) -> { }, task -> {
                    if (plugin.isEnabled()) {
                        sync(plugin, task);
                    }
                });
    }
}
