package me.angylo.elotecraftAPI.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.angylo.elotecraftAPI.CleanupListener;
import me.angylo.elotecraftAPI.util.Events;
import me.angylo.elotecraftAPI.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatInputTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private final List<Boolean> chatCancelled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock api = MockBukkit.createMockPlugin("Api");
        server.getPluginManager().registerEvents(new InputListener(), api);
        server.getPluginManager().registerEvents(new CleanupListener(), api);
        plugin = MockBukkit.createMockPlugin("Shop");
        Events.listen(plugin, AsyncChatEvent.class, EventPriority.MONITOR, false, event -> chatCancelled.add(event.isCancelled()));
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void chat(String message) {
        player.chat(message);
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performOneTick();
    }

    @Test
    void answerCompletesOnMainThreadAndIsHiddenFromChat() {
        AtomicBoolean onMain = new AtomicBoolean();
        CompletableFuture<Optional<String>> answer = ChatInput.ask(plugin, player, "<yellow>Name your shop", TIMEOUT);
        answer.thenAccept(value -> onMain.set(Bukkit.isPrimaryThread()));

        chat("  Steve's Shop ");

        assertEquals("Name your shop", Text.plain(player.nextComponentMessage()));
        assertEquals(Optional.of("Steve's Shop"), answer.getNow(null));
        assertTrue(onMain.get());
        assertEquals(List.of(true), chatCancelled);
        assertFalse(ChatInput.isWaiting(player));
    }

    @Test
    void chatWithoutPromptIsUntouched() {
        chat("hello everyone");

        assertEquals(List.of(false), chatCancelled);
    }

    @Test
    void cancelWordAndTimeoutCompleteEmpty() {
        CompletableFuture<Optional<String>> cancelled = ChatInput.ask(plugin, player, "Name?", TIMEOUT);
        chat("CANCEL");
        CompletableFuture<Optional<String>> timedOut = ChatInput.ask(plugin, player, "Name?", Duration.ofSeconds(1));
        server.getScheduler().performTicks(25);

        assertEquals(Optional.empty(), cancelled.getNow(null));
        assertEquals(Optional.empty(), timedOut.getNow(null));
    }

    @Test
    void newerPromptReplacesOlder() {
        CompletableFuture<Optional<String>> first = ChatInput.ask(plugin, player, "First?", TIMEOUT);
        CompletableFuture<Optional<String>> second = ChatInput.ask(plugin, player, "Second?", TIMEOUT);

        chat("answer");

        assertEquals(Optional.empty(), first.getNow(null));
        assertEquals(Optional.of("answer"), second.getNow(null));
    }

    @Test
    void quitAndPluginDisableCompleteEmpty() {
        CompletableFuture<Optional<String>> quit = ChatInput.ask(plugin, player, "Name?", TIMEOUT);
        player.disconnect();
        PlayerMock other = server.addPlayer();
        CompletableFuture<Optional<String>> disabled = ChatInput.ask(plugin, other, "Name?", TIMEOUT);
        server.getPluginManager().disablePlugin(plugin);

        assertEquals(Optional.empty(), quit.getNow(null));
        assertEquals(Optional.empty(), disabled.getNow(null));
    }
}
