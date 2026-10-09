package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.stats.Seasons;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duels season}: looking at seasons, planning their end, auto end and its warnings, names and exports. */
class SeasonCommandsTest extends DuelsTestBase {

    private TestPlayer admin;
    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void players() {
        admin = join("Admin");
        admin.setOp(true);
        alex = join("Alex");
        steve = join("Steve");
        swordKit();
        readyArena("pit");
    }

    /** Alex beats Steve in a ranked sword duel, and waits for it to be stored. */
    private void rankedDuel() {
        duels.stats().recordResult(alex, steve, "sword", 20);
        tickUntil(() -> await(duels.seasons().ratings(duels.seasons().current())).size() == 2);
    }

    private void endSeason() {
        int season = duels.seasons().current();
        assertSays(admin, "duels season end", "This ends season " + season);
        assertSays(admin, "duels season end confirm", "Season " + season + " ended");
    }

    private void plan(long endsAt) {
        await(duels.seasons().schedule(endsAt));
    }

    @Test
    void theRunningSeasonShowsItsStartPlanAndLeaders() {
        rankedDuel();

        assertSays(admin, "duels season", "Season 1 (season 1)");
        assertSays(admin, "duels season", "since starts were kept");
        assertSays(admin, "duels season", "Planned end: none");
        assertSays(admin, "duels season", "Rated players 2 · ranked duels 1");
        assertSays(admin, "duels season", "#1 Alex · 1020 rating");
    }

    @Test
    void scheduleSetsAndClearsThePlannedEnd() {
        assertSays(admin, "duels season schedule 30", "won't end by itself");
        long planned = duels.seasons().info().endsAt() - System.currentTimeMillis();
        assertTrue(Math.abs(planned - Duration.ofDays(30).toMillis()) < Duration.ofMinutes(1).toMillis());
        assertSays(admin, "duels season", "(in 29d 23h)");

        assertSays(admin, "duels season schedule 2000-01-01", "That is not in the future");
        assertSays(admin, "duels season schedule soon", "Use /duels season schedule");
        assertSays(admin, "duels season schedule off", "has no planned end now");
        assertFalse(duels.seasons().info().planned());
    }

    @Test
    void autoEndEndsTheSeasonAtItsPlannedEndAndCarriesOn() {
        setConfig("seasons.default-length-days", 10);
        rankedDuel();
        assertSays(admin, "duels season auto on", "end by itself");
        plan(System.currentTimeMillis() + 500);

        tickUntil(() -> duels.seasons().current() == 2);

        Seasons.Info next = duels.seasons().info();
        assertTrue(next.autoEnd());
        assertTrue(next.startKnown());
        assertTrue(Math.abs(next.endsAt() - next.startedAt() - Duration.ofDays(10).toMillis()) < 1000);
        assertEquals(2, await(duels.seasons().ratings(1)).size());
    }

    @Test
    void withoutAutoEndAPassedPlanOnlyTellsAdmins() {
        plan(System.currentTimeMillis() - 1000);
        ticks(60);

        assertEquals(1, duels.seasons().current());
        messages(admin);
        server.getPluginManager().callEvent(new PlayerJoinEvent(admin, Component.empty()));
        assertTrue(messages(admin).stream().anyMatch(line -> line.contains("Season 1 is past its planned end")));
        server.getPluginManager().callEvent(new PlayerJoinEvent(alex, Component.empty()));
        assertTrue(messages(alex).stream().noneMatch(line -> line.contains("past its planned end")));
        assertSays(admin, "duels season", "(passed)");
    }

    @Test
    void warningsGoOutOnceAndOnlyTheShortestPassed() {
        assertSays(admin, "duels season auto on", "Seasons now end by itself");
        plan(System.currentTimeMillis() + Duration.ofMinutes(5).toMillis());
        messages(admin);
        messages(alex);

        ticks(40);

        List<String> adminLines = messages(admin);
        assertEquals(1, adminLines.stream().filter(line -> line.contains("ends by itself in")).count(), adminLines.toString());
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Season 1 ends in")));
        ticks(40);
        assertTrue(messages(admin).stream().noneMatch(line -> line.contains("ends by itself in")));
    }

    @Test
    void endedSeasonsAreListedComparedAndLookedInto() {
        rankedDuel();
        endSeason();
        duels.stats().recordResult(steve, alex, "sword", 10);
        tickUntil(() -> await(duels.seasons().ratings(2)).size() == 2);

        assertSays(admin, "duels season list", "#1 Season 1");
        assertSays(admin, "duels season list", "2 players, 1 duels");
        assertSays(admin, "duels season info 1", "Season 1 (season 1, ended)");
        assertSays(admin, "duels season info 1", "#1 Alex · 1020 rating");
        assertSays(admin, "duels season info 2", "1 to 1");
        assertSays(admin, "duels season top 1 sword", "#1 Alex · 1020 rating");
        assertSays(admin, "duels season top", "#1 Steve · 1010 rating");
        assertSays(admin, "duels season player Alex 1", "Sword 1020");
        assertSays(admin, "duels season player Alex", "Sword 990");
        assertSays(admin, "duels season player Nobody", "Nobody has no ratings in season 2");
        assertSays(admin, "duels season compare 1 2", "Season 1 vs season 2");
        assertSays(admin, "duels season compare 1 2", "Average rating 1000 vs 1000");
        assertSays(admin, "duels season kits 1", "Sword 1 duels, 2 players");
        assertSays(admin, "duels season compare 1 9", "Use a season from 1 to 2");
    }

