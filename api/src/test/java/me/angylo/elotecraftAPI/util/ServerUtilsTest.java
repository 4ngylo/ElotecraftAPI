package me.angylo.elotecraftAPI.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerUtilsTest {

    private ServerMock server;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void itemBuilderAppliesMeta() {
        NamespacedKey key = new NamespacedKey(plugin, "id");
        NamespacedKey model = new NamespacedKey("elotecraft", "ruby_sword");
        ItemBuilder builder = ItemBuilder.of(Material.DIAMOND_SWORD)
                .name("<gold>Ruby")
                .lore("one", "<italic>two")
                .itemModel(model)
                .data(key, PersistentDataType.STRING, "ruby");

        ItemStack item = builder.build();

        assertEquals("Ruby", Text.plain(item.getItemMeta().displayName()));
        assertEquals(TextDecoration.State.FALSE, item.getItemMeta().displayName().decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.TRUE, item.getItemMeta().lore().get(1).decoration(TextDecoration.ITALIC));
        // itemModel is not asserted: MockBukkit's ItemMetaMock copy constructor drops it, so getItemMeta() loses it.
        assertEquals("ruby", item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING));
        assertNotSame(item, builder.build());
    }

    @Test
    void itemBuilderLoreFromStringList() {
        ItemStack item = ItemBuilder.of(Material.STONE).lore(List.of("<gray>first", "second")).build();

        assertEquals(List.of("first", "second"), item.getItemMeta().lore().stream().map(Text::plain).toList());
    }

    @Test
    void itemBuilderRejectsAir() {
        assertThrows(IllegalArgumentException.class, () -> ItemBuilder.of(Material.AIR));
        assertThrows(IllegalArgumentException.class, () -> ItemBuilder.from(new ItemStack(Material.AIR)));
    }

    @Test
    void itemBuilderChangesAfterBuildDoNotTouchEarlierItems() {
        ItemBuilder builder = ItemBuilder.of(Material.STONE).name("First").amount(2);
        ItemStack first = builder.build();

        ItemStack second = builder.name("Second").amount(5).build();

        assertEquals("First", Text.plain(first.getItemMeta().displayName()));
        assertEquals(2, first.getAmount());
        assertEquals("Second", Text.plain(second.getItemMeta().displayName()));
        assertEquals(5, second.getAmount());
    }

    @Test
    void itemBuilderSkullShowsOwner() {
        PlayerMock steve = server.addPlayer("Steve");

        ItemStack head = ItemBuilder.of(Material.PLAYER_HEAD).skull(steve).build();

        // MockBukkit's SkullMeta stores the owner by name and returns an offline-mode UUID, so compare names.
        assertEquals("Steve", ((SkullMeta) head.getItemMeta()).getOwningPlayer().getName());
    }

    @Test
    void itemBuilderSkullTextureSetsTexturesProperty() {
        String texture = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWJjIn19fQ==";

        ItemStack head = ItemBuilder.of(Material.PLAYER_HEAD).skullTexture(texture).build();
        ItemStack same = ItemBuilder.of(Material.PLAYER_HEAD).skullTexture(texture).build();

        PlayerProfile profile = ((SkullMeta) head.getItemMeta()).getPlayerProfile();
        assertEquals(texture, profile.getProperties().iterator().next().getValue());
        assertEquals(profile.getId(), ((SkullMeta) same.getItemMeta()).getPlayerProfile().getId());
    }

    @Test
    void itemBuilderSkullRejectsWrongItemAndBlankTexture() {
        assertThrows(IllegalStateException.class, () -> ItemBuilder.of(Material.STONE).skullTexture("abc"));
        assertThrows(IllegalArgumentException.class, () -> ItemBuilder.of(Material.PLAYER_HEAD).skullTexture(" "));
    }

    @Test
    void itemBuilderFromKeepsOriginalUntouched() {
        ItemStack original = ItemBuilder.of(Material.STONE).name("Original").build();

        ItemStack copy = ItemBuilder.from(original).name("Copy").build();

        assertEquals("Original", Text.plain(original.getItemMeta().displayName()));
        assertEquals("Copy", Text.plain(copy.getItemMeta().displayName()));
    }

    @Test
    void supplyAsyncCompletesOnMainThread() {
        AtomicBoolean onMain = new AtomicBoolean();
        CompletableFuture<Integer> future = Tasks.supplyAsync(plugin, () -> 42);
        CompletableFuture<Void> callback = future.thenAccept(value -> onMain.set(Bukkit.isPrimaryThread()));

        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();

        assertEquals(42, future.join());
        assertTrue(callback.isDone());
        assertTrue(onMain.get());
    }

    @Test
    void supplyAsyncPropagatesFailure() {
        CompletableFuture<Object> future = Tasks.supplyAsync(plugin, () -> {
            throw new IllegalStateException("boom");
        });

        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();

        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void eventsListenAndUnregister() {
        AtomicInteger calls = new AtomicInteger();
        Listener listener = Events.listen(plugin, TestEvent.class, event -> calls.incrementAndGet());

        server.getPluginManager().callEvent(new TestEvent());
        HandlerList.unregisterAll(listener);
        server.getPluginManager().callEvent(new TestEvent());

        assertEquals(1, calls.get());
    }

    @Test
    void configFileRoundTripsAndRefusesToOverwriteBrokenFile() throws IOException {
        ConfigFile file = new ConfigFile(plugin, "data.yml");
        file.get().set("coins", 5);
        file.saveNow();

        assertTrue(file.reload());
        assertEquals(5, file.get().getInt("coins"));

        Path path = plugin.getDataFolder().toPath().resolve("data.yml");
        Files.writeString(path, "coins: [unclosed");
        assertFalse(file.reload());
        assertEquals(5, file.get().getInt("coins"));
        assertTrue(file.save().isCompletedExceptionally());
        file.saveNow();
        assertEquals("coins: [unclosed", Files.readString(path));
    }

    @Test
    void saveLaterFoldsChangesIntoOneDelayedWrite() throws IOException {
        Path path = freshFile("later.yml");
        ConfigFile file = new ConfigFile(plugin, "later.yml");

        file.get().set("coins", 1);
        file.saveLater();
        file.get().set("coins", 2);
        file.saveLater();
        // No waitAsyncTasksFinished() here: MockBukkit ticks until every scheduled task has run.
        server.getScheduler().performTicks(10);
        assertFalse(Files.exists(path));

        server.getScheduler().performTicks(15);
        server.getScheduler().waitAsyncTasksFinished();
        assertEquals("coins: 2", Files.readString(path).strip());
    }

    @Test
    void saveNowCancelsPendingSaveLater() throws IOException {
        Path path = freshFile("now.yml");
        ConfigFile file = new ConfigFile(plugin, "now.yml");

        file.get().set("coins", 1);
        file.saveLater();
        file.get().set("coins", 2);
        file.saveNow();
        assertEquals("coins: 2", Files.readString(path).strip());

        Files.delete(path);
        server.getScheduler().performTicks(30);
        server.getScheduler().waitAsyncTasksFinished();
        assertFalse(Files.exists(path));
    }

    @Test
    void olderAsyncSnapshotNeverOverwritesNewerSave() throws IOException {
        Path path = freshFile("order.yml");
        ConfigFile file = new ConfigFile(plugin, "order.yml");

        file.get().set("coins", 1);
        CompletableFuture<Void> older = file.save();
        file.get().set("coins", 2);
        file.saveNow();
        server.getScheduler().waitAsyncTasksFinished();

        assertTrue(older.isDone());
        assertEquals("coins: 2", Files.readString(path).strip());
    }

    private Path freshFile(String name) throws IOException {
        Path path = plugin.getDataFolder().toPath().resolve(name);
        Files.deleteIfExists(path);
        return path;
    }

    @Test
    void messagesWithoutPlaceholdersAreCachedUntilReload() throws IOException {
        Path file = writeMessages("hello: \"<green>Hi\"\n");
        Messages messages = new Messages(plugin);

        Component first = messages.get("hello");
        Files.writeString(file, "hello: \"<red>Bye\"\n");

        assertSame(first, messages.get("hello"));
        assertTrue(messages.reload());
        assertEquals("Bye", Text.plain(messages.get("hello")));
    }

    @Test
    void messagesWithPlaceholdersAreNotCached() throws IOException {
        writeMessages("greet: \"Hi <name>\"\n");
        Messages messages = new Messages(plugin);

        assertEquals("Hi Steve", Text.plain(messages.get("greet", Placeholder.unparsed("name", "Steve"))));
        assertEquals("Hi Alex", Text.plain(messages.get("greet", Placeholder.unparsed("name", "Alex"))));
    }

    private Path writeMessages(String yaml) throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("messages.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void messagesParseKnownAndFallBackOnMissing() throws IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.writeString(plugin.getDataFolder().toPath().resolve("messages.yml"), "hello: \"<green>Hi <name>\"\n");
        Messages messages = new Messages(plugin);

        Component hello = messages.get("hello", Placeholder.unparsed("name", "<red>Steve"));

        assertEquals("Hi <red>Steve", Text.plain(hello));
        assertEquals("missing.key", Text.plain(messages.get("missing.key")));
    }

    public static final class TestEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }
}
