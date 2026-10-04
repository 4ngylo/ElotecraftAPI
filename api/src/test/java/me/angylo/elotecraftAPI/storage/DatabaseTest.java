package me.angylo.elotecraftAPI.storage;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DatabaseTest {

    private static final String FILE = "test.db";
    private static final long TIMEOUT_MILLIS = 5000;
    private static final String CREATE = "CREATE TABLE players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, coins INTEGER NOT NULL)";
    private static final String INSERT = "INSERT INTO players (uuid, name, coins) VALUES (?, ?, ?)";

    private ServerMock server;
    private PluginMock plugin;
    private Database db;

    private record Row(String uuid, String name, int coins) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Files.deleteIfExists(plugin.getDataFolder().toPath().resolve(FILE));
        db = Database.sqlite(plugin, FILE);
        await(db.update(CREATE));
    }

    @AfterEach
    void tearDown() {
        db.close();
        MockBukkit.unmock();
    }

    /** Ticks the mock server until the future completes; completions are scheduled on the main thread. */
    private <T> T await(CompletableFuture<T> future) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!future.isDone()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Database future did not complete in time");
            }
            server.getScheduler().performOneTick();
            Thread.onSpinWait();
        }
        return future.join();
    }

    private int count() {
        return await(db.queryOne("SELECT COUNT(*) FROM players", row -> row.getInt(1))).orElseThrow();
    }

    @Test
    void insertsAndQueriesWithParameters() {
        UUID steve = UUID.randomUUID();
        assertEquals(1, await(db.update(INSERT, steve, "Steve", 10)));
        await(db.update(INSERT, UUID.randomUUID(), "Alex", 25));

        List<Row> rows = await(db.query("SELECT * FROM players ORDER BY coins",
                row -> new Row(row.getString("uuid"), row.getString("name"), row.getInt("coins"))));
        Optional<Integer> coins = await(db.queryOne("SELECT coins FROM players WHERE uuid = ?", row -> row.getInt(1), steve));
        Optional<Integer> missing = await(db.queryOne("SELECT coins FROM players WHERE uuid = ?", row -> row.getInt(1), "nobody"));

        assertEquals(List.of("Steve", "Alex"), rows.stream().map(Row::name).toList());
        assertEquals(steve.toString(), rows.getFirst().uuid());
        assertEquals(Optional.of(10), coins);
        assertEquals(Optional.empty(), missing);
    }

    @Test
    void parametersAreNeverSplicedIntoSql() {
        String hostile = "x', 0); DROP TABLE players; --";

        await(db.update(INSERT, "id", hostile, 1));

        assertEquals(1, count());
        assertEquals(Optional.of(hostile), await(db.queryOne("SELECT name FROM players", row -> row.getString(1))));
    }

    @Test
    void transactionRollsBackWhenWorkFails() {
        await(db.update(INSERT, "a", "Steve", 1));

        CompletableFuture<Void> failed = db.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setString(1, "b");
                insert.setString(2, "Alex");
                insert.setInt(3, 2);
                insert.executeUpdate();
            }
            throw new SQLException("boom");
        });

        CompletionException error = assertThrows(CompletionException.class, () -> await(failed));
        assertInstanceOf(SQLException.class, error.getCause());
        assertEquals(1, count());
    }

    @Test
    void sqlErrorsCompleteExceptionally() {
        CompletionException error = assertThrows(CompletionException.class,
                () -> await(db.update("INSERT INTO nope VALUES (1)")));

        assertInstanceOf(SQLException.class, error.getCause());
    }

    @Test
    void callbacksRunOnMainThread() {
        AtomicBoolean onMain = new AtomicBoolean();

        await(db.update(INSERT, "a", "Steve", 1).thenAccept(rows -> onMain.set(Bukkit.isPrimaryThread())));

        assertTrue(onMain.get());
    }

    @Test
    void closeFinishesQueuedWritesThenRejectsNewOnes() {
        for (int i = 0; i < 50; i++) {
            db.update(INSERT, "id-" + i, "Player" + i, i);
        }

        db.close();
        CompletableFuture<Integer> late = db.update(INSERT, "late", "Late", 0);

        CompletionException error = assertThrows(CompletionException.class, late::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());

        db = Database.sqlite(plugin, FILE);
        assertEquals(50, count());
    }

    @Test
    void fromConfigOpensSqliteAndRejectsUnknownTypes() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", FILE);
        Database fromConfig = Database.fromConfig(plugin, config.getConfigurationSection("database"));
        try {
            assertEquals(Optional.of(1), await(fromConfig.queryOne("SELECT 1", row -> row.getInt(1))));
        } finally {
            fromConfig.close();
        }

        config.set("database.type", "postgres");
        assertThrows(IllegalArgumentException.class,
                () -> Database.fromConfig(plugin, config.getConfigurationSection("database")));
        config.set("database.type", "mysql");
        assertThrows(IllegalArgumentException.class,
                () -> Database.fromConfig(plugin, config.getConfigurationSection("database")));
        assertThrows(IllegalArgumentException.class, () -> Database.fromConfig(plugin, null));
    }
}
