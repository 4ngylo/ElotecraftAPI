package me.angylo.elotecraftAPI.hud;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import me.angylo.elotecraftAPI.CleanupListener;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.getPluginManager().registerEvents(new CleanupListener(), MockBukkit.createMockPlugin("Api"));
        plugin = MockBukkit.createMockPlugin("Game");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private boolean showing(BossBar bar) {
        return StreamSupport.stream(player.activeBossBars().spliterator(), false).anyMatch(active -> active == bar);
    }

    @Test
    void timedBossBarDrainsThenHides() {
        BossBar bar = Bossbars.timed(plugin, player, "<red>Boom", BossBar.Color.RED, Duration.ofSeconds(1));
        assertTrue(showing(bar));
        assertEquals("Boom", Text.plain(bar.name()));

        server.getScheduler().performTicks(10);
        assertEquals(0.5f, bar.progress(), 0.11f);

        server.getScheduler().performTicks(12);
        assertFalse(showing(bar));
    }

    @Test
    void bossBarsHideWhenTheirPluginDisables() {
        BossBar bar = Bossbars.timed(plugin, player, "<red>Boom", BossBar.Color.RED, Duration.ofMinutes(1));

        server.getPluginManager().disablePlugin(plugin);

        assertFalse(showing(bar));
    }

    @Test
    void sidebarShowsLinesTopToBottomWithoutNumbers() {
        Sidebar.show(plugin, player, "<gold>Elotecraft").lines("<gray>Coins: 5", "", "play.elotecraft.net");

        Scoreboard board = player.getScoreboard();
        Objective objective = board.getObjective(DisplaySlot.SIDEBAR);
        assertEquals("Elotecraft", Text.plain(objective.displayName()));
        assertEquals(NumberFormat.blank(), objective.numberFormat());
        assertEquals(3, objective.getScore("line0").getScore());
        assertEquals(1, objective.getScore("line2").getScore());
        assertEquals("Coins: 5", Text.plain(objective.getScore("line0").customName()));
    }

    @Test
    void fewerLinesRemoveTheRest() {
        Sidebar sidebar = Sidebar.show(plugin, player, "Title").lines("a", "b", "c");

        sidebar.lines("only");

        assertEquals(List.of("line0"), player.getScoreboard().getEntries().stream().sorted().toList());
    }

    @Test
    void hideAndPluginDisableRestoreTheMainScoreboard() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Sidebar.show(plugin, player, "Title").hide();
        assertSame(main, player.getScoreboard());

        Sidebar.show(plugin, player, "Title");
        server.getPluginManager().disablePlugin(plugin);
        assertSame(main, player.getScoreboard());
    }

    @Test
    void rejectsMoreThanFifteenLines() {
        Sidebar sidebar = Sidebar.show(plugin, player, "Title");

        assertThrows(IllegalArgumentException.class, () -> sidebar.lines(Collections.nCopies(16, "x").toArray(String[]::new)));
    }
}
