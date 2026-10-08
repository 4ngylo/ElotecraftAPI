package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.hud.LeaderboardHolograms.Board;
import me.angylo.elotecraftDuels.hud.LeaderboardHolograms.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockBukkit cannot spawn holograms (DisplayMock.setBillboard), so these boards sit in an unloaded world or
 * are checked before the next second's tick spawns them.
 */
class HologramTest extends DuelsTestBase {

    private static final Position ORIGIN = new Position(0, 64, 0, 0, 0);

    private TestPlayer admin;

    @BeforeEach
    void setUpAdmin() {
        admin = join("Admin");
        admin.setOp(true);
        messages(admin);
    }

    private String text(Board board) {
        return Text.plain(await(duels.holograms().text(board)));
    }

    @Test
    void createListAndDeleteWithoutTicking() {
        swordKit();

        admin.performCommand("duels hologram create Top elo sword");
        Board board = duels.holograms().get("top").orElseThrow();
        assertEquals(Type.ELO, board.type());
        assertEquals("sword", board.kit());
        assertEquals("world", board.world());

        admin.performCommand("duels hologram list");
        assertTrue(messages(admin).stream().anyMatch(line -> line.contains("top · elo sword · world")));

        admin.performCommand("duels hologram delete top");
        assertTrue(duels.holograms().get("top").isEmpty());
    }

    @Test
    void badArgumentsAreRefused() {
        admin.performCommand("duels hologram create top kills");
        admin.performCommand("duels hologram create top elo nokit");
        admin.performCommand("duels hologram create top wins sword");
        admin.performCommand("duels hologram create bad! wins");
        admin.performCommand("duels hologram delete nothing");
        admin.performCommand("duels hologram delete");

        List<String> lines = messages(admin);
        assertTrue(lines.stream().anyMatch(line -> line.contains("There is no kit called 'nokit'")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Names use 1 to 32")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("There is no hologram called 'nothing'")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("'null'")));
        assertTrue(duels.holograms().all().isEmpty());
    }

    @Test
    void boardsShowTheLinesOfTop() {
        TestPlayer steve = join("Steve");
        Board wins = new Board("wins", Type.WINS, null, "nowhere", ORIGIN);
        Board elo = new Board("elo", Type.ELO, null, "nowhere", ORIGIN);
        assertTrue(text(wins).contains("No duels yet"));

        duels.stats().recordResult(admin, steve);
        tickUntil(() -> text(wins).contains("#1 Admin · 1 wins · 0 losses"));
        assertTrue(text(wins).startsWith("⚔ Top duelists"));
        assertTrue(text(elo).startsWith("⚔ Top ranked duelists"));
    }

    @Test
    void boardsShowTheConfiguredNumberOfLines() {
        TestPlayer steve = join("Steve");
        TestPlayer alex = join("Alex");
        Board wins = new Board("wins", Type.WINS, null, "nowhere", ORIGIN);
        duels.stats().recordResult(admin, steve);
        duels.stats().recordResult(alex, steve);
        tickUntil(() -> text(wins).contains("#2"));

        setConfig("holograms.lines", 1);

        assertTrue(text(wins).contains("#1"));
        assertFalse(text(wins).contains("#2"));
    }

    @Test
    void aDeletedKitLeavesAnEmptyBoard() {
        assertTrue(text(new Board("gone", Type.ELO, "gone", "nowhere", ORIGIN)).contains("No duels yet"));
    }

    @Test
    void boardsSurviveARestart() {
        await(duels.holograms().put(new Board("wins", Type.WINS, null, "nowhere", new Position(1.5, 70, -2.5, 0, 0))));

        duels.shutdown();
        duels = Duels.start(plugin);
        await(duels.ready());

        Board board = duels.holograms().get("wins").orElseThrow();
        assertEquals(Type.WINS, board.type());
        assertNull(board.kit());
        assertEquals("nowhere", board.world());
        assertEquals(-2.5, board.position().z());
    }
}
