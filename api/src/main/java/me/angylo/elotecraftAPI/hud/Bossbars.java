package me.angylo.elotecraftAPI.hud;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timed boss bars. For a permanent bar use Adventure directly:
 * {@code player.showBossBar(BossBar.bossBar(...))}.
 */
public final class Bossbars {

    private static final long UPDATE_PERIOD_TICKS = 2;
    private static final Map<Plugin, Set<Timed>> ACTIVE = new ConcurrentHashMap<>();

    private static final class Timed {
        private final Player player;
        private final BossBar bar;
        private BukkitTask task;

        private Timed(Player player, BossBar bar) {
            this.player = player;
            this.bar = bar;
        }

        private void stop() {
            task.cancel();
            player.hideBossBar(bar);
        }
    }

    private Bossbars() {
    }

    /** @param name MiniMessage text */
    public static BossBar timed(Plugin plugin, Player player, String name, BossBar.Color color, Duration duration) {
        return timed(plugin, player, Text.mm(name), color, duration);
    }

    /**
     * Shows a bar that drains from full to empty over {@code duration}, then hides it.
     * Returns the bar, so its name or color can change; it also hides when the player quits or the plugin disables.
     * Main thread only.
     */
    public static BossBar timed(Plugin plugin, Player player, Component name, BossBar.Color color, Duration duration) {
        BossBar bar = BossBar.bossBar(name, 1f, color, BossBar.Overlay.PROGRESS);
        long totalTicks = Math.max(1, duration.toMillis() / 50);
        long[] elapsed = {0};
        Timed timed = new Timed(player, bar);
        Set<Timed> active = ACTIVE.computeIfAbsent(plugin, key -> ConcurrentHashMap.newKeySet());
        timed.task = Tasks.timer(plugin, () -> {
            elapsed[0] += UPDATE_PERIOD_TICKS;
            if (elapsed[0] >= totalTicks || !player.isOnline()) {
                timed.stop();
                active.remove(timed);
                return;
            }
            bar.progress(1f - (float) elapsed[0] / totalTicks);
        }, UPDATE_PERIOD_TICKS, UPDATE_PERIOD_TICKS);
        active.add(timed);
        player.showBossBar(bar);
        return bar;
    }

    /** Hides every timed bar {@code plugin} showed; called automatically when it disables. */
    public static void hideAll(Plugin plugin) {
        Set<Timed> active = ACTIVE.remove(plugin);
        if (active != null) {
            active.forEach(Timed::stop);
        }
    }
}
