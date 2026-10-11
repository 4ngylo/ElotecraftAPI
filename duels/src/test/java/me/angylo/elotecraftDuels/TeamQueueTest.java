package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.stats.MatchHistory;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2v2 queues: parties of two and solo players paired into teams, and the wins and losses they count. */
class TeamQueueTest extends DuelsTestBase {

    private TestPlayer ann;
    private TestPlayer bob;
    private TestPlayer cid;
    private TestPlayer dee;

    @BeforeEach
    void setUp() {
        ann = join("Ann");
        bob = join("Bob");
        cid = join("Cid");
        dee = join("Dee");
        swordKit();
        readyArena("pit");
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 20; z++) {
                arenaWorld.getBlockAt(x, 63, z).setType(Material.STONE);
            }
        }
    }

    private void party(TestPlayer leader, TestPlayer member) {
        assertSays(leader, "party " + member.getName(), "Invited");
        assertSays(member, "party accept " + leader.getName(), "joined the party");
    }

    private static List<Set<String>> teamNames(Match match) {
        return match.teams().stream().map(team -> team.stream().map(player -> player.getName()).collect(Collectors.toSet())).toList();
    }

    @Test
    void twoPartiesOfTwoFightATeamDuel() {
        party(ann, bob);
        party(cid, dee);

        server.dispatchCommand(ann, "duel 2v2 sword");
        server.dispatchCommand(cid, "duel 2v2 sword");

        Match match = duels.matches().matchOf(dee).orElseThrow();
        assertEquals(Match.Type.TEAM, match.type());
        assertEquals(List.of(Set.of("Ann", "Bob"), Set.of("Cid", "Dee")), teamNames(match));
    }

    @Test
    void soloPlayersArePairedIntoTeamsInOrder() {
        for (TestPlayer player : List.of(ann, bob, cid, dee)) {
            server.dispatchCommand(player, "duel 2v2 sword");
        }

        Match match = duels.matches().matchOf(ann).orElseThrow();
        assertEquals(List.of(Set.of("Ann", "Bob"), Set.of("Cid", "Dee")), teamNames(match));
    }

    @Test
    void aMemberLeavingDropsThePartyFromTheQueue() {
        party(ann, bob);
        server.dispatchCommand(ann, "duel 2v2 sword");
        assertTrue(duels.teamQueue().queued(bob).isPresent());

        server.dispatchCommand(bob, "party leave");
        ticks(20);

        assertTrue(duels.teamQueue().queued(ann).isEmpty());
        assertTrue(messages(ann).stream().anyMatch(line -> line.contains("your team changed")));
    }

    @Test
    void onlyThePartyLeaderQueuesAndOnlyAPartyOfTwo() {
        party(ann, bob);
        party(ann, cid);

        server.dispatchCommand(bob, "duel 2v2 sword");
        server.dispatchCommand(ann, "duel 2v2 sword");

        assertTrue(duels.teamQueue().queued(bob).isEmpty());
        assertTrue(duels.teamQueue().queued(ann).isEmpty());
        assertTrue(messages(ann).stream().anyMatch(line -> line.contains("Only a party of 2")));
    }

    /** Four solo players in the ranked 2v2 queue fight; the first two form the team that wins. */
    private Match rankedTeamDuel() {
        for (TestPlayer player : List.of(ann, bob, cid, dee)) {
            server.dispatchCommand(player, "duel 2v2ranked sword");
        }
        Match match = duels.matches().matchOf(ann).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        cid.simulateDamage(100, ann);
        dee.simulateDamage(100, bob);
        tickUntil(() -> duels.matches().matchOf(ann).isEmpty());
        return match;
    }

    @Test
    void aRankedTeamDuelMovesEveryonesRating() {
        Match match = rankedTeamDuel();

        assertTrue(match.isRanked());
        int start = PlayerStats.START_ELO;
        for (TestPlayer winner : List.of(ann, bob)) {
            assertTrue(duels.stats().elo(winner.getUniqueId(), "sword") > start);
        }
        for (TestPlayer loser : List.of(cid, dee)) {
            assertTrue(duels.stats().elo(loser.getUniqueId(), "sword") < start);
        }
    }

    @Test
    void teamDuelsGoToTheirOwnHistory() {
        rankedTeamDuel();

        tickUntil(() -> !await(duels.history().of(ann.getUniqueId(), true)).isEmpty());
        MatchHistory.Entry entry = await(duels.history().of(ann.getUniqueId(), true)).getFirst();
        assertTrue(entry.won());
        assertEquals("Bob", entry.teammates());
        assertEquals("Cid, Dee", entry.against());
        assertTrue(await(duels.history().of(ann.getUniqueId(), false)).isEmpty());
    }

    @Test
    void teamsPairOnlyWithinEveryOpponentsPingRange() {
        PingRange.next(ann);
        PingRange.next(ann);
        dee.ping(250);

        for (TestPlayer player : List.of(ann, bob, cid, dee)) {
            server.dispatchCommand(player, "duel 2v2 sword");
        }

        assertTrue(duels.matches().matchOf(ann).isEmpty());
        assertTrue(duels.teamQueue().queued(dee).isPresent());
    }

    @Test
    void aTeamDuelCountsAWinForEachWinnerAndALossForEachLoser() {
        for (TestPlayer player : List.of(ann, bob, cid, dee)) {
            server.dispatchCommand(player, "duel 2v2 sword");
        }
        Match match = duels.matches().matchOf(ann).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);

        cid.simulateDamage(100, ann);
        dee.simulateDamage(100, bob);

        tickUntil(() -> duels.matches().matchOf(ann).isEmpty());
        for (TestPlayer winner : List.of(ann, bob)) {
            assertEquals(1, duels.stats().cached(winner.getUniqueId()).orElseThrow().wins());
        }
        for (TestPlayer loser : List.of(cid, dee)) {
            assertEquals(1, duels.stats().cached(loser.getUniqueId()).orElseThrow().losses());
        }
    }
}
