package me.angylo.elotecraftExample;

import me.angylo.elotecraftAPI.storage.Database;
import me.angylo.elotecraftAPI.util.Events;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Example: counts blocks broken per player and block type in {@code blocks.db} (SQLite).
 * Breaks are counted in memory and written in one transaction every 30 seconds, instead of one
 * write per block. Reads flush first; the database runs one query at a time in order, so they
 * always see the latest counts.
 */
final class BlockStats {

    private static final long FLUSH_PERIOD_TICKS = 20L * 30;
    private static final int TOP_BLOCKS_SHOWN = 5;
    private static final int LEADERBOARD_SIZE = 10;

    private static final String CREATE_PLAYERS = """
            CREATE TABLE IF NOT EXISTS players (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL
            )""";
    private static final String CREATE_BREAKS = """
            CREATE TABLE IF NOT EXISTS block_breaks (
                uuid     TEXT NOT NULL REFERENCES players (uuid),
                material TEXT NOT NULL,
                amount   INTEGER NOT NULL,
                PRIMARY KEY (uuid, material)
            )""";
    private static final String UPSERT_PLAYER = """
            INSERT INTO players (uuid, name) VALUES (?, ?)
            ON CONFLICT (uuid) DO UPDATE SET name = excluded.name""";
    private static final String UPSERT_BREAKS = """
            INSERT INTO block_breaks (uuid, material, amount) VALUES (?, ?, ?)
            ON CONFLICT (uuid, material) DO UPDATE SET amount = amount + excluded.amount""";
    private static final String FIND_PLAYER = "SELECT uuid, name FROM players WHERE name = ? COLLATE NOCASE";
    private static final String PLAYER_BREAKS =
            "SELECT material, amount FROM block_breaks WHERE uuid = ? ORDER BY amount DESC";
    private static final String LEADERBOARD = """
            SELECT p.name, SUM(b.amount) AS total
            FROM block_breaks b JOIN players p ON p.uuid = b.uuid
            GROUP BY b.uuid ORDER BY total DESC LIMIT ?""";

    private record Profile(String uuid, String name) {
    }

    private record Count(String material, int amount) {
    }

    private record Rank(String name, int total) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Database db;
    // Main thread only: written by the break listener, swapped out by flush().
    private Map<UUID, Map<Material, Integer>> pending = new HashMap<>();
    private Map<UUID, String> pendingNames = new HashMap<>();

    BlockStats(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.db = Database.sqlite(plugin, "blocks.db");
        db.update(CREATE_PLAYERS);
        db.update(CREATE_BREAKS);
        Events.listen(plugin, BlockBreakEvent.class, EventPriority.MONITOR, true, this::record);
        Tasks.timer(plugin, this::flush, FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
    }

    /** Writes pending counts, then waits for queued writes and closes the database. For {@code onDisable}. */
    void shutdown() {
        flush();
        db.close();
    }

    /** Shows {@code targetName}'s totals (any player who has broken a block, online or not). */
    CompletableFuture<Void> show(CommandSender viewer, String targetName) {
        flush();
        return db.queryOne(FIND_PLAYER, row -> new Profile(row.getString("uuid"), row.getString("name")), targetName)
                .thenCompose(profile -> {
                    if (profile.isEmpty()) {
                        messages.send(viewer, "example.blocks-unknown", Placeholder.unparsed("player", targetName));
                        return CompletableFuture.completedFuture(null);
                    }
                    return db.query(PLAYER_BREAKS, row -> new Count(row.getString("material"), row.getInt("amount")),
                                    profile.get().uuid())
                            .thenAccept(counts -> sendCounts(viewer, profile.get().name(), counts));
                })
                .exceptionally(error -> {
                    fail(viewer, error);
                    return null;
                });
    }

    /** Shows the top miners by total blocks broken. */
    CompletableFuture<Void> showTop(CommandSender viewer) {
        flush();
        return db.query(LEADERBOARD, row -> new Rank(row.getString("name"), row.getInt("total")), LEADERBOARD_SIZE)
                .thenAccept(ranks -> {
                    if (ranks.isEmpty()) {
                        messages.send(viewer, "example.top-empty");
                        return;
                    }
                    messages.send(viewer, "example.top-header");
                    for (int i = 0; i < ranks.size(); i++) {
                        messages.send(viewer, "example.top-line",
                                Placeholder.unparsed("rank", String.valueOf(i + 1)),
                                Placeholder.unparsed("player", ranks.get(i).name()),
                                Placeholder.unparsed("total", String.valueOf(ranks.get(i).total())));
                    }
                })
                .exceptionally(error -> {
                    fail(viewer, error);
                    return null;
                });
    }

    private void record(BlockBreakEvent event) {
        Player player = event.getPlayer();
        pending.computeIfAbsent(player.getUniqueId(), uuid -> new EnumMap<>(Material.class))
                .merge(event.getBlock().getType(), 1, Integer::sum);
        pendingNames.put(player.getUniqueId(), player.getName());
    }

    /** Writes all pending counts in one transaction; on failure they go back into memory. */
    void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<UUID, Map<Material, Integer>> batch = pending;
        Map<UUID, String> names = pendingNames;
        pending = new HashMap<>();
        pendingNames = new HashMap<>();

        db.transaction(connection -> {
            try (PreparedStatement players = connection.prepareStatement(UPSERT_PLAYER);
                 PreparedStatement breaks = connection.prepareStatement(UPSERT_BREAKS)) {
                for (Map.Entry<UUID, Map<Material, Integer>> player : batch.entrySet()) {
                    String uuid = player.getKey().toString();
                    players.setString(1, uuid);
                    players.setString(2, names.get(player.getKey()));
                    players.addBatch();
                    for (Map.Entry<Material, Integer> count : player.getValue().entrySet()) {
                        breaks.setString(1, uuid);
                        breaks.setString(2, count.getKey().name());
                        breaks.setInt(3, count.getValue());
                        breaks.addBatch();
                    }
                }
                players.executeBatch();
                breaks.executeBatch();
            }
            return null;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not save block stats; keeping them for the next flush", error);
            requeue(batch, names);
            return null;
        });
    }

    private void requeue(Map<UUID, Map<Material, Integer>> batch, Map<UUID, String> names) {
        batch.forEach((uuid, counts) -> counts.forEach((material, amount) ->
                pending.computeIfAbsent(uuid, key -> new EnumMap<>(Material.class)).merge(material, amount, Integer::sum)));
        names.forEach(pendingNames::putIfAbsent);
    }

    private void sendCounts(CommandSender viewer, String name, List<Count> counts) {
        int total = counts.stream().mapToInt(Count::amount).sum();
        messages.send(viewer, "example.blocks-header",
                Placeholder.unparsed("player", name), Placeholder.unparsed("total", String.valueOf(total)));
        counts.stream().limit(TOP_BLOCKS_SHOWN).forEach(count -> messages.send(viewer, "example.blocks-line",
                Placeholder.unparsed("block", pretty(count.material())),
                Placeholder.unparsed("amount", String.valueOf(count.amount()))));
    }

    private void fail(CommandSender viewer, Throwable error) {
        plugin.getLogger().log(Level.WARNING, "Block stats query failed", error);
        messages.send(viewer, "example.blocks-error");
    }

    private static String pretty(String material) {
        return material.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
