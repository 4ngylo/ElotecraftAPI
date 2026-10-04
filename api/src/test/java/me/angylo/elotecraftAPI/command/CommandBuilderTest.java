package me.angylo.elotecraftAPI.command;

import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBuilderTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private ConsoleCommandSenderMock console;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Shop");
        player = server.addPlayer();
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Command registerShop() {
        return CommandBuilder.create("shop")
                .aliases("s")
                .sub("give", null, (sender, args) -> calls.add("give " + String.join(" ", args)),
                        (sender, args) -> List.of("10", "64"))
                .sub("admin", "shop.admin", (sender, args) -> calls.add("admin"))
                .playerSub("buy", null, (buyer, args) -> calls.add("buy " + buyer.getName()))
                .register(plugin);
    }

    @Test
    void routesToSubcommandWithRemainingArgs() {
        registerShop();

        server.dispatchCommand(player, "shop GIVE 64 diamonds");
        server.dispatchCommand(player, "s give 1");

        assertEquals(List.of("give 64 diamonds", "give 1"), calls);
    }

    @Test
    void unknownSubcommandShowsOnlyAllowedUsage() {
        registerShop();

        server.dispatchCommand(player, "shop nope");

        assertEquals("Usage:", plain(player.nextComponentMessage()));
        assertEquals("/shop give", plain(player.nextComponentMessage()));
        assertEquals("/shop buy", plain(player.nextComponentMessage()));
        assertNull(player.nextComponentMessage());
        assertTrue(calls.isEmpty());
    }

    @Test
    void subPermissionIsEnforced() {
        registerShop();

        server.dispatchCommand(player, "shop admin");
        assertEquals("You do not have permission to do that.", plain(player.nextComponentMessage()));
        assertTrue(calls.isEmpty());

        player.addAttachment(plugin, "shop.admin", true);
        server.dispatchCommand(player, "shop admin");
        assertEquals(List.of("admin"), calls);
    }

    @Test
    void rootPermissionIsEnforced() {
        CommandBuilder.create("secret").permission("secret.use")
                .executes((sender, args) -> calls.add("secret"))
                .register(plugin);

        server.dispatchCommand(player, "secret");

        assertEquals("You do not have permission to do that.", plain(player.nextComponentMessage()));
        assertTrue(calls.isEmpty());
    }

    @Test
    void playerSubRejectsConsole() {
        registerShop();

        server.dispatchCommand(console, "shop buy");
        server.dispatchCommand(player, "shop buy");

        assertEquals("Only players can use this command.", plain(console.nextComponentMessage()));
        assertEquals(List.of("buy " + player.getName()), calls);
    }

    @Test
    void rootHandlerRunsWithoutArgs() {
        CommandBuilder.create("menu").sub("open", null, (sender, args) -> calls.add("open"))
                .executes((sender, args) -> calls.add("root"))
                .register(plugin);

        server.dispatchCommand(player, "menu");
        server.dispatchCommand(player, "menu open");

        assertEquals(List.of("root", "open"), calls);
    }

    @Test
    void tabCompleteFiltersByPrefixAndPermission() {
        Command command = registerShop();

        assertEquals(List.of("give", "admin", "buy"), command.tabComplete(console, "shop", new String[]{""}));
        assertEquals(List.of("give", "buy"), command.tabComplete(player, "shop", new String[]{""}));
        assertEquals(List.of(), command.tabComplete(player, "shop", new String[]{"a"}));
        assertEquals(List.of("10", "64"), command.tabComplete(player, "shop", new String[]{"give", ""}));
    }

    @Test
    void disabledOwnerDoesNotRunHandler() {
        registerShop();
        server.getPluginManager().disablePlugin(plugin);

        server.dispatchCommand(player, "shop give 1");

        assertEquals("This command is currently unavailable.", plain(player.nextComponentMessage()));
        assertTrue(calls.isEmpty());
    }

    @Test
    void rejectsInvalidAndDuplicateNames() {
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.create("Shop"));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.create("my shop"));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilder.create("shop")
                .sub("give", null, (sender, args) -> { })
                .sub("give", null, (sender, args) -> { }));
    }

    private static String plain(Component component) {
        return component == null ? null : Text.plain(component);
    }
}
