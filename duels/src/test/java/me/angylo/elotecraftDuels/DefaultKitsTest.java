package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.GoldenHeads;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The default kits a first start installs, and knockback-only kits such as Sumo. */
class DefaultKitsTest extends DuelsTestBase {

    private static final Set<String> DEFAULTS = Set.of("nodebuff", "debuff", "gapple", "builduhc", "classic", "archer", "sumo",
            "vanilla", "uhc", "pot", "nethop", "smp", "sword", "axe", "mace", "boxing", "combo", "spear", "bridge", "bedfight",
            "mlgrush", "fireball", "battlerush", "spleef", "tntsumo", "pearlfight");

    @Test
    void aFirstStartInstallsTwentySixPlayableKitsThatSurviveAReload() {
        duels.shutdown();
        assertTrue(new File(plugin.getDataFolder(), "kits.yml").delete());
        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());
        server.getScheduler().waitAsyncTasksFinished();
        Kit uhc = duels.kits().get("uhc").orElseThrow();
        assertFalse(uhc.flag(KitRule.NATURAL_REGENERATION, duels.settings()));
        assertTrue(uhc.flag(KitRule.HUNGER, duels.settings()));
        assertTrue(uhc.items().stream().anyMatch(GoldenHeads::is));
        assertTrue(duels.kits().get("builduhc").orElseThrow().items().stream().anyMatch(GoldenHeads::is));

        assertTrue(duels.kits().reload());
        // MockBukkit cannot read player heads back from kits.yml, so the UHC kits with golden heads are skipped there.
        Set<String> readable = headsRoundTrip() ? DEFAULTS
                : DEFAULTS.stream().filter(name -> !name.equals("uhc") && !name.equals("builduhc")).collect(Collectors.toSet());
        assertEquals(readable, Set.copyOf(duels.kits().names()));
        for (Kit kit : duels.kits().all()) {
            assertFalse(kit.isEmpty(), kit.name());
        }
        Kit noDebuff = duels.kits().get("nodebuff").orElseThrow();
        assertTrue(noDebuff.items().stream().filter(item -> item.getType() == Material.SPLASH_POTION).count() > 20);
        assertEquals(Material.DIAMOND_HELMET, noDebuff.items().get(39).getType());
        assertTrue(duels.kits().get("vanilla").orElseThrow().flag(KitRule.BUILD, duels.settings()));
        assertEquals(Material.TOTEM_OF_UNDYING, duels.kits().get("vanilla").orElseThrow().items().get(40).getType());
        assertFalse(duels.kits().get("sumo").orElseThrow().flag(KitRule.DAMAGE, duels.settings()));
        assertEquals(Set.of("sumo"), duels.kits().get("sumo").orElseThrow().arenaCategories());
        assertEquals(15, noDebuff.number(KitRule.PEARL_COOLDOWN, duels.settings()).orElseThrow());
        assertEquals(2, duels.kits().get("sumo").orElseThrow().number(KitRule.ROUNDS_TO_WIN, duels.settings()).orElseThrow());
        assertEquals(Kit.Mode.MLG_RUSH, duels.kits().get("mlgrush").orElseThrow().mode());
        assertEquals(Kit.Mode.SPLEEF, duels.kits().get("spleef").orElseThrow().mode());
        assertTrue(duels.kits().get("fireball").orElseThrow().flag(KitRule.FIREBALLS, duels.settings()));
        Kit boxing = duels.kits().get("boxing").orElseThrow();
        assertFalse(boxing.flag(KitRule.DAMAGE, duels.settings()));
        assertEquals(100, boxing.number(KitRule.HITS_TO_WIN, duels.settings()).orElseThrow());
        assertFalse(duels.kits().get("combo").orElseThrow().flag(KitRule.HIT_DELAY, duels.settings()));
        assertEquals(Material.DIAMOND_SPEAR, duels.kits().get("spear").orElseThrow().items().get(0).getType());
        Kit bridge = duels.kits().get("bridge").orElseThrow();
        assertEquals(Kit.Mode.BRIDGE, bridge.mode());
        assertTrue(bridge.flag(KitRule.BUILD, duels.settings()));
        assertEquals(5, bridge.number(KitRule.ROUNDS_TO_WIN, duels.settings()).orElseThrow());
        assertEquals(Kit.Mode.BED_FIGHT, duels.kits().get("bedfight").orElseThrow().mode());
    }

    private static boolean headsRoundTrip() {
        try {
            ItemStack.deserializeItemsFromBytes(ItemStack.serializeItemsAsBytes(List.of(GoldenHeads.create(1))));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void defaultsOnlyAddMissingKits() {
        Kit mine = swordKit();
        TestPlayer admin = join("Admin");
        admin.setOp(true);

        assertSays(admin, "duels kit defaults", "Added 25 default kits");
        assertEquals(List.of(ItemStack.of(Material.DIAMOND_SWORD)), duels.kits().get("sword").orElseThrow().items().subList(0, 1));
        assertEquals(mine.displayName(), duels.kits().get("sword").orElseThrow().displayName());
        assertSays(admin, "duels kit defaults", "Added 0 default kits");
    }

    @Test
    void sumoHitsOnlyKnockBackAndFallingOffLoses() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        Kit sumo = swordKit().withRule(KitRule.DAMAGE, false);
        await(duels.kits().update(sumo));
        Arena arena = readyArena("pit");
        assertTrue(duels.matches().start(alex, steve, sumo, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());

        EntityDamageEvent hit = steve.simulateDamage(100, alex);

        assertFalse(hit.isCancelled());
        assertEquals(20, steve.getHealth());
        assertEquals(Match.State.FIGHTING, match.state());

        steve.simulatePlayerMove(new Location(arenaWorld, 6, 59, 7));
        assertEquals(Match.State.ENDING, match.state());
        assertEquals(1, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
    }
}
