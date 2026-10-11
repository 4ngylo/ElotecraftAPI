package me.angylo.elotecraftDuels.listener;

import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.MatchManager;
import org.bukkit.Material;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.function.Supplier;

/**
 * {@link KitRule#FIREBALLS}: a fighter right-clicking with a fire charge throws a fireball (Fireball Fight). It sets
 * nothing on fire; its blast breaks only what an explosion of the fight may break ({@link BuildListener}).
 */
public final class Fireballs implements Listener {

    /** Blast strength: a ghast fireball's is 1, TNT's 4. */
    private static final float YIELD = 2;
    /** Between throws, so a held click does not empty the stack. */
    private static final int COOLDOWN_TICKS = 10;

    private final Supplier<Settings> settings;
    private final MatchManager matches;

    public Fireballs(Supplier<Settings> settings, MatchManager matches) {
        this.settings = settings;
        this.matches = matches;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (held == null || held.getType() != Material.FIRE_CHARGE || event.getHand() == null
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !matches.matchOf(player).filter(match -> match.isFighting(player)
                        && match.kit().flag(KitRule.FIREBALLS, settings.get())).isPresent()) {
            return;
        }
        // Not lit on the clicked block.
        event.setCancelled(true);
        if (player.hasCooldown(Material.FIRE_CHARGE)) {
            return;
        }
        Fireball fireball = player.launchProjectile(Fireball.class);
        fireball.setIsIncendiary(false);
        fireball.setYield(YIELD);
        player.setCooldown(Material.FIRE_CHARGE, COOLDOWN_TICKS);
        ItemStack stack = player.getInventory().getItem(event.getHand());
        stack.setAmount(stack.getAmount() - 1);
        player.getInventory().setItem(event.getHand(), stack.getAmount() > 0 ? stack : null);
    }
}
