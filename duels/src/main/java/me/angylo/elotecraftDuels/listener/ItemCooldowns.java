package me.angylo.elotecraftDuels.listener;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The item cooldowns of kit rules: {@link KitRule#PEARL_COOLDOWN} after an ender pearl, {@link KitRule#ARROW_COOLDOWN}
 * after a bow or crossbow shot and {@link KitRule#GAPPLE_COOLDOWN} after a golden apple. With {@link KitRule#COOLDOWN_BAR}
 * the experience bar counts the longest one running down: the level is the seconds left, the bar the part left.
 * {@code PlayerSnapshot} clears the cooldowns and puts the experience back after the fight. Main thread only.
 */
public final class ItemCooldowns implements Listener {

    private static final int TICKS_PER_SECOND = 20;
    /** Paper puts the vanilla pearl cooldown on after the launch event, so every cooldown goes on a tick later. */
    private static final long COOLDOWN_DELAY_TICKS = 1;
    private static final long BAR_PERIOD_TICKS = 2;

    /** A fighter's experience bar countdown: the full length of each cooldown it shows, by item. */
    private record Bar(Match match, Map<Material, Integer> lengths, BukkitTask[] task) {
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final MatchManager matches;
    private final Map<UUID, Bar> bars = new HashMap<>();

    public ItemCooldowns(Plugin plugin, Supplier<Settings> settings, MatchManager matches) {
        this.plugin = plugin;
        this.settings = settings;
        this.matches = matches;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        if (event.getProjectile() instanceof EnderPearl) {
            start(event.getPlayer(), KitRule.PEARL_COOLDOWN, Material.ENDER_PEARL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player && event.getBow() != null) {
            start(player, KitRule.ARROW_COOLDOWN, event.getBow().getType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.GOLDEN_APPLE) {
            start(event.getPlayer(), KitRule.GAPPLE_COOLDOWN, Material.GOLDEN_APPLE);
        }
    }

    /** Puts {@code rule}'s cooldown on {@code item} if {@code player} fights with a kit that sets it. */
    private void start(Player player, KitRule rule, Material item) {
        Match match = matches.matchOf(player).filter(found -> found.isFighting(player)).orElse(null);
        if (match == null) {
            return;
        }
        match.kit().number(rule, settings.get()).ifPresent(seconds -> Tasks.later(plugin, () -> {
            if (!player.isOnline() || matches.matchOf(player).orElse(null) != match) {
                return;
            }
            int ticks = seconds * TICKS_PER_SECOND;
            player.setCooldown(item, ticks);
            if (ticks > 0 && match.kit().flag(KitRule.COOLDOWN_BAR, settings.get())) {
                show(player, match, item, ticks);
            }
        }, COOLDOWN_DELAY_TICKS));
    }

    /** Adds {@code item}'s cooldown to {@code player}'s bar, starting the countdown if none runs. */
    private void show(Player player, Match match, Material item, int ticks) {
        Bar bar = bars.get(player.getUniqueId());
        if (bar != null && bar.match() == match) {
            bar.lengths().put(item, ticks);
            return;
        }
        if (bar != null) {
            bar.task()[0].cancel();
        }
        Bar started = new Bar(match, new HashMap<>(Map.of(item, ticks)), new BukkitTask[1]);
        started.task()[0] = Tasks.timer(plugin, () -> tick(player, started), 0, BAR_PERIOD_TICKS);
        bars.put(player.getUniqueId(), started);
    }

    /**
     * Shows the cooldown with the most time left; once none is left (bar emptied) or the fighter is out of the match
     * (bar left to the snapshot), the countdown stops.
     */
    private void tick(Player player, Bar bar) {
        boolean inMatch = player.isOnline() && matches.matchOf(player).orElse(null) == bar.match();
        Material longest = null;
        int left = 0;
        if (inMatch) {
            for (Material item : bar.lengths().keySet()) {
                int itemLeft = player.getCooldown(item);
                if (itemLeft > left) {
                    left = itemLeft;
                    longest = item;
                }
            }
            player.setLevel((left + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
            player.setExp(longest == null ? 0 : Math.clamp((float) left / bar.lengths().get(longest), 0, 1));
        }
        if (left <= 0) {
            bar.task()[0].cancel();
            bars.remove(player.getUniqueId(), bar);
        }
    }
}
