package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.util.Events;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class BlockStatsTest {

    private static final long TIMEOUT_MILLIS = 5000;

    private ServerMock server;
    private PluginMock plugin;
    private Messages messages;
    private WorldMock world;
    private BlockStats stats;
    private int nextX;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("ElotecraftExample");
        Files.deleteIfExists(plugin.getDataFolder().toPath().resolve("blocks.db"));
        messages = new Messages(plugin);
        world = server.addSimpleWorld("world");
        stats = new BlockStats(plugin, messages);
    }

    @AfterEach
    void tearDown() {
        if (stats != null) {
            stats.shutdown();
        }
        MockBukkit.unmock();
    }

    private void breakBlocks(PlayerMock player, Material material, int count) {
        for (int i = 0; i < count; i++) {
            Block block = world.getBlockAt(nextX++, 64, 0);
            block.setType(material);
            player.simulateBlockBreak(block);
        }
    }

    /** Ticks the mock server until the future completes; database results are delivered on the main thread. */
    private void await(CompletableFuture<?> future) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!future.isDone()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Block stats query did not complete in time");
            }
            server.getScheduler().performOneTick();
            Thread.onSpinWait();
        }
        future.join();
    }

    private static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            lines.add(Text.plain(line));
        }
        return lines;
    }

    private static List<String> drain(ConsoleCommandSenderMock console) {
        List<String> lines = new ArrayList<>();
        for (Component line = console.nextComponentMessage(); line != null; line = console.nextComponentMessage()) {
            lines.add(Text.plain(line));
        }
        return lines;
    }

    @Test
    void showsTotalsPerBlockTypeMostBrokenFirst() {
        PlayerMock steve = server.addPlayer("Steve");
        breakBlocks(steve, Material.STONE, 3);
        breakBlocks(steve, Material.OAK_LOG, 1);

        await(stats.show(steve, "steve"));

        assertEquals(List.of("⛏ Steve's broken blocks: 4", "  • stone: 3", "  • oak log: 1"), drain(steve));
    }

    @Test
    void countsAddUpAcrossFlushes() {
        PlayerMock steve = server.addPlayer("Steve");
        breakBlocks(steve, Material.DIRT, 2);
        stats.flush();
        breakBlocks(steve, Material.DIRT, 3);

        await(stats.show(steve, "Steve"));

        assertEquals("  • dirt: 5", drain(steve).get(1));
    }

    @Test
    void leaderboardRanksPlayersByTotal() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock alex = server.addPlayer("Alex");
        breakBlocks(steve, Material.STONE, 2);
        breakBlocks(alex, Material.SAND, 5);
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();

        await(stats.showTop(console));

        assertEquals(List.of("⛏ Top miners", "#1 Alex - 5", "#2 Steve - 2"), drain(console));
    }

    @Test
    void cancelledBreaksAreNotCounted() {
        PlayerMock steve = server.addPlayer("Steve");
        Events.listen(plugin, BlockBreakEvent.class, EventPriority.HIGH, false, event -> event.setCancelled(true));
        breakBlocks(steve, Material.STONE, 2);

        await(stats.show(steve, "Steve"));

        assertEquals(List.of("No blocks broken by 'Steve' yet."), drain(steve));
    }

    @Test
    void countsSurviveARestart() {
        PlayerMock steve = server.addPlayer("Steve");
        breakBlocks(steve, Material.STONE, 4);
        stats.shutdown();

        stats = new BlockStats(plugin, messages);
        await(stats.show(steve, "Steve"));

        assertEquals("⛏ Steve's broken blocks: 4", drain(steve).getFirst());
    }
}
