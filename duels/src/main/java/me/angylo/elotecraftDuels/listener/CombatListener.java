package me.angylo.elotecraftDuels.listener;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
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
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Fight rules: only the two fighters of a match hurt each other, and only while fighting. A lethal hit
 * ends the duel instead of killing, so there is no death screen, no vanilla drops and no death event for
 * other plugins (graves, /back) to react to; kits with {@link KitRule#DEATH_DROPS} drop the inventory. Also freezes fighters during the countdown, keeps everyone
 * inside the arena and applies the kit's game rules ({@link KitRule}).
 */
public final class CombatListener implements Listener {

    private static final long RESPAWN_DELAY_TICKS = 1;
    private static final long PEARL_COOLDOWN_DELAY_TICKS = 1;
    private static final int TICKS_PER_SECOND = 20;
    /** A health potion that healed its thrower less than this was missed. */
    private static final double HALF_INTENSITY = 0.5;
    private static final double PERCENT = 100;

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
                || (attacker != null && !attacker.equals(victim)
                && (!victimMatch.isFighting(attacker)
                || (victimMatch.sameTeam(attacker, victim) && !victimMatch.kit().flag(KitRule.FRIENDLY_FIRE, settings.get()))))) {
            event.setCancelled(true);
            return;
        }
        // A pearl's landing damage is not self-damage; it follows the fall rule like vanilla's cause.
        boolean self = victim.equals(attacker)
                && !(event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof EnderPearl);
        if (!damageAllowed(victimMatch.kit(), event.getCause(), self)) {
            event.setCancelled(true);
            return;
        }
        boolean byOpponent = attacker != null && !attacker.equals(victim);
        OptionalInt multiplier = victimMatch.kit().number(KitRule.DAMAGE_MULTIPLIER);
        if (byOpponent && multiplier.isPresent() && multiplier.getAsInt() > 0) {
            event.setDamage(event.getDamage() * multiplier.getAsInt() / PERCENT);
        }
        if (byOpponent && invulnerable(victim)) {
            // Paper fires this event again for a harder hit while the victim is still invulnerable, and a
            // knockback-only kit's 0 damage makes every hit harder: count none of them, and let only damage through.
            if (!victimMatch.kit().damage()) {
                event.setCancelled(true);
                return;
            }
        } else if (byOpponent) {
            victimMatch.fightStats().hit(attacker, victim);
            if (lastHit(victimMatch, attacker, victim)) {
                event.setCancelled(true);
                dropInventory(victim);
                matches.eliminate(victim);
                return;
            }
        }
        // Knockback-only kits (Sumo): the hit still pushes, but never hurts; falling off the arena decides.
        if (!victimMatch.kit().damage()) {
            event.setDamage(0);
            return;
        }
        if (victim.getHealth() - event.getFinalDamage() <= 0 && !holdsTotem(victim)) {
            event.setCancelled(true);
            dropInventory(victim);
            matches.eliminate(victim);
        }
    }

    /** Tells a shooter how much health their arrow left the target with ({@code match.arrow-health}). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent event) {
        if (!settings.get().arrowHealth() || !(event.getDamager() instanceof AbstractArrow arrow)
                || !(arrow.getShooter() instanceof Player shooter) || !(event.getEntity() instanceof Player target)
                || shooter.equals(target) || matches.matchOf(target).isEmpty()) {
            return;
        }
        double hearts = Math.max(0, target.getHealth() - event.getFinalDamage()) / 2;
        messages.send(shooter, "match.arrow-health", Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("health", String.format(Locale.ROOT, "%.1f", hearts)));
    }

    /** Backup for deaths that skip {@link #onDamage}, such as {@code /kill}: nothing is lost. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!matches.isRestricted(player)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setShouldDropExperience(false);
        event.deathMessage(null);
        dropInventory(player);
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
            // Bridge and bed fight fighters still in the fight come back at their spawn.
            event.setRespawnLocation(match.isFighting(player) ? match.spawnOf(player) : match.spectatorSpawn());
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
        // Bridge: walking into the other side's goal, or an end portal on its side, scores. The only place goals
        // are counted: the portal itself is cancelled by ProtectionListener, after this move.
        if (event.hasChangedBlock() && match.mode() == Kit.Mode.BRIDGE && match.isFighting(player)
                && match.scoresAt(match.teamOf(player.getUniqueId()), event.getTo(), settings.get().modes().goalRadius())) {
            matches.score(player);
            return;
        }
        if (event.hasChangedBlock() && !match.contains(event.getTo())) {
            // Falling out of the bottom loses the fight, like the void; any other way out is undone.
            if (match.kit().flag(KitRule.VOID_ELIMINATES, settings.get()) && match.isFighting(player)
                    && event.getTo().getY() < match.arena().bounds().getMinY()) {
                matches.eliminate(player);
                // Bridge and bed fight fighters come back at their spawn.
                event.setTo(match.isFighting(player) ? match.spawnOf(player) : match.spectatorSpawn());
                return;
            }
            boolean fighter = match.isFighter(player) && player.getGameMode() != GameMode.SPECTATOR;
            // Kits played over the void let fighters out; they fall and come back from below.
            if (fighter && !match.kit().flag(KitRule.ARENA_BOUNDS, settings.get())) {
                return;
            }
            event.setTo(fighter ? match.spawnOf(player) : match.spectatorSpawn());
            messages.send(player, "match.out-of-bounds");
        }
    }

    /**
     * Bridge: a golden apple heals a fighter fully at once, as instant health would, on top of its own effects;
     * food and saturation are left as the apple leaves them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGoldenApple(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (event.getItem().getType() != Material.GOLDEN_APPLE) {
            return;
        }
        matches.matchOf(player).filter(match -> match.mode() == Kit.Mode.BRIDGE && match.isFighting(player)).ifPresent(match -> {
            double missing = player.getAttribute(Attribute.MAX_HEALTH).getValue() - player.getHealth();
            if (missing > 0) {
                player.heal(missing, EntityRegainHealthEvent.RegainReason.MAGIC);
            }
        });
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
        if (frozenFighter(event.getPlayer())
                || (event.getItem().getType() == Material.POTION && !rule(event.getPlayer(), KitRule.POTIONS))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onThrowPotion(PlayerLaunchProjectileEvent event) {
        if (event.getProjectile() instanceof ThrownPotion && !rule(event.getPlayer(), KitRule.POTIONS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && matches.isRestricted(player)
                && event.getFoodLevel() < player.getFoodLevel() && !rule(player, KitRule.HUNGER)) {
            event.setCancelled(true);
        }
    }

    /** Healing from a full hunger bar; potions, golden apples and the like are not affected. */
    @EventHandler(ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (event.getEntity() instanceof Player player && matches.isRestricted(player)
                && (reason == EntityRegainHealthEvent.RegainReason.SATIATED || reason == EntityRegainHealthEvent.RegainReason.REGEN)
                && !rule(player, KitRule.NATURAL_REGENERATION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (!rule(event.getPlayer(), KitRule.ITEM_DURABILITY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        if (!(event.getArrow() instanceof Trident) && !rule(event.getPlayer(), KitRule.ARROW_PICKUP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && !rule(player, KitRule.CRAFTING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        Player player = event.getPlayer();
        Match match = matches.matchOf(player).orElse(null);
        if (match == null || !(event.getProjectile() instanceof EnderPearl)) {
            return;
        }
        match.kit().number(KitRule.PEARL_COOLDOWN).ifPresent(seconds ->
                // Paper puts the vanilla cooldown on after this event, so ours goes on a tick later.
                Tasks.later(plugin, () -> {
                    if (player.isOnline() && matches.matchOf(player).orElse(null) == match) {
                        player.setCooldown(Material.ENDER_PEARL, seconds * TICKS_PER_SECOND);
                    }
                }, PEARL_COOLDOWN_DELAY_TICKS));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        Player thrower = event.getPotion().getShooter() instanceof Player player ? player : null;
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (!canAffect(thrower, entity)) {
                event.setIntensity(entity, 0);
            }
        }
        Match match = thrower == null ? null : matches.matchOf(thrower).orElse(null);
        if (match != null && match.isFighting(thrower) && heals(event.getPotion())) {
            boolean healed = event.getAffectedEntities().contains(thrower) && event.getIntensity(thrower) >= HALF_INTENSITY;
            match.fightStats().healthPotion(thrower, !healed);
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

    /**
     * With the kit's {@link KitRule#DEATH_DROPS}, a fighter about to be knocked out drops everything they
     * carry where they stand, tagged like other duel drops. Their own items come back from their snapshot.
     */
    private void dropInventory(Player fighter) {
        Match match = matches.matchOf(fighter).orElse(null);
        if (match == null || !match.isFighting(fighter)) {
            return;
        }
        // Before the drop empties it, for /duel inventory.
        match.recordFinal(fighter);
        if (!match.kit().flag(KitRule.DEATH_DROPS, settings.get())) {
            return;
        }
        PlayerInventory inventory = fighter.getInventory();
        Location at = fighter.getLocation();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.isEmpty()) {
                at.getWorld().dropItemNaturally(at, stack, ProtectionListener::markDuelDrop);
            }
        }
        inventory.clear();
    }

    /** {@code rule} of the kit {@code player} is in a duel with, or its default outside one (the kit editor). */
    private boolean rule(Player player, KitRule rule) {
        Settings current = settings.get();
        return matches.matchOf(player).map(match -> match.kit().flag(rule, current)).orElseGet(() -> rule.defaultFlag(current));
    }

    /**
     * Counts a hit for {@link KitRule#HITS_TO_WIN} and shows the attacker the count.
     *
     * @return true when it is the hit that knocks {@code victim} out
     */
    private boolean lastHit(Match match, Player attacker, Player victim) {
        int needed = match.kit().number(KitRule.HITS_TO_WIN).orElse(0);
        if (needed <= 0) {
            return false;
        }
        int hits = match.hit(victim);
        attacker.sendActionBar(messages.get(attacker, "match.hits", Placeholder.unparsed("player", victim.getName()),
                Placeholder.unparsed("hits", String.valueOf(hits)), Placeholder.unparsed("max", String.valueOf(needed))));
        return hits >= needed;
    }

    /** The kit's damage rules: falling, fire, explosions and hurting yourself (bow boosting, your own TNT). */
    private boolean damageAllowed(Kit kit, EntityDamageEvent.DamageCause cause, boolean self) {
        Settings current = settings.get();
        if (self && !kit.flag(KitRule.SELF_DAMAGE, current)) {
            return false;
        }
        KitRule rule = switch (cause) {
            case FALL -> KitRule.FALL_DAMAGE;
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> KitRule.FIRE_DAMAGE;
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> KitRule.EXPLOSION_DAMAGE;
            default -> null;
        };
        return rule == null || kit.flag(rule, current);
    }

    /** Paper's own test in {@code LivingEntity.hurtServer}: within the first half of the no-damage ticks. */
    private static boolean invulnerable(Player victim) {
        return victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2f;
    }

    private boolean frozenFighter(Player player) {
        Match match = matches.matchOf(player).orElse(null);
        return match != null && match.isFighter(player) && match.state() != Match.State.FIGHTING;
    }

    /** Whether {@code potion} holds instant health, from its type or as an added effect. */
    private static boolean heals(ThrownPotion potion) {
        PotionMeta meta = potion.getPotionMeta();
        PotionType base = meta.getBasePotionType();
        return Stream.concat(base == null ? Stream.empty() : base.getPotionEffects().stream(), meta.getCustomEffects().stream())
                .anyMatch(effect -> effect.getType().equals(PotionEffectType.INSTANT_HEALTH));
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
