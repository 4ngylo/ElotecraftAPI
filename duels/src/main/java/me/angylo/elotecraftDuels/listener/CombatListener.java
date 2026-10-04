package me.angylo.elotecraftDuels.listener;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Fight rules: only the two fighters of a match hurt each other, and only while fighting. A lethal hit
 * ends the duel instead of killing, so there is no death screen, no drops and no death event for other
 * plugins (graves, /back) to react to. Also freezes fighters during the countdown, keeps everyone
 * inside the arena and applies the hunger and regeneration rules.
 */
public final class CombatListener implements Listener {

    private static final long RESPAWN_DELAY_TICKS = 1;

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final MatchManager matches;
    private final SnapshotStore snapshots;

    public CombatListener(Plugin plugin, Messages messages, Supplier<Settings> settings, MatchManager matches,
                          SnapshotStore snapshots) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.matches = matches;
        this.snapshots = snapshots;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity ? attacker(byEntity.getDamager()) : null;
        Match attackerMatch = attacker == null ? null : matches.matchOf(attacker).orElse(null);
        Player victim = event.getEntity() instanceof Player player ? player : null;
        Match victimMatch = victim == null ? null : matches.matchOf(victim).orElse(null);
        if (attackerMatch == null && victimMatch == null) {
            return;
        }
        // A duel never touches the outside: no hitting outsiders, mobs or item frames, and no being hit by them.
        if (victimMatch == null || (attackerMatch != null && attackerMatch != victimMatch)
                || !victimMatch.isFighting(victim)
                || (attacker != null && !attacker.equals(victim) && !victimMatch.isFighting(attacker))) {
            event.setCancelled(true);
            return;
        }
        if (victim.getHealth() - event.getFinalDamage() <= 0 && !holdsTotem(victim)) {
            event.setCancelled(true);
            matches.eliminate(victim);
        }
    }

    /** Backup for deaths that skip {@link #onDamage}, such as {@code /kill}: nothing is lost. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!matches.isBusy(player)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setShouldDropExperience(false);
        event.deathMessage(null);
        matches.handleDeath(player);
        Tasks.later(plugin, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        }, RESPAWN_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Optional<Location> restoreAt = snapshots.respawnLocation(player);
        if (restoreAt.isPresent()) {
            event.setRespawnLocation(restoreAt.get());
            snapshots.restoreAfterRespawn(player);
            return;
        }
        matches.matchOf(player).ifPresent(match -> {
            event.setRespawnLocation(match.spectatorSpawn());
            Tasks.later(plugin, () -> matches.respawned(player), RESPAWN_DELAY_TICKS);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        Match match = matches.matchOf(player).orElse(null);
        if (match == null || !match.hasArrived(player)) {
            return;
        }
        if (match.state() == Match.State.COUNTDOWN && match.isFighter(player)) {
            Location from = event.getFrom();
            Location to = event.getTo();
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            return;
        }
        if (event.hasChangedBlock() && !match.contains(event.getTo())) {
            boolean fighter = match.isFighter(player) && player.getGameMode() != GameMode.SPECTATOR;
            event.setTo(fighter ? match.spawnOf(player) : match.spectatorSpawn());
            messages.send(player, "match.out-of-bounds");
        }
    }

    /** No item use before the fight or after it ends: no pearls, potions or food. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (frozenFighter(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (frozenFighter(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!settings.get().hunger() && event.getEntity() instanceof Player player && matches.isBusy(player)
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (!settings.get().naturalRegeneration() && event.getEntity() instanceof Player player && matches.isBusy(player)
                && (reason == EntityRegainHealthEvent.RegainReason.SATIATED || reason == EntityRegainHealthEvent.RegainReason.REGEN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        Player thrower = event.getPotion().getShooter() instanceof Player player ? player : null;
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (!canAffect(thrower, entity)) {
                event.setIntensity(entity, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        Player source = event.getEntity().getSource() instanceof Player player ? player : null;
        event.getAffectedEntities().removeIf(entity -> !canAffect(source, entity));
    }

    /** Same rule as damage: inside a duel only fighters affect fighters, and nothing crosses its edge. */
    private boolean canAffect(Player source, Entity target) {
        Match sourceMatch = source == null ? null : matches.matchOf(source).orElse(null);
        Match targetMatch = target instanceof Player player ? matches.matchOf(player).orElse(null) : null;
        if (sourceMatch == null && targetMatch == null) {
            return true;
        }
        return sourceMatch == targetMatch && target instanceof Player player
                && sourceMatch.isFighting(player) && sourceMatch.isFighting(source);
    }

    private boolean frozenFighter(Player player) {
        Match match = matches.matchOf(player).orElse(null);
        return match != null && match.isFighter(player) && match.state() != Match.State.FIGHTING;
    }

    /** The player behind a damaging entity: arrows, tridents, TNT, lingering potions and pets count. */
    static Player attacker(Entity damager) {
        return switch (damager) {
            case Player player -> player;
            case Projectile projectile when projectile.getShooter() instanceof Player shooter -> shooter;
            case TNTPrimed tnt when tnt.getSource() instanceof Player source -> source;
            case AreaEffectCloud cloud when cloud.getSource() instanceof Player source -> source;
            case Tameable pet when pet.getOwner() instanceof Player owner -> owner;
            default -> null;
        };
    }

    private static boolean holdsTotem(Player player) {
        PlayerInventory inventory = player.getInventory();
        return inventory.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || inventory.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }
}
