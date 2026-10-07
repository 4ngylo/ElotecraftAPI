package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.GameMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fights between teams: knock-outs, the last team standing, teammates, quits and draws. */
class TeamMatchTest extends DuelsTestBase {

    private TestPlayer ann;
    private TestPlayer bob;
    private TestPlayer cid;
    private TestPlayer dee;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void setUpPlayers() {
        ann = join("Ann");
        bob = join("Bob");
        cid = join("Cid");
        dee = join("Dee");
        kit = swordKit();
        arena = readyArena("pit");
    }

    private Match fight(List<List<TestPlayer>> teams) {
        List<List<org.bukkit.entity.Player>> players = teams.stream().map(team -> List.<org.bukkit.entity.Player>copyOf(team)).toList();
        assertTrue(duels.matches().start(players, kit, arena, Match.Type.PARTY, false));
        Match match = duels.matches().matchOf(ann).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    @Test
    void theLastTeamStandingWinsAndTeammatesCannotHurtEachOther() {
        Match match = fight(List.of(List.of(ann, bob), List.of(cid, dee)));
        assertEquals(match.spawnOf(ann), match.spawnOf(bob));

        assertTrue(bob.simulateDamage(1, ann).isCancelled());
        cid.simulateDamage(100, ann);

        assertEquals(Match.State.FIGHTING, match.state());
        assertEquals(GameMode.SPECTATOR, cid.getGameMode());
        assertFalse(match.isAlive(cid));
        assertTrue(messages(dee).stream().anyMatch(line -> line.contains("Cid is out.")));
        assertTrue(cid.simulateDamage(1, dee).isCancelled());

        dee.simulateDamage(100, bob);

        assertEquals(Match.State.ENDING, match.state());
        assertTrue(messages(cid).stream().anyMatch(line -> line.contains("Ann, Bob won the fight")));
        assertEquals(0, duels.stats().cached(ann.getUniqueId()).orElseThrow().wins());
        assertEquals(0, duels.stats().cached(cid.getUniqueId()).orElseThrow().losses());

        ticks(20 * duels.settings().endDelaySeconds() + 1);
        for (TestPlayer player : List.of(ann, bob, cid, dee)) {
            assertFalse(duels.matches().isBusy(player));
            assertEquals(GameMode.SURVIVAL, player.getGameMode());
        }
        assertTrue(duels.matches().rematchOf(ann).isEmpty());
    }

    @Test
    void everyoneForThemselvesUntilOneIsLeft() {
        Match match = fight(List.of(List.of(ann), List.of(bob), List.of(cid)));

        bob.simulateDamage(100, ann);
        assertEquals(Match.State.FIGHTING, match.state());
        cid.simulateDamage(100, ann);

        assertEquals(Match.State.ENDING, match.state());
        assertTrue(messages(bob).stream().anyMatch(line -> line.contains("Ann won the fight")));
    }

    @Test
    void aTeamThatQuitsLoses() {
        Match match = fight(List.of(List.of(ann, bob), List.of(cid, dee)));

        cid.disconnect();
        tick();
        assertEquals(Match.State.FIGHTING, match.state());
        dee.disconnect();
        tick();

        assertEquals(Match.State.ENDING, match.state());
        assertTrue(messages(ann).stream().anyMatch(line -> line.contains("Ann, Bob won the fight")));
    }

    @Test
    void aFighterWhoForfeitsWatchesAndCanThenLeave() {
        Match match = fight(List.of(List.of(ann, bob), List.of(cid, dee)));

        assertTrue(duels.matches().leave(ann));
        assertEquals(GameMode.SPECTATOR, ann.getGameMode());
        assertTrue(duels.matches().isBusy(ann));

        assertTrue(duels.matches().leave(ann));
        assertFalse(duels.matches().isBusy(ann));
        assertEquals(Match.State.FIGHTING, match.state());
    }

    @Test
    void timeRunningOutIsADraw() {
        setConfig("match.max-duration", "10s");
        Match match = fight(List.of(List.of(ann), List.of(bob), List.of(cid)));

        ticks(20 * 11);

        assertEquals(Match.State.ENDING, match.state());
        assertTrue(messages(cid).stream().anyMatch(line -> line.contains("Ann and Bob, Cid ended in a draw")));
    }
}
