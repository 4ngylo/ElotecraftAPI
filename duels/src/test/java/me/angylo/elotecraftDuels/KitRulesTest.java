package me.angylo.elotecraftDuels;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderPearl;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitRulesTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void players() {
        alex = join("Alex");
        steve = join("Steve");
    }

    private Match fight(Kit kit) {
        await(duels.kits().update(kit));
        assertTrue(duels.matches().start(alex, steve, kit, readyArena("pit")));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    @Test
    void uhcRulesDrainHungerButStopRegenerationFromFood() {
        fight(swordKit().withRule(KitRule.HUNGER, true).withRule(KitRule.NATURAL_REGENERATION, false));

        FoodLevelChangeEvent hunger = new FoodLevelChangeEvent(alex, 10);
        server.getPluginManager().callEvent(hunger);
        EntityRegainHealthEvent satiated = new EntityRegainHealthEvent(alex, 1, EntityRegainHealthEvent.RegainReason.SATIATED);
        server.getPluginManager().callEvent(satiated);
        EntityRegainHealthEvent goldenApple = new EntityRegainHealthEvent(alex, 1, EntityRegainHealthEvent.RegainReason.MAGIC_REGEN);
        server.getPluginManager().callEvent(goldenApple);

        assertFalse(hunger.isCancelled());
        assertTrue(satiated.isCancelled());
        assertFalse(goldenApple.isCancelled());
    }

    @Test
    void unsetRulesFollowConfig() {
        setConfig("rules.hunger", true);
        Kit kit = swordKit();

        assertTrue(kit.flag(KitRule.HUNGER, duels.settings()));
        assertTrue(kit.flag(KitRule.FALL_DAMAGE, duels.settings()));
        assertTrue(kit.seconds(KitRule.PEARL_COOLDOWN).isEmpty());
    }

    @Test
    void damageCauseRulesCancelOnlyTheirCauses() {
        fight(swordKit().withRule(KitRule.FALL_DAMAGE, false));

        EntityDamageEvent fall = new EntityDamageEvent(alex, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 2);
        server.getPluginManager().callEvent(fall);
        EntityDamageEvent fire = new EntityDamageEvent(alex, EntityDamageEvent.DamageCause.FIRE_TICK,
                DamageSource.builder(DamageType.ON_FIRE).build(), 1);
        server.getPluginManager().callEvent(fire);

        assertTrue(fall.isCancelled());
        assertFalse(fire.isCancelled());
    }

    @Test
    void itemDurabilityRuleKeepsGearWhole() {
        fight(swordKit().withRule(KitRule.ITEM_DURABILITY, false));

        PlayerItemDamageEvent wear = new PlayerItemDamageEvent(alex, ItemStack.of(Material.DIAMOND_SWORD), 1, 1);
        server.getPluginManager().callEvent(wear);

        assertTrue(wear.isCancelled());
    }

    @Test
    void comboKitsDropTheHitDelayUntilTheDuelEnds() {
        fight(swordKit().withRule(KitRule.HIT_DELAY, false));
        assertTrue(alex.getMaximumNoDamageTicks() < 20);

        assertTrue(duels.matches().leave(alex));

        tickUntil(() -> !duels.matches().isBusy(alex) && alex.getMaximumNoDamageTicks() == 20);
    }

    @Test
    void pearlCooldownIsSetAfterAThrowAndClearedAfterTheDuel() {
        fight(swordKit().withRule(KitRule.PEARL_COOLDOWN, 15));
        EnderPearl pearl = alex.getWorld().spawn(alex.getLocation(), EnderPearl.class);

        server.getPluginManager().callEvent(new PlayerLaunchProjectileEvent(alex, ItemStack.of(Material.ENDER_PEARL), pearl));
        tick();

        assertEquals(15 * 20, alex.getCooldown(Material.ENDER_PEARL));
        assertTrue(duels.matches().leave(alex));
        tickUntil(() -> !duels.matches().isBusy(alex) && alex.getCooldown(Material.ENDER_PEARL) == 0);
    }

    @Test
    void ruleValuesMustMatchTheirKind() {
        Kit kit = swordKit();

        assertThrows(IllegalArgumentException.class, () -> kit.withRule(KitRule.PEARL_COOLDOWN, true));
        assertThrows(IllegalArgumentException.class, () -> kit.withRule(KitRule.PEARL_COOLDOWN, KitRule.MAX_SECONDS + 1));
        assertThrows(IllegalArgumentException.class, () -> kit.withRule(KitRule.HUNGER, 1));
        assertThrows(IllegalArgumentException.class, () -> KitRule.PEARL_COOLDOWN.parse("soon"));
        assertEquals(false, KitRule.CRAFTING.parse("false"));
    }
}
