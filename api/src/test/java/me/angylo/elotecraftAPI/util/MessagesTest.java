package me.angylo.elotecraftAPI.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Lang");
        Path folder = plugin.getDataFolder().toPath();
        Files.createDirectories(folder);
        try (DirectoryStream<Path> old = Files.newDirectoryStream(folder, "messages*.yml")) {
            for (Path file : old) {
                Files.delete(file);
            }
        }
        Files.writeString(folder.resolve("messages.yml"), """
                prefix: "<gold>[Shop]</gold> "
                hello: "<prefix>Hello"
                bye: "Bye"
                papi: "Hi %player_name%"
                """);
        Files.writeString(folder.resolve("messages_es.yml"), """
                hello: "<prefix>Hola"
                """);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void prefixIsAvailableInEveryMessage() {
        Messages messages = new Messages(plugin);

        assertEquals("[Shop] Hello", Text.plain(messages.get("hello")));
    }

    @Test
    void playersGetTheirLanguageWithFallbackPerKey() {
        Messages messages = new Messages(plugin);
        player.setLocale(Locale.forLanguageTag("es-ES"));

        assertEquals("[Shop] Hola", Text.plain(messages.get(player, "hello")));
        assertEquals("Bye", Text.plain(messages.get(player, "bye")));

        player.setLocale(Locale.GERMANY);
        assertEquals("[Shop] Hello", Text.plain(messages.get(player, "hello")));
    }

    @Test
    void sendUsesTheViewersLanguage() {
        Messages messages = new Messages(plugin);
        player.setLocale(Locale.forLanguageTag("es"));

        messages.send(player, "hello");

        assertEquals("[Shop] Hola", Text.plain(player.nextComponentMessage()));
    }

    @Test
    void invalidBundledLanguageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Messages(plugin, "../evil"));
    }

    @Test
    void placeholderValuesAreInsertedAsTextNotTags() {
        TagResolver.Builder tags = TagResolver.builder();
        Map<String, String> values = Map.of(
                "%player_name%", "<click:run_command:'/op me'>Steve",
                "%vault_eco_balance%", "§a100");

        String raw = PlaceholderHook.replace("%player_name% has %vault_eco_balance% and %unknown_thing%",
                token -> values.getOrDefault(token, token), tags);
        Component message = Text.mm(raw, tags.build());

        assertEquals("<papi_0> has <papi_1> and %unknown_thing%", raw);
        assertEquals("<click:run_command:'/op me'>Steve has 100 and %unknown_thing%", Text.plain(message));
        String json = GsonComponentSerializer.gson().serialize(message);
        assertFalse(json.contains("click_event") || json.contains("clickEvent"));
        assertTrue(json.contains("\"color\":\"green\""));
    }

    @Test
    void placeholdersStayAsTypedWithoutPlaceholderApi() {
        Messages messages = new Messages(plugin);

        assertEquals("Hi %player_name%", Text.plain(messages.get(player, "papi")));
    }
}
