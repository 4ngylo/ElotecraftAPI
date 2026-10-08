package me.angylo.elotecraftAPI.input;

import me.angylo.elotecraftAPI.CleanupListener;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockBukkit's {@code MenuType} builds no anvil view, so these run on a fake {@link AnvilView} over a mock
 * anvil inventory, with the rename text set by the test. A real client is checked by a smoke test.
 */
class AnvilInputTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private BiFunction<org.bukkit.entity.Player, Component, AnvilView> realFactory;
    /** The anvil last opened, and what the player typed in it. */
    private AnvilView view;
    private Component title;
    private String typed;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock api = MockBukkit.createMockPlugin("Api");
        server.getPluginManager().registerEvents(new InputListener(), api);
        server.getPluginManager().registerEvents(new CleanupListener(), api);
        plugin = MockBukkit.createMockPlugin("Shop");
        player = server.addPlayer();
        realFactory = AnvilInput.viewFactory;
        AnvilInput.viewFactory = this::fakeView;
    }

    @AfterEach
    void tearDown() {
        AnvilInput.viewFactory = realFactory;
        MockBukkit.unmock();
    }

    /** An anvil view answering what {@link AnvilInput} asks of one. */
    private AnvilView fakeView(org.bukkit.entity.Player viewer, Component viewTitle) {
        AnvilInventory inventory = (AnvilInventory) server.createInventory(null, InventoryType.ANVIL);
        title = viewTitle;
        typed = null;
        view = (AnvilView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AnvilView.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getTopInventory" -> inventory;
                    case "getBottomInventory" -> viewer.getInventory();
                    case "getPlayer" -> viewer;
                    case "getType" -> InventoryType.ANVIL;
                    case "getRenameText" -> typed;
                    case "title" -> title;
                    case "getTitle", "getOriginalTitle" -> Text.plain(title);
                    case "close" -> {
                        viewer.closeInventory();
                        yield null;
                    }
                    case "countSlots" -> inventory.getSize() + viewer.getInventory().getSize();
                    case "convertSlot" -> args[0];
                    case "getSlotType" -> InventoryType.SlotType.CONTAINER;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeAnvilView";
                    default -> method.getReturnType() == int.class ? 0 : method.getReturnType() == boolean.class ? false : null;
                });
        return view;
    }

    /** The answer, failing instead of blocking when there is none yet. */
    private static Optional<String> done(CompletableFuture<Optional<String>> answer) {
        assertTrue(answer.isDone(), "no answer yet");
        return answer.join();
    }

    private CompletableFuture<Optional<String>> ask() {
        return AnvilInput.ask(plugin, player, "<gold>Rename the kit", "NAME", TIMEOUT);
    }

    private InventoryClickEvent click(int slot) {
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void opensAnAnvilWithTheTitleAndTheInitialText() {
        ask();

        InventoryView open = player.getOpenInventory();
        assertSame(view, open);
        assertEquals("Rename the kit", Text.plain(title));
        ItemStack item = view.getTopInventory().getItem(AnvilInput.INPUT_SLOT);
        assertEquals(Material.PAPER, item.getType());
        assertEquals("NAME", Text.plain(item.getItemMeta().customName()));
        assertTrue(AnvilInput.isWaiting(player));
    }

    @Test
    void clickingTheResultAnswersWithTheTypedText() {
        CompletableFuture<Optional<String>> answer = ask();
        typed = "  Archer  ";
        PrepareAnvilEvent prepare = new PrepareAnvilEvent(view, null);
        server.getPluginManager().callEvent(prepare);
        assertEquals("Archer", Text.plain(prepare.getResult().getItemMeta().customName()));

        assertTrue(click(AnvilInput.RESULT_SLOT).isCancelled());
        server.getScheduler().performOneTick();

        assertEquals(Optional.of("Archer"), done(answer));
        assertFalse(AnvilInput.isWaiting(player));
        assertFalse(player.getInventory().contains(Material.PAPER));
    }

    @Test
    void anEmptyFieldDoesNotAnswerAndOtherClicksAreCancelled() {
        CompletableFuture<Optional<String>> answer = ask();
        typed = "   ";

        assertTrue(click(AnvilInput.RESULT_SLOT).isCancelled());
        assertTrue(click(AnvilInput.INPUT_SLOT).isCancelled());
        assertTrue(click(30).isCancelled());

        assertFalse(answer.isDone());
    }

    @Test
    void closingCancelsAndGivesNothingBack() {
        CompletableFuture<Optional<String>> answer = ask();

        // What the server fires on Esc; MockBukkit's closeInventory does not for this view.
        server.getPluginManager().callEvent(new InventoryCloseEvent(view));

        assertEquals(Optional.empty(), done(answer));
        assertFalse(AnvilInput.isWaiting(player));
        assertFalse(player.getInventory().contains(Material.PAPER));
        assertTrue(view.getTopInventory().isEmpty());
    }

    @Test
    void timesOutQuitsAndNewerPromptsCancel() {
        CompletableFuture<Optional<String>> first = AnvilInput.ask(plugin, player, "<gold>First", "", Duration.ofSeconds(1));
        server.getScheduler().performTicks(21);
        assertEquals(Optional.empty(), done(first));

        CompletableFuture<Optional<String>> replaced = ask();
        CompletableFuture<Optional<String>> newer = ask();
        assertEquals(Optional.empty(), done(replaced));
        assertFalse(newer.isDone());

        player.disconnect();
        assertEquals(Optional.empty(), done(newer));
    }

    @Test
    void disablingThePluginCancelsItsPrompts() {
        CompletableFuture<Optional<String>> answer = ask();

        server.getPluginManager().disablePlugin(plugin);

        assertEquals(Optional.empty(), done(answer));
    }
}
