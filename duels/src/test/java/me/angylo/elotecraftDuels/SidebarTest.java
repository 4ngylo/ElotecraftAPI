package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.hud.DuelsSidebar;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sidebar's layouts. MockBukkit cannot show a sidebar, so this checks what one would show. */
class SidebarTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        kit = swordKit();
        arena = readyArena("pit");
    }

    private List<String> lines(Player player) {
        DuelsSidebar.Layout layout = duels.sidebar().layoutFor(player);
        return layout.lines().stream().map(Text::plain).toList();
    }

    private void assertShows(Player player, String... expected) {
        List<String> shown = lines(player);
        for (String line : expected) {
            assertTrue(shown.contains(line), "'" + line + "' not in " + shown);
        }
    }

    private Match startDuel(boolean ranked) {
        assertTrue(duels.matches().start(alex, steve, kit, arena, ranked));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        return match;
    }

    @Test
    void aDuelShowsTheOpponentKitArenaAndTimeLeft() {
        startDuel(false);

        assertEquals("DUEL", Text.plain(duels.sidebar().layoutFor(alex).title()).replace("⚔ ", ""));
        assertShows(alex, "5:00", "Opponent: Steve", "Health: 10❤", "Kit: Sword", "Arena: pit");
        assertTrue(lines(alex).stream().noneMatch(line -> line.startsWith("Rating")));
        ticks(20 * duels.settings().countdownSeconds() + 20 * 3);
        assertShows(alex, "4:57");
    }

    @Test
    void aRankedDuelShowsBothRatings() {
        startDuel(true);

        assertShows(steve, "Opponent: Alex", "Rating: 1000 vs 1000");
    }

    @Test
    void aTeamFightCountsWhoIsLeftAndSpectatorsSeeTheFighters() {
        TestPlayer sam = join("Sam");
        TestPlayer zoe = join("Zoe");
        TestPlayer watcher = join("Watcher");
        assertTrue(duels.matches().start(List.of(List.of(alex, steve), List.of(sam, zoe)), kit, arena, Match.Type.PARTY, false));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());

        sam.simulateDamage(100, alex);

        assertShows(alex, "Your team: 2 left", "Enemies: 1 left");
        assertShows(zoe, "Your team: 1 left", "Enemies: 2 left");
        assertSays(watcher, "duel spectate Alex", "Spectating");
        assertShows(watcher, "Watching: Alex, Steve vs Sam, Zoe", "Kit: Sword");
    }

    @Test
    void theLobbyShowsStatsQueueAndParty() {
        assertShows(alex, "Rating: 1000", "Wins: 0", "Losses: 0", "Win rate: 0%", "Streak: 0 (best 0)",
                "Queue: none", "Party: none", "Dueling now: 0");

        assertSays(alex, "duel queue sword", "queue");
        assertShows(alex, "Queue: Sword (Unranked)");
        assertSays(alex, "duel leave", "left");
        assertSays(alex, "party Steve", "Invited Steve");
        assertSays(steve, "party accept", "joined the party");
        assertShows(alex, "Party: 2 players");
    }

    @Test
    void statsNotLoadedYetShowLoadingAndTheRankComesFromTheLeaderboard() {
        duels.stats().recordResult(alex, steve, "sword", 16);
        server.getScheduler().waitAsyncTasksFinished();
        await(duels.sidebar().refreshRanks());

        assertShows(alex, "Rating: 1016 #1", "Wins: 1");
        assertShows(steve, "Rating: 984 #2", "Losses: 1");

        duels.stats().forget(alex.getUniqueId());
        assertShows(alex, "Wins: ...", "Rating: ... #1");
    }
}