    @Test
    void divisionsAndThePreviewShowWhatAnEndWouldPay() {
        setConfig("ranked.divisions", List.of(Map.of("name", "Bronze", "min", 0),
                Map.of("name", "Gold", "min", 1010, "season-reward", Map.of("commands", List.of("say <player>")))));
        rankedDuel();

        assertSays(admin, "duels season divisions", "Gold 1 (season reward)");
        assertSays(admin, "duels season end preview", "would archive 2 ratings");
        assertSays(admin, "duels season end preview", "1 of 2 rated players would get a season reward");
        assertEquals(1, duels.seasons().current());
    }

    @Test
    void seasonsCanBeNamed() {
        rankedDuel();
        endSeason();

        assertSays(admin, "duels season name 1 <gold>The opening", "Season 1 is now called The opening");
        assertSays(admin, "duels season list", "#1 The opening");
        assertSays(admin, "duels season name 2 Second", "Season 2 is now called Second");
        assertEquals("Second", duels.seasons().info().name());
        assertSays(admin, "duels season name 2 off", "no longer has a name");
        assertSays(admin, "duels season name 7 x", "Use a season from 1 to 2");
    }

    @Test
    void exportWritesTheSeasonsRatings() throws IOException {
        rankedDuel();

        assertSays(admin, "duels season export 1", "Wrote seasons/season-1.csv (2 ratings)");

        List<String> lines = Files.readAllLines(plugin.getDataFolder().toPath().resolve("seasons").resolve("season-1.csv"));
        assertEquals("player,uuid,kit,elo,peak,wins,losses", lines.getFirst());
        assertTrue(lines.get(1).startsWith("Alex," + alex.getUniqueId() + ",sword,1020,1020,1,0"), lines.toString());
    }

    @Test
    void thePlanAndAutoEndSurviveARestart() {
        assertSays(admin, "duels season auto on", "Seasons now end by itself");
        plan(System.currentTimeMillis() + Duration.ofDays(3).toMillis());
        long endsAt = duels.seasons().info().endsAt();
        duels.shutdown();

        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());
        await(duels.seasons().ready());

        assertEquals(endsAt, duels.seasons().info().endsAt());
        assertTrue(duels.seasons().info().autoEnd());
    }

    @Test
    void aDatabaseFromBeforeSeasonInfoStartsTheSeasonWhenTheLastOneEnded() throws SQLException {
        rankedDuel();
        endSeason();
        long ended = await(duels.seasons().ended()).getFirst().endedAt();
        duels.shutdown();
        File file = new File(plugin.getDataFolder(), "duels.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE duels_season_info");
        }

        duels = Duels.start(plugin, worldEdit);
        await(duels.seasons().ready());

        assertEquals(2, duels.seasons().current());
        assertEquals(ended, duels.seasons().info().startedAt());
        assertTrue(duels.seasons().info().startKnown());
    }

    @Test
    void playersSeeTheSeasonAndTheirRating() {
        rankedDuel();
        plan(System.currentTimeMillis() + Duration.ofDays(5).toMillis());

        assertSays(alex, "duel season", "ends in 4d 23h");
        assertSays(alex, "duel season", "Your rating: 1020");
    }

    @Test
    void theAdminHubOpensTheSeasonMenu() {
        server.dispatchCommand(admin, "duels");
        assertEquals("Admin", menuTitle(admin));

        clickNamed(admin, "Season");
        assertEquals("Admin › Season", menuTitle(admin));
        clickNamed(admin, "Ends by itself");

        tickUntil(() -> duels.seasons().info().autoEnd());
    }

    @Test
    void seasonsWithoutRatingsEndOneAfterAnotherAndStayEndedAfterARestart() {
        endSeason();
        endSeason();
        assertEquals(3, duels.seasons().current());
        duels.shutdown();

        duels = Duels.start(plugin, worldEdit);
        await(duels.seasons().ready());

        assertEquals(3, duels.seasons().current());
    }

    @Test
    void autoEndWaitsForRankedDuels() {
        assertSays(admin, "duels season auto on", "end by itself");
        duels.queues().toggle(alex, duels.kits().get("sword").orElseThrow(), true);
        duels.queues().toggle(steve, duels.kits().get("sword").orElseThrow(), true);
        assertTrue(duels.matches().matchOf(alex).orElseThrow().isRanked());
        plan(System.currentTimeMillis() - 1000);

        ticks(60);
        assertEquals(1, duels.seasons().current());

        duels.matches().leave(alex);
        tickUntil(() -> duels.seasons().current() == 2);
    }

    @Test
    void exportPathsAreNumbersOnly() {
        assertSays(admin, "duels season export ../x", "Use a season from 1 to 1");
        assertFalse(Files.exists(Path.of(plugin.getDataFolder().getPath(), "x.csv")));
    }

    @Test
    void lookingAtSeasonsNeedsLessThanManagingThem() {
        TestPlayer viewer = join("Viewer");
        viewer.addAttachment(plugin, "duels.staff", true);
        viewer.addAttachment(plugin, "duels.admin.season", true);

        assertSays(viewer, "duels season list", "No season has ended yet.");
        assertSays(viewer, "duels season end", "You don't have permission");
        assertSays(viewer, "duels season schedule 30", "You don't have permission");
        assertSays(viewer, "duels season auto on", "You don't have permission");

        viewer.addAttachment(plugin, "duels.admin.season.manage", true);
        assertSays(viewer, "duels season schedule 30", "won't end by itself");
    }
}
