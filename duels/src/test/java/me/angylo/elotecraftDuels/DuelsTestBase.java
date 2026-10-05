package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.CleanupListener;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.kit.Kit;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/** Starts the whole plugin on a mock server with a clean data folder, an arena world and helpers. */
abstract class DuelsTestBase {

    private static final long TIMEOUT_MILLIS = 10_000;

    protected ServerMock server;
    protected PluginMock plugin;
    protected WorldMock world;
    protected WorldMock arenaWorld;
    protected Duels duels;

    @BeforeEach
    void startServer() throws IOException {
        server = MockBukkit.mock();
        server.getPluginManager().registerEvents(new CleanupListener(), MockBukkit.createMockPlugin("ElotecraftAPI"));
        plugin = MockBukkit.createMockPlugin("ElotecraftDuels");
        deleteRecursively(plugin.getDataFolder().toPath());
        registerPermissions();
        world = server.addSimpleWorld("world");
        arenaWorld = server.addSimpleWorld("arena");
        duels = Duels.start(plugin);
        await(duels.ready());
    }

    @AfterEach
    void stopServer() {
        try {
            if (duels != null) {
                duels.shutdown();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    protected TestPlayer join(String name) {
        TestPlayer player = new TestPlayer(server, name);
        server.addPlayer(player);
        tick();
        return player;
    }

    /** A ready arena: spawns 10 blocks apart inside a 21x21x21 box in the arena world. */
    protected Arena readyArena(String name) {
        Location origin = new Location(arenaWorld, 0, 64, 0);
        await(duels.arenas().create(name, origin));
        Arena arena = duels.arenas().get(name).orElseThrow()
                .withSpawn(1, new Position(5.5, 64, 5.5, 0, 0))
                .withSpawn(2, new Position(15.5, 64, 5.5, 180, 0))
                .withCorner(1, new Position(0, 60, 0, 0, 0))
                .withCorner(2, new Position(20, 80, 20, 0, 0));
        await(duels.arenas().update(arena));
        return arena;
    }

    /** A kit of one diamond sword. */
    protected Kit swordKit() {
        Kit kit = new Kit("sword", "<aqua>Sword", Material.DIAMOND_SWORD, null, List.of(ItemStack.of(Material.DIAMOND_SWORD)), false, Set.of());
        await(duels.kits().update(kit));
        return kit;
    }

    /** A build kit of a stack of planks. */
    protected Kit buildKit() {
        Kit kit = new Kit("bridge", "<gold>Bridge", Material.OAK_PLANKS, null, List.of(ItemStack.of(Material.OAK_PLANKS, 64)), true, Set.of());
        await(duels.kits().update(kit));
        return kit;
    }

    /** Changes one value in config.yml and reloads. */
    protected void setConfig(String path, Object value) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set(path, value);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        duels.reload();
    }

    protected void tick() {
        server.getScheduler().performOneTick();
    }

    protected void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    /** Ticks until {@code future} completes; database and file results arrive on later ticks. */
    protected <T> T await(CompletableFuture<T> future) {
        tickUntil(future::isDone);
        return future.join();
    }

    protected void tickUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Condition not met in time");
            }
            tick();
            Thread.onSpinWait();
        }
    }

    /** Runs {@code command} as {@code player} and ticks until a chat line holds {@code text}, for answers after file saves. */
    protected void assertSays(TestPlayer player, String command, String text) {
        messages(player);
        server.dispatchCommand(player, command);
        List<String> lines = new ArrayList<>();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (lines.stream().noneMatch(line -> line.contains(text))) {
            if (System.currentTimeMillis() > deadline) {
                fail("Expected '" + text + "' after /" + command + " in " + lines);
            }
            tick();
            lines.addAll(messages(player));
            Thread.onSpinWait();
        }
    }

    protected static List<String> messages(TestPlayer player) {
        List<String> lines = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            lines.add(Text.plain(line));
        }
        return lines;
    }

    /** The permissions and defaults from plugin.yml, as a real server registers them. */
    private void registerPermissions() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            new PluginDescriptionFile(in).getPermissions().forEach(server.getPluginManager()::addPermission);
        } catch (IOException | InvalidDescriptionException e) {
            throw new IllegalStateException("Could not read plugin.yml", e);
        }
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (Files.notExists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
