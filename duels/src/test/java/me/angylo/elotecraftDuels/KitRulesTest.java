package me.angylo.elotecraftDuels;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitRulesTest extends DuelsTestBase {

    private static final String DUEL_DROP_TAG = "elotecraft-duels-drop";

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
        setConfig("rules.kit-defaults.hunger", true);
        Kit kit = swordKit();

        assertTrue(kit.flag(KitRule.HUNGER, duels.settings()));
        assertTrue(kit.flag(KitRule.FALL_DAMAGE, duels.settings()));
        assertTrue(kit.number(KitRule.PEARL_COOLDOWN, duels.settings()).isEmpty());
    }

    @Test
    void everyRuleTakesItsDefaultFromConfigUnlessTheKitSetsIt() {
        setConfig("rules.kit-defaults.fall-damage", false);
        setConfig("rules.kit-defaults.build", true);
        setConfig("rules.kit-defaults.pearl-cooldown", 5);
        Kit kit = swordKit();

        assertFalse(kit.flag(KitRule.FALL_DAMAGE, duels.settings()));
        assertTrue(kit.flag(KitRule.BUILD, duels.settings()));
        assertEquals(5, kit.number(KitRule.PEARL_COOLDOWN, duels.settings()).orElseThrow());
        Kit own = kit.withRule(KitRule.FALL_DAMAGE, true).withRule(KitRule.PEARL_COOLDOWN, 0);
        assertTrue(own.flag(KitRule.FALL_DAMAGE, duels.settings()));
        assertEquals(0, own.number(KitRule.PEARL_COOLDOWN, duels.settings()).orElseThrow());
    }

    @Test
    void invalidDefaultsFallBackToTheBuiltInOnes() {
        setConfig("rules.kit-defaults.damage", "maybe");
        setConfig("rules.kit-defaults.max-health", 5000);
        setConfig("rules.kit-defaults.pearl-cooldown", "vanilla");
        Kit kit = swordKit();

        assertTrue(kit.flag(KitRule.DAMAGE, duels.settings()));
        assertTrue(kit.number(KitRule.MAX_HEALTH, duels.settings()).isEmpty());
        assertTrue(kit.number(KitRule.PEARL_COOLDOWN, duels.settings()).isEmpty());
    }

    @Test
    void anOlderConfigKeepsItsRuleDefaults() {
        setConfig("rules.hunger", true);
        setConfig("parties.friendly-fire", true);
        Kit kit = swordKit();

        assertTrue(kit.flag(KitRule.HUNGER, duels.settings()));
        assertTrue(kit.flag(KitRule.FRIENDLY_FIRE, duels.settings()));
        server.getScheduler().waitAsyncTasksFinished();
        YamlConfiguration file = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        assertTrue(file.getBoolean("rules.kit-defaults.hunger"));
        assertTrue(file.getBoolean("rules.kit-defaults.friendly-fire"));
        assertFalse(file.contains("rules.hunger") || file.contains("parties.friendly-fire"));
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
    void itemDropsLetFightersDropAndPickUpButNobodyOutside() {
        TestPlayer outsider = join("Outsider");
        fight(swordKit().withRule(KitRule.ITEM_DROPS, true));
        Item item = arenaWorld.dropItem(alex.getLocation(), ItemStack.of(Material.DIAMOND_SWORD));

        PlayerDropItemEvent drop = new PlayerDropItemEvent(alex, item);
        server.getPluginManager().callEvent(drop);
        EntityPickupItemEvent fighterPickup = new EntityPickupItemEvent(steve, item, 0);
        server.getPluginManager().callEvent(fighterPickup);
        EntityPickupItemEvent outsiderPickup = new EntityPickupItemEvent(outsider, item, 0);
        server.getPluginManager().callEvent(outsiderPickup);

        assertFalse(drop.isCancelled());
        assertTrue(item.getScoreboardTags().contains(DUEL_DROP_TAG));
        assertFalse(fighterPickup.isCancelled());
        assertTrue(outsiderPickup.isCancelled());
    }

    @Test
    void anyDropRuleAllowsPickingUpButOnlyItemDropsAllowsThrowing() {
        fight(swordKit().withRule(KitRule.DEATH_DROPS, true));
        Item item = arenaWorld.dropItem(alex.getLocation(), ItemStack.of(Material.DIAMOND_SWORD));

        PlayerDropItemEvent drop = new PlayerDropItemEvent(alex, item);
        server.getPluginManager().callEvent(drop);
        EntityPickupItemEvent pickup = new EntityPickupItemEvent(steve, item, 0);
        server.getPluginManager().callEvent(pickup);

        assertTrue(drop.isCancelled());
        assertFalse(pickup.isCancelled());
    }

    @Test
    void deathDropsSpillTheKnockedOutFightersInventory() {
        fight(swordKit().withRule(KitRule.DEATH_DROPS, true));
        alex.getInventory().setItem(0, ItemStack.of(Material.DIAMOND_SWORD));

        alex.simulateDamage(alex.getHealth() + 10, steve);

        assertTrue(alex.getInventory().isEmpty());
        List<Item> drops = List.copyOf(arenaWorld.getEntitiesByClass(Item.class));
        assertTrue(drops.stream().anyMatch(item -> item.getItemStack().getType() == Material.DIAMOND_SWORD));
        assertTrue(drops.stream().allMatch(item -> item.getScoreboardTags().contains(DUEL_DROP_TAG)));
    }

    @Test
    void withoutDeathDropsTheInventoryStays() {
        fight(swordKit());
        alex.getInventory().setItem(0, ItemStack.of(Material.DIAMOND_SWORD));

        alex.simulateDamage(alex.getHealth() + 10, steve);

        assertFalse(alex.getInventory().isEmpty());
        assertTrue(arenaWorld.getEntitiesByClass(Item.class).isEmpty());
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

    @Test
    void boxingEndsTheDuelOnTheLastHit() {
        Match match = fight(swordKit().withRule(KitRule.DAMAGE, false).withRule(KitRule.HITS_TO_WIN, 3));

        steve.simulateDamage(1, alex);
        steve.simulateDamage(1, alex);
        assertEquals(Match.State.FIGHTING, match.state());
        steve.simulateDamage(1, alex);

        assertEquals(Match.State.ENDING, match.state());
        assertEquals(1, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
    }

    /**
     * Paper fires the damage event again for hits while the victim is still invulnerable (with 0 damage, for
     * every click). Found with mineflayer bots spam-clicking: 22 hits counted for 5 that landed.
     */
    @Test
    void hitsWhileInvulnerableDoNotCount() {
        Match match = fight(swordKit().withRule(KitRule.DAMAGE, false).withRule(KitRule.HITS_TO_WIN, 2));
        assertFalse(punch(steve).isCancelled());

        steve.setNoDamageTicks(steve.getMaximumNoDamageTicks() / 2 + 1);
        assertTrue(punch(steve).isCancelled(), "a knockback-only hit while invulnerable");
        assertEquals(Match.State.FIGHTING, match.state());

        steve.setNoDamageTicks(steve.getMaximumNoDamageTicks() / 2);
        punch(steve);
        assertEquals(Match.State.ENDING, match.state());
    }

    /** Alex punches {@code victim} as Paper would report it. */
    private EntityDamageByEntityEvent punch(Player victim) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(alex, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(alex).withDirectEntity(alex).build(), 1);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void archersSeeTheHealthTheirArrowLeft() {
        fight(swordKit());
        Arrow arrow = arenaWorld.spawn(steve.getLocation(), Arrow.class);
        arrow.setShooter(alex);
        messages(alex);

        server.getPluginManager().callEvent(new EntityDamageByEntityEvent(arrow, steve, EntityDamageEvent.DamageCause.PROJECTILE,
                DamageSource.builder(DamageType.ARROW).withCausingEntity(alex).withDirectEntity(arrow).build(), 5));

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Steve is on 7.5❤")), "health message");
    }

    @Test
    void numberRulesHaveTheirOwnLimits() {
        Kit kit = swordKit();

        assertEquals(100, kit.withRule(KitRule.HITS_TO_WIN, 100).number(KitRule.HITS_TO_WIN, duels.settings()).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> kit.withRule(KitRule.HITS_TO_WIN, KitRule.MAX_HITS + 1));
        assertEquals("15s", KitRule.PEARL_COOLDOWN.format(15));
        assertEquals("100", KitRule.HITS_TO_WIN.format(100));
    }
}
