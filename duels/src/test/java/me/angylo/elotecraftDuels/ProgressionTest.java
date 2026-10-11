package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Kills, deaths and experience from duels, and the levels they reach. */
class ProgressionTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        swordKit();
        readyArena("pit");
    }

    @Test
    void aDuelKillCountsAKillADeathAndExperience() {
        duels.matches().start(alex, steve, duels.kits().get("sword").orElseThrow(), duels.arenas().get("pit").orElseThrow());
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);

        steve.simulateDamage(100, alex);

        assertEquals(new PlayerStats.Progress(1, 0, 25), duels.stats().cached(alex.getUniqueId()).orElseThrow().progress());
        assertEquals(new PlayerStats.Progress(0, 1, 5), duels.stats().cached(steve.getUniqueId()).orElseThrow().progress());
        tickUntil(() -> await(duels.stats().find("Alex")).orElseThrow().progress().xp() == 25);
    }

    @Test
    void eachLevelTakesLevelXpMoreThanTheOneBefore() {
        assertEquals(1, new PlayerStats.Progress(0, 0, 99).level(100));
        assertEquals(2, new PlayerStats.Progress(0, 0, 100).level(100));
        assertEquals(2, new PlayerStats.Progress(0, 0, 299).level(100));
        assertEquals(3, new PlayerStats.Progress(0, 0, 300).level(100));
        assertEquals(1, new PlayerStats.Progress(0, 0, 299).toNextLevel(100));
    }
}
