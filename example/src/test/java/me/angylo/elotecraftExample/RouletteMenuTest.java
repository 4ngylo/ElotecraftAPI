package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouletteMenuTest {

    private static final int CENTER_SLOT = 13;
    private static final long WHOLE_SPIN_TICKS = 400;

    private ServerMock server;
    private PluginMock plugin;
    private Messages messages;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("ElotecraftExample");
        messages = new Messages(plugin);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spinStopsOnCenterPrizeAndGivesIt() {
        RouletteMenu roulette = new RouletteMenu(plugin, messages, player, new Random(7));
        roulette.start();
        ItemStack[] before = player.getInventory().getContents().clone();

        server.getScheduler().performTicks(WHOLE_SPIN_TICKS);

        ItemStack shown = player.getOpenInventory().getTopInventory().getItem(CENTER_SLOT);
        ItemStack received = Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull).findFirst().orElseThrow();
        assertTrue(Arrays.stream(before).allMatch(Objects::isNull));
        assertEquals(shown.getType(), received.getType());
        assertEquals(shown.getAmount(), received.getAmount());

        assertTrue(Text.plain(player.nextComponentMessage()).contains("spinning"));
        assertTrue(Text.plain(player.nextComponentMessage()).startsWith("✦ You won "));
    }

    @Test
    void secondSpinIsRejectedWhileFirstRuns() {
        new RouletteMenu(plugin, messages, player, new Random(1)).start();
        player.nextComponentMessage();

        new RouletteMenu(plugin, messages, player, new Random(2)).start();

        assertEquals("Your roulette is still spinning!", Text.plain(player.nextComponentMessage()));
        server.getScheduler().performTicks(WHOLE_SPIN_TICKS);
    }
}
