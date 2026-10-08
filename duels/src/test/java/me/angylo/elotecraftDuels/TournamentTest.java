package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.event.HostedEvent;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tournament and sumo events, and events the server hosts on a schedule. */
class TournamentTest extends DuelsTestBase {

    private TestPlayer ann;
    private TestPlayer bob;
    private TestPlayer cid;
    private TestPlayer dee;
    private final List<String> prizes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ann = join("Ann");
        bob = join("Bob");
        cid = join("Cid");
        dee = join("Dee");
        swordKit();
        readyArena("pit");
        server.getCommandMap().register("test", new Command("eventprize") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String @NotNull [] args) {
                prizes.add(String.join(" ", args));
                return true;
            }
        });
        setConfig("events.reward.commands", List.of("eventprize <winner> <host> <kit> <arena>"));
    }

    /** A second ready arena, 100 blocks along from the first. */
    private void secondArena() {
        await(duels.arenas().create("pit2", new Location(arenaWorld, 100, 64, 0)));
        Arena arena = duels.arenas().get("pit2").orElseThrow()
                .withSpawn(1, new Position(105.5, 64, 5.5, 0, 0))
                .withSpawn(2, new Position(115.5, 64, 5.5, 180, 0))
                .withCorner(1, new Position(100, 60, 0, 0, 0))
                .withCorner(2, new Position(120, 80, 20, 0, 0));
        await(duels.arenas().update(arena));
    }

    /** Ann hosts an event in {@code mode} that everyone joins, then starts it. */
    private void hostAndStart(HostedEvent.Mode mode) {
        assertSays(ann, "event host sword", "You're hosting a Sword event");
        while (duels.events().hostedBy(ann).orElseThrow().mode() != mode) {
            duels.events().toggleMode(ann);
        }
        for (TestPlayer player : List.of(bob, cid, dee)) {
            assertSays(player, "event join Ann", "You joined Ann's event");
        }
        assertSays(ann, "event start", "Ann's tournament begins: 4 players");
    }

    /** Waits until {@code count} fights are under way and returns them. */
    private List<Match> fights(int count) {
        tickUntil(() -> duels.matches().running().stream().filter(match -> match.state() == Match.State.FIGHTING).count() == count);
        return duels.matches().running().stream().filter(match -> match.state() == Match.State.FIGHTING).toList();
    }

    /** The first fighter kills the second; returns the winner once the fight is over. */
    private Player win(Match match) {
        Player winner = match.first();
        ((TestPlayer) match.second()).simulateDamage(100, winner);
        tickUntil(() -> !duels.matches().running().contains(match));
        return winner;
    }

    @Test
    void roundsOfFightsAtOnceEndWithAChampionWhoGetsTheReward() {
        secondArena();
        TestPlayer outsider = join("Eve");
        hostAndStart(HostedEvent.Mode.TOURNAMENT);
        assertTrue(messages(dee).stream().anyMatch(line -> line.contains("Round 1 · 4 players left")));

        List<Match> round1 = fights(2);
        assertTrue(round1.stream().allMatch(match -> match.type() == Match.Type.EVENT && match.fighters().size() == 2));
        Player loser = round1.getFirst().second();
        Player first = win(round1.getFirst());
        assertFalse(duels.events().isWaiting(loser));
        assertTrue(duels.events().isWaiting(first));
        assertSays((TestPlayer) first, "duel queue sword", "already in a duel, a queue, an event");
        assertTrue(prizes.isEmpty());
        Player second = win(round1.get(1));

        Match fin = fights(1).getFirst();
        assertEquals(Set.of(first, second), Set.copyOf(fin.fighters()));
        Player champion = win(fin);

        assertTrue(messages(outsider).stream().anyMatch(line -> line.contains(champion.getName() + " won Ann's tournament (4 players · Sword)")));
        assertEquals(1, prizes.size());
        assertTrue(prizes.getFirst().startsWith(champion.getName() + " Ann sword pit"));
        tick();
        assertFalse(duels.events().isWaiting(champion));
        assertEquals(0, duels.stats().cached(champion.getUniqueId()).orElseThrow().wins());
    }

    @Test
    void sumoRunsOneFightAtATimeWhileTheOthersWatch() {
        secondArena();
        hostAndStart(HostedEvent.Mode.SUMO);

        Match fight = fights(1).getFirst();
        List<Player> waiting = List.of(ann, bob, cid, dee).stream().filter(player -> !fight.isFighter(player)).map(Player.class::cast).toList();
        tickUntil(() -> waiting.stream().allMatch(fight::isSpectator));
        win(fight);

        Match next = fights(1).getFirst();
        assertTrue(waiting.stream().allMatch(next::isFighter));
    }

    /** {@code winner} kills the other fighter of {@code match}; returns once the fight is over. */
    private void winBy(Match match, Player winner) {
        ((TestPlayer) match.opponentOf(winner)).simulateDamage(100, winner);
        tickUntil(() -> !duels.matches().running().contains(match));
    }

    @Test
    void doubleEliminationKeepsPlayersInAfterOneLossUntilAChampion() {
        secondArena();
        hostAndStart(HostedEvent.Mode.DOUBLE);
        List<Match> round1 = fights(2);
        List<Player> losers = round1.stream().map(Match::second).toList();
        round1.forEach(this::win);

        assertTrue(losers.stream().allMatch(duels.events()::isWaiting));
        assertTrue(messages((TestPlayer) losers.getFirst()).stream().anyMatch(line -> line.contains("One more loss and you're out")));
        // 4 players out after 2 losses: 7 fights at most (8 with a final played again).
        for (int fight = 0; fight < 8 && prizes.isEmpty(); fight++) {
            tickUntil(() -> !prizes.isEmpty() || duels.matches().running().stream().anyMatch(match -> match.state() == Match.State.FIGHTING));
            duels.matches().running().stream().filter(match -> match.state() == Match.State.FIGHTING).findFirst().ifPresent(this::win);
        }
        assertEquals(1, prizes.size());
    }

    @Test
    void aDoubleEliminationFinalIsPlayedAgainWhenTheUnbeatenFinalistLoses() {
        assertSays(ann, "event host sword", "You're hosting");
        while (duels.events().hostedBy(ann).orElseThrow().mode() != HostedEvent.Mode.DOUBLE) {
            duels.events().toggleMode(ann);
        }
        assertSays(bob, "event join Ann", "You joined");
        assertSays(ann, "event start", "Ann's tournament begins: 2 players");

        winBy(fights(1).getFirst(), ann);
        winBy(fights(1).getFirst(), bob);
        assertTrue(duels.events().isWaiting(ann) && duels.events().isWaiting(bob));
        Match decider = fights(1).getFirst();
        assertEquals(Set.of(ann, bob), Set.copyOf(decider.fighters()));
        winBy(decider, bob);

        tickUntil(() -> !prizes.isEmpty());
        assertTrue(prizes.getFirst().startsWith("Bob Ann sword"));
    }

    @Test
    void aFightWithoutAWinnerIsPlayedAgainThenDecidedAtRandom() {
        hostAndStart(HostedEvent.Mode.TOURNAMENT);
        Match fight = fights(1).getFirst();
        Set<Player> pair = Set.copyOf(fight.fighters());

        assertTrue(duels.matches().stop(fight.first()));
        tickUntil(() -> !duels.matches().running().contains(fight));
        Match replay = fights(1).getFirst();

        assertEquals(pair, Set.copyOf(replay.fighters()));
        assertTrue(messages((TestPlayer) fight.first()).stream().anyMatch(line -> line.contains("the fight is played again")));

        assertTrue(duels.matches().stop(replay.first()));
        tickUntil(() -> !duels.matches().running().contains(replay));
        Match next = fights(1).getFirst();
        assertFalse(pair.equals(Set.copyOf(next.fighters())));
        assertEquals(1, pair.stream().filter(duels.events()::isWaiting).count());
    }

    @Test
    void anOddPlayerOutGoesThroughAndAQuitterLoses() {
        dee.disconnect();
        assertSays(ann, "event host sword", "You're hosting");
        duels.events().toggleMode(ann);
        duels.events().toggleMode(ann);
        assertSays(bob, "event join Ann", "You joined");
        assertSays(cid, "event join Ann", "You joined");
        server.dispatchCommand(ann, "event start");
        tick();

        Match fight = fights(1).getFirst();
        TestPlayer bye = List.of(ann, bob, cid).stream().filter(player -> !fight.isFighter(player)).findFirst().orElseThrow();
        assertTrue(messages(bye).stream().anyMatch(line -> line.contains("No opponent for you this round")));
        // The waiting player may watch a fight of their tournament.
        assertSays(bye, "duel spectate " + fight.first().getName(), "Spectating");
        assertSays(bye, "duel leave", "You stopped spectating");
        assertTrue(duels.events().isWaiting(bye));

        Player stays = fight.first();
        ((TestPlayer) fight.second()).disconnect();
        tickUntil(() -> !duels.matches().running().contains(fight));

        Match fin = fights(1).getFirst();
        assertTrue(fin.isFighter(stays) && fin.isFighter(bye));
        win(fin);
        assertEquals(1, prizes.size());
    }

    @Test
    void theServerHostsScheduledEvents() {
        setConfig("events.schedule", List.of(Map.of("at", "20:00", "kit", "sword", "mode", "tournament"),
                Map.of("at", 1230, "kit", "sword"), Map.of("at", "noon", "kit", "sword")));
        assertEquals(List.of(LocalTime.of(20, 0), LocalTime.of(20, 30)),
                duels.settings().events().schedule().stream().map(entry -> entry.at()).toList());
        setConfig("events.wait-time", "10s");

        duels.events().runSchedule(LocalTime.of(20, 0, 15));
        assertTrue(messages(dee).stream().anyMatch(line -> line.contains("Server is hosting a Sword event")));
        assertSays(bob, "event join server", "You joined Server's event (1/16)");
        assertSays(cid, "event join Server", "You joined Server's event (2/16)");
        assertFalse(duels.events().hostScheduled(duels.settings().events().schedule().getFirst()));

        ticks(20 * 11);
        assertTrue(messages(bob).stream().anyMatch(line -> line.contains("Server's tournament begins: 2 players")));
        Match fin = fights(1).getFirst();
        Player champion = win(fin);
        assertTrue(prizes.getFirst().startsWith(champion.getName() + " Server sword pit"));
    }
}
