package me.angylo.elotecraftAPI.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.angylo.elotecraftAPI.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Pooled SQLite or MySQL access that never blocks the main thread.
 * <pre>{@code
 * Database db = Database.fromConfig(this, getConfig().getConfigurationSection("database")); // in onEnable
 * db.update("INSERT INTO coins (uuid, amount) VALUES (?, ?)", player.getUniqueId(), 10);
 * db.queryOne("SELECT amount FROM coins WHERE uuid = ?", row -> row.getInt("amount"), player.getUniqueId())
 *         .thenAccept(amount -> player.sendMessage("Coins: " + amount.orElse(0)));
 * db.close();                                                                                // in onDisable
 * }</pre>
 * Queries run on the database's own threads and always use {@code ?} parameters, so values are
 * never spliced into SQL. Futures complete on the main thread while the plugin is enabled, so
 * callbacks may use the Bukkit API; during shutdown they complete on a database thread instead.
 * {@link UUID} parameters are stored as strings.
 */
public final class Database implements AutoCloseable {

    private static final int DEFAULT_MYSQL_POOL_SIZE = 10;
    private static final long CLOSE_TIMEOUT_SECONDS = 10;

    /** Reads one row of a {@link ResultSet}; do not call {@code next()} yourself. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    /** Work done with one connection, e.g. inside {@link #transaction(SqlWork)}. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Plugin plugin;
    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    private Database(Plugin plugin, HikariConfig config, int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("Pool size must be at least 1: " + poolSize);
        }
        config.setMaximumPoolSize(poolSize);
        config.setPoolName(plugin.getName() + "-db");
        this.plugin = plugin;
        try {
            this.dataSource = new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not connect to the database: " + e.getMessage(), e);
        }
        this.executor = Executors.newFixedThreadPool(poolSize,
                Thread.ofPlatform().name(plugin.getName() + "-db-", 1).daemon(true).factory());
    }

    /**
     * A SQLite file in the plugin's data folder. Uses one connection, since SQLite allows one writer.
     * Connects immediately; create it in {@code onEnable}.
     */
    public static Database sqlite(Plugin plugin, String fileName) {
        Path file = plugin.getDataFolder().toPath().resolve(fileName).toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + file.getParent(), e);
        }
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        return new Database(plugin, config, 1);
    }

    /** A MySQL/MariaDB server. Connects immediately; create it in {@code onEnable}. */
    public static Database mysql(Plugin plugin, String host, int port, String database,
                                 String user, String password, int poolSize) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        config.setUsername(user);
        config.setPassword(password);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        return new Database(plugin, config, poolSize);
    }

    /**
     * Opens the database described by a config section, keeping credentials out of code:
     * <pre>
     * database:
     *   type: sqlite        # sqlite or mysql
     *   file: data.db       # sqlite only
     *   host: localhost     # mysql only, as are the keys below
     *   port: 3306
     *   name: elotecraft
     *   user: minecraft
     *   password: ""
     *   pool-size: 10
     * </pre>
     *
     * @throws IllegalArgumentException if the section is missing, the type is unknown or a required key is missing
     */
    public static Database fromConfig(Plugin plugin, ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("Missing database config section");
        }
        String type = section.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "sqlite" -> sqlite(plugin, section.getString("file", "data.db"));
            case "mysql" -> mysql(plugin,
                    section.getString("host", "localhost"),
                    section.getInt("port", 3306),
                    required(section, "name"),
                    required(section, "user"),
                    section.getString("password", ""),
                    section.getInt("pool-size", DEFAULT_MYSQL_POOL_SIZE));
            default -> throw new IllegalArgumentException("Unknown database type '" + type + "'; use sqlite or mysql");
        };
    }

    /** Runs INSERT, UPDATE, DELETE or DDL; completes with the number of affected rows. */
    public CompletableFuture<Integer> update(String sql, Object... params) {
        return submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        });
    }

    /** Completes with every row mapped by {@code mapper}. */
    public <T> CompletableFuture<List<T>> query(String sql, RowMapper<T> mapper, Object... params) {
        return submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet rows = statement.executeQuery()) {
                    List<T> results = new ArrayList<>();
                    while (rows.next()) {
                        results.add(mapper.map(rows));
                    }
                    return Collections.unmodifiableList(results);
                }
            }
        });
    }

    /** Completes with the first row, or empty if there is none. */
    public <T> CompletableFuture<Optional<T>> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.ofNullable(mapper.map(rows)) : Optional.empty();
                }
            }
        });
    }

    /** Runs {@code work} in one transaction: committed if it returns, rolled back if it throws. */
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return submit(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    /**
     * Runs {@code work} on the calling thread and returns its result, for threads that may wait, such as an
     * {@code AsyncPlayerPreLoginEvent} handler loading a player's data before they join. It uses its own
     * pooled connection, so it is not queued behind earlier queries.
     *
     * @throws IllegalStateException if called on the main thread or after {@link #close()}
     * @throws SQLException          if the work fails
     */
    public <T> T runBlocking(SqlWork<T> work) throws SQLException {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("runBlocking would freeze the server; call it off the main thread");
        }
        if (executor.isShutdown()) {
            throw new IllegalStateException("Database is closed");
        }
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        }
    }

    /**
     * Waits up to 10 seconds for queued queries to finish, then closes the pool. Call in {@code onDisable};
     * queries submitted afterwards fail with {@link IllegalStateException}.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Database queries still running after " + CLOSE_TIMEOUT_SECONDS + "s; closing anyway");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        dataSource.close();
    }

    private <T> CompletableFuture<T> submit(SqlWork<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    T value = work.run(connection);
                    completeOnMain(() -> result.complete(value));
                } catch (SQLException | RuntimeException e) {
                    completeOnMain(() -> result.completeExceptionally(e));
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("Database is closed", e));
        }
        return result;
    }

    private void completeOnMain(Runnable completion) {
        if (plugin.isEnabled()) {
            try {
                Tasks.sync(plugin, completion);
                return;
            } catch (IllegalPluginAccessException e) {
                // Plugin disabled between the check and scheduling; complete here instead.
            }
        }
        completion.run();
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i] instanceof UUID uuid ? uuid.toString() : params[i];
            statement.setObject(i + 1, value);
        }
    }

    private static String required(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing database." + key + " in config");
        }
        return value;
    }
}
