package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.RequestManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duel} and {@code /duels} as players and admins use them. */
class CommandTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        swordKit();
        readyArena("pit");
    }

    private boolean anyMessage(TestPlayer player, String text) {
        return messages(player).stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void challengeAndAcceptStartsADuel() {
        server.dispatchCommand(alex, "duel Steve sword pit");
        assertTrue(anyMessage(steve, "Alex challenged you to a duel!"));
        assertTrue(anyMessage(alex, "Challenged Steve"));

        server.dispatchCommand(steve, "duel accept");
        tickUntil(() -> duels.matches().matchOf(alex).map(match -> match.state() == Match.State.COUNTDOWN).orElse(false));

        assertTrue(duels.matches().isBusy(steve));
        assertTrue(anyMessage(alex, "Steve accepted your duel!"));
    }

    @Test
    void denyTellsTheChallenger() {
        server.dispatchCommand(alex, "duel Steve sword");
        server.dispatchCommand(steve, "duel deny alex");

        assertTrue(anyMessage(alex, "Steve denied your duel."));
        server.dispatchCommand(steve, "duel accept");
        assertTrue(anyMessage(steve, "Nobody has challenged you."));
    }

    @Test
    void cancelTakesBackASentChallenge() {
        TestPlayer third = join("Third");
        alex.addAttachment(plugin, RequestManager.BYPASS_COOLDOWN, true);
        server.dispatchCommand(alex, "duel cancel");
        assertTrue(anyMessage(alex, "You haven't challenged anyone."));
        server.dispatchCommand(alex, "duel Steve sword");
        server.dispatchCommand(alex, "duel Third sword");

        server.dispatchCommand(alex, "duel cancel");
        assertTrue(anyMessage(alex, "You challenged several players"));
        Command duel = server.getCommandMap().getCommand("duel");
        assertEquals(List.of("Steve", "Third"), duel.tabComplete(alex, "duel", new String[]{"cancel", ""}));

        server.dispatchCommand(alex, "duel cancel steve");
        assertTrue(anyMessage(alex, "You took back your challenge to Steve."));
        assertTrue(anyMessage(steve, "Alex took back their duel request."));
        server.dispatchCommand(steve, "duel accept");
        assertTrue(anyMessage(steve, "Nobody has challenged you."));

        server.dispatchCommand(alex, "duel cancel");
        assertTrue(anyMessage(third, "Alex took back their duel request."));
    }

    @Test
    void requestsHaveACooldownAndCannotTargetYourself() {
        TestPlayer third = join("Third");
        server.dispatchCommand(alex, "duel Alex sword");
        server.dispatchCommand(alex, "duel Steve sword");
        server.dispatchCommand(alex, "duel Third sword");

        List<String> lines = messages(alex);
        assertTrue(lines.stream().anyMatch(line -> line.contains("You can't duel yourself.")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Wait ")));
        assertTrue(messages(third).isEmpty());
    }

    @Test
    void requestsExpire() {
        server.dispatchCommand(alex, "duel Steve sword");
        ticks(20 * (int) duels.settings().requestExpiry().toSeconds() + 20);

        assertTrue(anyMessage(alex, "Your duel request to Steve expired."));
    }

    @Test
    void twoPlayersInTheSameQueueArePaired() {
        server.dispatchCommand(alex, "duel queue sword");
        assertFalse(duels.matches().isBusy(alex));
        server.dispatchCommand(steve, "duel queue sword");

        assertTrue(duels.matches().isBusy(alex));
        assertTrue(duels.matches().isBusy(steve));
        assertFalse(duels.matches().matchOf(alex).orElseThrow().isRanked());
    }

    @Test
    void twoPlayersInTheRankedQueueGetARankedDuel() {
        server.dispatchCommand(alex, "duel ranked sword");
        assertTrue(anyMessage(alex, "Joined the Ranked Sword queue."));
        server.dispatchCommand(steve, "duel ranked sword");

        assertTrue(duels.matches().matchOf(alex).orElseThrow().isRanked());
    }

    @Test
    void leaveTakesYouOutOfTheQueue() {
        server.dispatchCommand(alex, "duel queue sword");
        server.dispatchCommand(alex, "duel leave");
        server.dispatchCommand(alex, "duel leave");

        List<String> lines = messages(alex);
        assertTrue(lines.stream().anyMatch(line -> line.contains("You left the Unranked Sword queue.")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("You're not in a queue, an event, a duel or spectating.")));
    }

    @Test
    void rematchChallengesTheLastOpponent() {
        duels.matches().start(alex, steve, duels.kits().get("sword").orElseThrow(), duels.arenas().get("pit").orElseThrow());
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        steve.simulateDamage(100, alex);
        ticks(20 * duels.settings().endDelaySeconds() + 1);
        messages(steve);

        server.dispatchCommand(alex, "duel rematch");
        assertTrue(anyMessage(steve, "Alex challenged you to a duel!"));
        server.dispatchCommand(steve, "duel rematch");

        tickUntil(() -> duels.matches().isBusy(alex));
    }

    @Test
    void statsAndTopShowResults() {
        server.dispatchCommand(alex, "duel stats");
        assertTrue(anyMessage(alex, "Wins 0"));

        server.dispatchCommand(alex, "duel top");
        tickUntil(() -> !messages(alex).isEmpty());

        duels.stats().recordResult(steve, alex, "sword", 16);
        tickUntil(() -> !await(duels.stats().topByElo("sword", 1)).isEmpty());
        server.dispatchCommand(steve, "duel top elo");
        List<String> lines = new ArrayList<>();
        tickUntil(() -> {
            lines.addAll(messages(steve));
            return lines.stream().anyMatch(line -> line.contains("Steve · 1016 rating"));
        });
        assertTrue(lines.getFirst().contains("Top ranked duelists"));
    }

    @Test
    void tabCompletionOffersSubcommandsPlayersKitsAndArenas() {
        Command duel = server.getCommandMap().getCommand("duel");

        List<String> first = duel.tabComplete(alex, "duel", new String[]{""});
        assertTrue(first.containsAll(List.of("accept", "queue", "Steve")));
        assertFalse(first.contains("Alex"));
        assertEquals(List.of("sword"), duel.tabComplete(alex, "duel", new String[]{"Steve", ""}));
        assertEquals(List.of("bet", "pit"), duel.tabComplete(alex, "duel", new String[]{"Steve", "sword", ""}));
        assertEquals(List.of("bet"), duel.tabComplete(alex, "duel", new String[]{"Steve", "sword", "pit", ""}));
        assertEquals(List.of(), duel.tabComplete(alex, "duel", new String[]{"Steve", "sword", "bet", ""}));
    }

    @Test
    void adminsBuildArenasAndKitsInGame() {
        TestPlayer admin = join("Admin");
        admin.setOp(true);
        admin.teleport(new Location(arenaWorld, 100, 64, 100));
        server.dispatchCommand(admin, "duels arena create yard");
        tickUntil(() -> duels.arenas().get("yard").isPresent());
        point(admin, "setspawn yard 1", 102, 64, 102);
        point(admin, "setspawn yard 2", 110, 64, 102);
        point(admin, "setcorner yard 1", 98, 60, 98);
        point(admin, "setcorner yard 2", 115, 75, 115);

        server.getScheduler().waitAsyncTasksFinished();
        Arena yard = duels.arenas().get("yard").orElseThrow();
        assertTrue(yard.isReady(), "problems: " + yard.problems());

        admin.getInventory().addItem(ItemStack.of(Material.BOW), ItemStack.of(Material.ARROW, 16));
        server.dispatchCommand(admin, "duels kit create archer");
        assertTrue(duels.kits().get("archer").orElseThrow().items().stream().anyMatch(item -> item.getType() == Material.BOW));

        server.dispatchCommand(admin, "duels kit setpermission archer duels.kit.archer");
        assertFalse(duels.kits().get("archer").orElseThrow().canUse(alex));
    }

    @Test
    void spectateWatchesARunningDuelAndLeaveStops() {
        TestPlayer viewer = join("Viewer");
        server.dispatchCommand(viewer, "duel spectate Alex");
        assertTrue(anyMessage(viewer, "Alex isn't in a duel."));

        duels.matches().start(alex, steve, duels.kits().get("sword").orElseThrow(), duels.arenas().get("pit").orElseThrow());
        server.dispatchCommand(viewer, "duel spectate Alex");
        tickUntil(() -> viewer.getGameMode() == GameMode.SPECTATOR);
        assertTrue(anyMessage(viewer, "Spectating Alex's duel."));

        server.dispatchCommand(viewer, "duel leave");
        assertFalse(duels.matches().isBusy(viewer));
        assertTrue(anyMessage(viewer, "You stopped spectating."));
    }

    @Test
    void statsHandleUnknownPlayersAndTheConsole() {
        server.dispatchCommand(alex, "duel stats Nobody");
        server.dispatchCommand(server.getConsoleSender(), "duel stats");
        // The offline lookup reads the database, which can take more than a few ticks on slow CI runners.
        List<String> lines = new ArrayList<>();
        tickUntil(() -> {
            lines.addAll(messages(alex));
            return lines.stream().anyMatch(line -> line.contains("No duel stats"));
        });
        server.dispatchCommand(alex, "duel Nobody sword");
        server.dispatchCommand(alex, "duel Steve nokit");
        server.dispatchCommand(alex, "duel Steve sword noarena");

        lines.addAll(messages(alex));
        assertTrue(lines.stream().anyMatch(line -> line.contains("No duel stats for 'Nobody' yet.")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("'Nobody' is not online.")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("There is no kit called 'nokit'.")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("There is no arena called 'noarena'.")));
    }

    @Test
    void playersCannotUseAdminCommands() {
        server.dispatchCommand(alex, "duels arena create nope");

        assertTrue(anyMessage(alex, "You don't have permission to do that."));
        assertTrue(duels.arenas().get("nope").isEmpty());
    }

    private void point(TestPlayer admin, String command, double x, double y, double z) {
        admin.teleport(new Location(arenaWorld, x, y, z));
        server.dispatchCommand(admin, "duels arena " + command);
    }
}
