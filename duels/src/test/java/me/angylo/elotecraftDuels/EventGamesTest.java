package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.event.HostedEvent;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Egg;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event games: Juggernaut, One in the chamber, King of the hill, TNT tag and Splegg. */
class EventGamesTest extends DuelsTestBase {

    private TestPlayer ann;
    private TestPlayer bob;
    private TestPlayer cid;

    @BeforeEach
    void setUpPlayers() {
        ann = join("Ann");
        bob = join("Bob");
        cid = join("Cid");
        swordKit();
        readyArena("pit");
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 20; z++) {
                arenaWorld.getBlockAt(x, 63, z).setType(Material.STONE);
            }
        }
    }

    /** Ann hosts a sword event of {@code mode}, Bob and Cid join, and the fight starts. */
    private Match start(HostedEvent.Mode mode) {
        assertSays(ann, "event host sword", "You're hosting a Sword event");
        duels.events().toggleOpen(ann);
        HostedEvent event = duels.events().hostedBy(ann).orElseThrow();
        while (event.mode() != mode) {
            duels.events().toggleMode(ann);
        }
        assertSays(bob, "event join Ann", "You joined Ann's event");
        assertSays(cid, "event join Ann", "You joined Ann's event");
        assertSays(ann, "event start", "Ann's event begins");
        Match match = duels.matches().matchOf(ann).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        return match;
    }

    @Test
    void theJuggernautFightsEveryoneElseWithBuffs() {
        Match match = start(HostedEvent.Mode.JUGGERNAUT);

        assertEquals(2, match.teams().size());
        assertEquals(1, match.teams().getFirst().size());
        assertTrue(match.teams().getFirst().getFirst().hasPotionEffect(PotionEffectType.RESISTANCE));
        assertFalse(match.teams().get(1).getFirst().hasPotionEffect(PotionEffectType.RESISTANCE));
    }

    @Test
    void anArrowKillsAKillGivesAnArrowAndThreeLivesThenOut() {
        Match match = start(HostedEvent.Mode.OITC);
        assertTrue(ann.getInventory().contains(Material.BOW));

        for (int life = 1; life <= 3; life++) {
            Arrow arrow = arenaWorld.spawn(bob.getLocation(), Arrow.class);
            arrow.setShooter(ann);
            EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(arrow, bob, EntityDamageEvent.DamageCause.PROJECTILE,
                    DamageSource.builder(DamageType.ARROW).build(), 1);
            server.getPluginManager().callEvent(hit);
            tick();
            assertEquals(life < 3, match.isFighting(bob), "life " + life);
        }
        assertTrue(ann.getInventory().all(Material.ARROW).values().stream().mapToInt(item -> item.getAmount()).sum() >= 2);
    }

    @Test
    void standingAloneOnTheHillWins() {
        Match match = start(HostedEvent.Mode.KOTH);
        ann.teleport(match.instance().middle());
        bob.teleport(match.instance().spawn(1));
        cid.teleport(match.instance().spawn(2));

        tickUntil(() -> match.state() == Match.State.ENDING);

        assertEquals(List.of(match.teamOf(ann.getUniqueId())), match.winnerTeams());
    }

    @Test
    void theTntPassesOnAHitAndBlowsUpItsCarrier() {
        Match match = start(HostedEvent.Mode.TNT_TAG);
        ticks(20);
        TestPlayer carrier = List.of(ann, bob, cid).stream().filter(player -> player.getInventory().getHelmet() != null
                && player.getInventory().getHelmet().getType() == Material.TNT).findFirst().map(TestPlayer.class::cast).orElseThrow();
        TestPlayer other = carrier == ann ? bob : ann;

        other.simulateDamage(1, carrier);

        assertNotNull(other.getInventory().getHelmet());
        assertEquals(Material.TNT, other.getInventory().getHelmet().getType());
        tickUntil(() -> !match.isFighting(other));
        assertTrue(match.isFighting(carrier));
    }

    @Test
    void aSpleggEggBreaksTheBlockItHits() {
        Match match = start(HostedEvent.Mode.SPLEGG);
        assertTrue(match.instance().isBuild());
        assertTrue(ann.getInventory().contains(Material.IRON_SHOVEL));
        Egg egg = arenaWorld.spawn(ann.getLocation(), Egg.class);
        egg.setShooter(ann);

        server.getPluginManager().callEvent(new ProjectileHitEvent(egg, null, arenaWorld.getBlockAt(10, 63, 10), null));

        assertEquals(Material.AIR, arenaWorld.getBlockAt(10, 63, 10).getType());
    }
}
