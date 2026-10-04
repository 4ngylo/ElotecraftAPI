package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke test: runs the demo against the bundled messages.yml and example.yml. */
class ExampleCommandTest {

    private ServerMock server;
    private PlayerMock player;
    private ExampleCommand example;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // MockBukkit.load cannot subclass the final ElotecraftExample; bundled YAML still resolves from the classpath.
        example = ExampleCommand.register(MockBukkit.createMockPlugin("ElotecraftExample"));
        player = server.addPlayer();
        player.setOp(true);
    }

    @AfterEach
    void tearDown() {
        if (example != null) {
            example.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void giveUsesBundledMessagesAndCooldown() {
        server.dispatchCommand(player, "example give 5");
        server.dispatchCommand(player, "ex give 5");

        // Not inventory.contains(): MockBukkit only counts plain items, and these carry PDC data.
        ItemStack diamonds = player.getInventory().getItem(player.getInventory().first(Material.DIAMOND));
        assertEquals(5, diamonds.getAmount());
        assertEquals(5, diamonds.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey("elotecraftexample", "demo_amount"), PersistentDataType.INTEGER));
        assertEquals("Gave you 5 demo diamonds. (total given: 5)", Text.plain(player.nextComponentMessage()));
        assertTrue(Text.plain(player.nextComponentMessage()).startsWith("Wait "));
    }

    @Test
    void invalidInputGetsMessages() {
        server.dispatchCommand(player, "example give 999");
        server.dispatchCommand(player, "example cooldown <red>soon");
        server.dispatchCommand(player, "countdown abc");

        assertEquals("Amount must be a number from 1 to 64.", Text.plain(player.nextComponentMessage()));
        assertEquals("'<red>soon' is not a duration. Try 30s, 5m or 1h30m.", Text.plain(player.nextComponentMessage()));
        assertEquals("Seconds must be a number from 1 to 60.", Text.plain(player.nextComponentMessage()));
    }

    @Test
    void countdownTicksDown() {
        server.dispatchCommand(player, "countdown 2");
        server.getScheduler().performTicks(41);

        assertEquals("2...", Text.plain(player.nextComponentMessage()));
        assertEquals("1...", Text.plain(player.nextComponentMessage()));
        assertEquals("Go!", Text.plain(player.nextComponentMessage()));
    }

    @Test
    void nonOpCannotUseDemo() {
        player.setOp(false);

        server.dispatchCommand(player, "example");

        assertEquals("Only operators can use the demo.", Text.plain(player.nextComponentMessage()));
    }
}
