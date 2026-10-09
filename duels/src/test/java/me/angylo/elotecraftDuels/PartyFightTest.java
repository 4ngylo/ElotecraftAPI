package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Party splits, party FFAs and party vs party, and the spawns they use. */
class PartyFightTest extends DuelsTestBase {

    private TestPlayer ann;
    private TestPlayer bob;
    private TestPlayer cid;
    private TestPlayer dee;
    private Arena pit;

    @BeforeEach
    void setUp() {
        ann = join("Ann");
        bob = join("Bob");
        cid = join("Cid");
        dee = join("Dee");
        swordKit();
        pit = readyArena("pit");
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 20; z++) {
                arenaWorld.getBlockAt(x, 63, z).setType(Material.STONE);
            }
        }
    }

    /** {@code leader} leads a party with {@code members}. */
    private void party(TestPlayer leader, TestPlayer... members) {
        for (TestPlayer member : members) {
            assertSays(leader, "party " + member.getName(), "Invited");
            assertSays(member, "party accept " + leader.getName(), "joined the party");
        }
    }

    private Match matchOf(TestPlayer player) {
        Match match = duels.matches().matchOf(player).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        return match;
    }

    /** Each team's member names. */
    private static List<Set<String>> teamNames(Match match) {
        return match.teams().stream().map(team -> team.stream().map(player -> player.getName()).collect(Collectors.toSet())).toList();
    }

    private static List<UUID> ids(TestPlayer... players) {
        return Arrays.stream(players).map(TestPlayer::getUniqueId).toList();
    }

    @Test
    void aSplitOpensTheTeamMenuWithTwoShuffledHalves() {
        party(ann, bob, cid, dee);

        server.dispatchCommand(ann, "party split sword");

        Inventory top = ann.getOpenInventory().getTopInventory();
        List<List<String>> lores = IntStream.range(0, top.getSize()).mapToObj(top::getItem)
                .filter(item -> item != null && item.getType() == Material.PLAYER_HEAD)
                .map(head -> head.getItemMeta().lore().stream().map(Text::plain).toList()).toList();
        assertEquals(4, lores.size());
        assertEquals(2, lores.stream().filter(lore -> lore.contains("▪ Red team")).count());
        assertEquals(2, lores.stream().filter(lore -> lore.contains("▪ Blue team")).count());
        assertTrue(duels.matches().matchOf(ann).isEmpty());
    }

    @Test
    void aSplitStartsWithTheChosenTeams() {
        party(ann, bob, cid, dee);
        Kit sword = duels.kits().get("sword").orElseThrow();
        duels.queues().toggle(bob, sword, false);

        duels.partyFights().startSplit(ann, ids(ann, bob, cid), ids(dee), sword, null);

        Match match = matchOf(ann);
        assertEquals(Match.Type.PARTY, match.type());
        assertEquals(List.of(Set.of("Ann", "Bob", "Cid"), Set.of("Dee")), teamNames(match));
        assertTrue(duels.queues().queued(bob.getUniqueId()).isEmpty());
        assertFalse(match.isRanked());
    }

    @Test
    void aSplitDropsLeaversAndPutsNewcomersOnTheSmallerTeam() {
        party(ann, bob, cid);

        // Dee is not in the party; Cid joined after the teams were picked.
        duels.partyFights().startSplit(ann, ids(ann, dee), ids(bob), duels.kits().get("sword").orElseThrow(), null);

        assertEquals(List.of(Set.of("Ann", "Cid"), Set.of("Bob")), teamNames(matchOf(ann)));
    }

    @Test
    void aSplitNeedsSomeoneOnEachTeam() {
        party(ann, bob);
        messages(ann);

        duels.partyFights().startSplit(ann, ids(ann, bob), List.of(), duels.kits().get("sword").orElseThrow(), null);

        assertTrue(messages(ann).stream().anyMatch(line -> line.contains("Both teams need at least one player")));
        assertTrue(duels.matches().matchOf(ann).isEmpty());
    }

    @Test
    void aPartyFfaSpreadsEveryoneBetweenTheSpawns() {
        party(ann, bob, cid);

        server.dispatchCommand(ann, "party ffa sword");

        Match match = matchOf(ann);
        assertEquals(3, match.teams().size());
        Set<Double> xs = List.of(ann, bob, cid).stream().map(player -> player.getLocation().getX()).collect(Collectors.toSet());
        assertEquals(Set.of(5.5, 10.5, 15.5), xs);
    }

    @Test
    void extraSpawnsAreUsedWhenThereIsOnePerPlayer() {
        await(duels.arenas().update(pit.withExtraSpawns(List.of(new Arena.Position(2.5, 64, 2.5, 0, 0),
                new Arena.Position(2.5, 64, 18.5, 0, 0), new Arena.Position(18.5, 64, 18.5, 0, 0)))));
        party(ann, bob, cid);

        server.dispatchCommand(ann, "party ffa sword pit");

        Match match = matchOf(ann);
        Set<Double> zs = List.of(ann, bob, cid).stream().map(player -> player.getLocation().getZ()).collect(Collectors.toSet());
        assertEquals(Set.of(2.5, 18.5), zs);
        assertEquals(match.spawnOf(bob), bob.getLocation());
    }

    @Test
    void partiesFightEachOtherOnceTheOtherLeaderAccepts() {
        party(ann, bob);
        party(cid, dee);

        assertSays(ann, "party duel Cid sword", "Challenged Cid's party");
        assertTrue(messages(cid).stream().anyMatch(line -> line.contains("Ann's party challenged yours")));
        assertSays(dee, "party duelaccept", "Only the party leader can do that.");
        server.dispatchCommand(cid, "party duelaccept Ann");

        Match match = matchOf(dee);
        assertEquals(Set.of(Set.of("Ann", "Bob"), Set.of("Cid", "Dee")), match.teams().stream()
                .map(team -> team.stream().map(player -> player.getName()).collect(Collectors.toSet())).collect(Collectors.toSet()));
    }

    @Test
    void onlyAFreePartyLeaderStartsFights() {
        assertSays(ann, "party split sword", "You're not in a party.");
        party(ann, bob);
        assertSays(bob, "party ffa sword", "Only the party leader can do that.");
        assertTrue(duels.matches().start(bob, cid, duels.kits().get("sword").orElseThrow(), pit));
        assertSays(ann, "party split sword", "Bob is busy");
        assertSays(dee, "party create", "Created a party.");
        assertSays(dee, "party split sword", "at least 2 players");
    }

    @Test
    void friendlyFireIsASetting() {
        party(ann, bob, cid, dee);
        duels.partyFights().startSplit(ann, ids(ann, bob), ids(cid, dee), duels.kits().get("sword").orElseThrow(), null);
        matchOf(ann);
        ticks(20 * duels.settings().countdownSeconds());

        assertTrue(bob.simulateDamage(1, ann).isCancelled());
        setConfig("rules.kit-defaults.friendly-fire", true);
        assertFalse(bob.simulateDamage(1, ann).isCancelled());
    }

    @Test
    void adminsAddAndClearExtraSpawns() {
        TestPlayer admin = join("Admin");
        admin.setOp(true);
        admin.teleport(new Location(arenaWorld, 3.5, 64, 3.5));
        assertSays(admin, "duels arena addspawn pit", "Added spawn 1 to pit");
        admin.teleport(new Location(arenaWorld, 50, 64, 50));
        assertSays(admin, "duels arena addspawn pit", "Stand inside the corners");
        assertSays(admin, "duels arena info pit", "Extra spawns: 1");
        assertSays(admin, "duels arena clearspawns pit", "Removed the extra spawns of pit.");
        assertTrue(duels.arenas().get("pit").orElseThrow().extraSpawns().isEmpty());
    }
}
