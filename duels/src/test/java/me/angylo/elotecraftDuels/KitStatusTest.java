package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit rules and effects that change the fighters themselves: max health, damage, saturation and effects. */
class KitStatusTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void players() {
        alex = join("Alex");
        steve = join("Steve");
        alex.setOp(true);
        messages(alex);
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

    private static double maxHealth(TestPlayer player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    @Test
    void maxHealthLastsTheFightOnly() {
        fight(swordKit().withRule(KitRule.MAX_HEALTH, 40));
        assertEquals(40, maxHealth(alex));
        assertEquals(40, alex.getHealth());

        duels.matches().leave(alex);
        ticks(20 * duels.settings().endDelaySeconds() + 1);

        assertEquals(20, maxHealth(alex));
        assertTrue(alex.getHealth() <= 20);
    }

    @Test
    void damageMultiplierScalesHitsByOpponents() {
        fight(swordKit().withRule(KitRule.DAMAGE_MULTIPLIER, 200));

        steve.simulateDamage(2, alex);

        assertEquals(16, steve.getHealth(), 0.001);
    }

    @Test
    void effectsAndSaturationAreGivenAndTakenBack() {
        PotionEffect speed = Kit.effect(PotionEffectType.SPEED, 2);
        fight(swordKit().withEffect(PotionEffectType.SPEED, 2).withRule(KitRule.SATURATION, true));
        assertEquals(1, alex.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        assertNotNull(alex.getPotionEffect(PotionEffectType.SATURATION));
        assertEquals(speed, duels.kits().get("sword").orElseThrow().effects().getFirst());

        duels.matches().leave(alex);
        ticks(20 * duels.settings().endDelaySeconds() + 1);

        assertFalse(alex.hasPotionEffect(PotionEffectType.SPEED));
        assertFalse(alex.hasPotionEffect(PotionEffectType.SATURATION));
    }

    @Test
    void effectsAreSavedAndSetByCommand() {
        swordKit();
        assertSays(alex, "duels kit effect sword speed 2", "Sword gives speed 2 for the whole fight.");
        assertSays(alex, "duels kit effect sword jump_boost 1", "jump_boost 1");
        assertSays(alex, "duels kit effect sword speed 0", "Sword no longer gives speed.");
        assertSays(alex, "duels kit effect sword wings 1", "Use /duels kit effect");

        assertTrue(duels.kits().reload());
        assertEquals(List.of(Kit.effect(PotionEffectType.JUMP_BOOST, 1)), duels.kits().get("sword").orElseThrow().effects());
        assertSays(alex, "duels kit effect sword", "Effects of Sword: jump_boost 1");
    }
}
