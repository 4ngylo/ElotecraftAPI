package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The event games ({@link Match.Game}) on top of an event fight's kit: Juggernaut's buffs, One in the Chamber's
 * one-shot arrows and lives, King of the Hill's points, TNT Tag's fuse and Splegg's eggs. {@link MatchManager} calls
 * the hooks; the handlers here run before {@code CombatListener} decides the damage. Main thread only.
 */
public final class EventGames implements Listener {

    /** One in the chamber: knockouts a fighter takes before they are out. */
    static final int OITC_LIVES = 3;
    /** King of the hill: seconds alone on the hill that win. */
    static final int KOTH_GOAL = 60;
    /** King of the hill: how far from the arena's middle (blocks, across and up) counts as on the hill. */
    private static final double KOTH_RADIUS = 3;
    /** TNT tag: seconds before the TNT goes off. */
    static final int TAG_SECONDS = 20;
    /** Juggernaut: extra health (Health Boost levels of 4 health points) and the strength of the buffs. */
    private static final int JUGGERNAUT_HEALTH_BOOST = 4;
    private static final double LETHAL = 1000;

    private final Messages messages;
    private final MatchManager matches;

    EventGames(Messages messages, MatchManager matches) {
        this.messages = messages;
        this.matches = matches;
    }

    /** After {@code fighter} was given the kit: the game's buffs or loadout. */
    void equip(Match match, Player fighter) {
        PlayerInventory inventory = fighter.getInventory();
        switch (match.options().game()) {
            case JUGGERNAUT -> {
                if (match.teamOf(fighter.getUniqueId()) == 0) {
                    fighter.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, PotionEffect.INFINITE_DURATION, JUGGERNAUT_HEALTH_BOOST - 1));
                    fighter.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 0));
                    fighter.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0));
                    fighter.setHealth(fighter.getAttribute(Attribute.MAX_HEALTH).getValue());
                }
            }
            case OITC -> {
                inventory.clear();
                inventory.setItem(0, ItemStack.of(Material.WOODEN_SWORD));
                inventory.setItem(1, ItemStack.of(Material.BOW));
                inventory.setItem(8, ItemStack.of(Material.ARROW));
            }
            case SPLEGG -> inventory.setItem(0, ItemStack.of(Material.IRON_SHOVEL));
            case NONE, KOTH, TNT_TAG -> { }
        }
    }

    /** Whether {@code fighter}, knocked out, comes back at their spawn rather than being out. */
    boolean respawns(Match match, Player fighter) {
        return switch (match.options().game()) {
            case OITC -> match.game().knockOut(fighter.getUniqueId()) < OITC_LIVES;
            case KOTH -> true;
            default -> match.respawns(match.teamOf(fighter.getUniqueId()));
        };
    }

    /** {@code victim} was knocked out, by {@code killer} if not null. */
    void knockedOut(Match match, Player victim, Player killer) {
        if (match.options().game() == Match.Game.OITC && killer != null) {
            killer.getInventory().addItem(ItemStack.of(Material.ARROW));
        }
        if (match.options().game() == Match.Game.TNT_TAG && victim.getUniqueId().equals(match.game().tagged())) {
            match.game().tag(null);
            victim.getInventory().setHelmet(null);
        }
    }

    /** Every second of the fight: the hill's points and the TNT's fuse. */
    void tick(Match match) {
        switch (match.options().game()) {
            case KOTH -> tickHill(match);
            case TNT_TAG -> tickTag(match);
            default -> { }
        }
    }

    /** The team alone on the hill earns a second; the first to {@link #KOTH_GOAL} wins. */
    private void tickHill(Match match) {
        Location middle = match.instance().middle();
        List<Integer> holding = match.fighters().stream().filter(match::isFighting)
                .filter(fighter -> onHill(fighter.getLocation(), middle))
                .map(fighter -> match.teamOf(fighter.getUniqueId())).distinct().toList();
        Integer holder = holding.size() == 1 ? holding.getFirst() : null;
        int points = holder == null ? 0 : match.game().hold(holder);
        for (Player participant : match.participants()) {
            int own = Math.max(0, match.teamOf(participant.getUniqueId()));
            participant.sendActionBar(messages.get(participant, holder == null ? "event.koth-free" : "event.koth-held",
                    Placeholder.unparsed("player", holder == null ? "" : match.teams().get(holder).getFirst().getName()),
                    Placeholder.unparsed("points", String.valueOf(holder == null ? 0 : points)),
                    Placeholder.unparsed("own", String.valueOf(match.game().points(own))),
                    Placeholder.unparsed("goal", String.valueOf(KOTH_GOAL))));
        }
        if (holder != null && points >= KOTH_GOAL) {
            matches.win(match, holder);
        }
    }

    private static boolean onHill(Location location, Location middle) {
        return location.getWorld() == middle.getWorld() && Math.abs(location.getX() - middle.getX()) <= KOTH_RADIUS
                && Math.abs(location.getZ() - middle.getZ()) <= KOTH_RADIUS && Math.abs(location.getY() - middle.getY()) <= KOTH_RADIUS;
    }

    /** A random fighter takes the TNT when nobody carries it; it goes off when the fuse runs out. */
    private void tickTag(Match match) {
        GameState game = match.game();
        Player tagged = game.tagged() == null ? null : Bukkit.getPlayer(game.tagged());
        if (tagged == null || !match.isFighting(tagged)) {
            List<Player> fighting = match.fighters().stream().filter(match::isFighting).toList();
            if (fighting.size() < 2) {
                return;
            }
            tagged = fighting.get(ThreadLocalRandom.current().nextInt(fighting.size()));
            game.tagSeconds(TAG_SECONDS);
            tag(match, tagged);
            return;
        }
        game.tagSeconds(game.tagSeconds() - 1);
        tagged.sendActionBar(messages.get(tagged, "event.tnt-fuse", Placeholder.unparsed("seconds", String.valueOf(game.tagSeconds()))));
        if (game.tagSeconds() <= 0) {
            tagged.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, tagged.getLocation(), 1);
            tagged.getWorld().playSound(tagged.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1, 1);
            for (Player participant : match.participants()) {
                messages.send(participant, "event.tnt-exploded", Placeholder.unparsed("player", tagged.getName()));
            }
            matches.eliminate(tagged);
        }
    }

    private void tag(Match match, Player player) {
        match.game().tag(player.getUniqueId());
        player.getInventory().setHelmet(ItemStack.of(Material.TNT));
        for (Player participant : match.participants()) {
            messages.send(participant, "event.tnt-tagged", Placeholder.unparsed("player", player.getName()));
        }
    }

    /** The fight both are fighting in, if it plays {@code game}. */
    private Optional<Match> playing(Player attacker, Player victim, Match.Game game) {
        return matches.matchOf(victim).filter(match -> match.options().game() == game && match.isFighting(victim)
                && match.isFighting(attacker) && !match.sameTeam(attacker, victim));
    }

    /** One in the chamber: an arrow kills. TNT tag: nobody is hurt, and the carrier's hit passes the TNT on. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player shooter
                && playing(shooter, victim, Match.Game.OITC).isPresent()) {
            event.setDamage(LETHAL);
            return;
        }
        if (event.getDamager() instanceof Player attacker) {
            playing(attacker, victim, Match.Game.TNT_TAG).ifPresent(match -> {
                event.setDamage(0);
                if (attacker.getUniqueId().equals(match.game().tagged())) {
                    attacker.getInventory().setHelmet(null);
                    tag(match, victim);
                }
            });
        }
    }

    /** Splegg: right-clicking a shovel shoots an egg. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (held == null || !held.getType().name().endsWith("_SHOVEL")
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || matches.matchOf(player).filter(match -> match.options().game() == Match.Game.SPLEGG && match.isFighting(player)).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        player.launchProjectile(Egg.class);
    }

    /** Splegg: an egg breaks the arena block it hits; the arena is put back after the fight. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEggHit(ProjectileHitEvent event) {
        Block block = event.getHitBlock();
        if (!(event.getEntity() instanceof Egg egg) || block == null || !(egg.getShooter() instanceof Player shooter)) {
            return;
        }
        matches.matchOf(shooter).filter(match -> match.options().game() == Match.Game.SPLEGG && match.isFighting(shooter)
                && match.canBuild(shooter, block.getLocation())).ifPresent(match -> {
            match.instance().changes().record(block);
            block.setType(Material.AIR);
        });
    }

    /** Splegg eggs never hatch chickens. */
    @EventHandler
    public void onEggThrow(PlayerEggThrowEvent event) {
        if (matches.matchOf(event.getPlayer()).filter(match -> match.options().game() == Match.Game.SPLEGG).isPresent()) {
            event.setHatching(false);
        }
    }
}
