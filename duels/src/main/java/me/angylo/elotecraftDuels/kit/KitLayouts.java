package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftAPI.storage.Database;
import me.angylo.elotecraftDuels.stats.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Players' own layouts of kits, made with the kit editor, in the {@code duels_kit_layouts} table, with the name they gave
 * them and, for custom kits, their arena and rules. Online players' layouts are cached so duels never wait for the
 * database. A layout is only used while it holds exactly its kit's items; once an admin changes the kit, it is dropped
 * and the kit is used as made. Main thread only.
 */
public final class KitLayouts implements Listener {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_kit_layouts (
                uuid  VARCHAR(36) NOT NULL,
                kit   VARCHAR(32) NOT NULL,
                items TEXT NOT NULL,
                name  VARCHAR(64),
                arena VARCHAR(32),
                rules TEXT,
                PRIMARY KEY (uuid, kit)
            )""";
    /** Columns tables made before the menu editor lack. */
    private static final Map<String, String> ADDED = Map.of("name", "VARCHAR(64)", "arena", "VARCHAR(32)", "rules", "TEXT");
    private static final String FIND = "SELECT kit, items, name, arena, rules FROM duels_kit_layouts WHERE uuid = ?";
    private static final String DELETE = "DELETE FROM duels_kit_layouts WHERE uuid = ? AND kit = ?";
    private static final String INSERT = "INSERT INTO duels_kit_layouts (uuid, kit, items, name, arena, rules) VALUES (?, ?, ?, ?, ?, ?)";

    /**
     * What a player keeps under a key: a kit's name for a layout, {@code custom:<slot>} for a custom kit.
     *
     * @param name  the name they gave it, or null
     * @param arena a custom kit's arena, or null for a random one
     * @param rules a custom kit's game rules
     */
    public record Saved(List<ItemStack> items, String name, String arena, Map<KitRule, Object> rules) {

        public Saved {
            items = items.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList();
            rules = Map.copyOf(rules);
        }

        @Override
        public List<ItemStack> items() {
            return items.stream().map(ItemStack::clone).toList();
        }
    }

    private record Row(String kit, String items, String name, String arena, String rules) {
    }

    private final Logger logger;
    private final Database db;
    private final CompletableFuture<Integer> schema;
    /** Online player, then key, to what they keep. */
    private final Map<UUID, Map<String, Saved>> online = new ConcurrentHashMap<>();

    public KitLayouts(Plugin plugin, Database db) {
        this.logger = plugin.getLogger();
        this.db = db;
        this.schema = db.transaction(KitLayouts::createTable);
    }

    /** Completes once the table exists. */
    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /** Reads {@code player}'s layouts into the cache. */
    public void load(Player player) {
        UUID uuid = player.getUniqueId();
        schema.thenCompose(ignored -> db.query(FIND, row -> new Row(row.getString("kit"), row.getString("items"),
                        row.getString("name"), row.getString("arena"), row.getString("rules")), uuid))
                .thenAccept(rows -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Map<String, Saved> layouts = new HashMap<>();
                    for (Row row : rows) {
                        try {
                            layouts.put(row.kit(), new Saved(decode(row.items()), row.name(), row.arena(), rules(row.rules())));
                        } catch (RuntimeException e) {
                            logger.warning("Skipping " + player.getName() + "'s layout of kit " + row.kit() + ": " + e.getMessage());
                        }
                    }
                    online.put(uuid, layouts);
                }).exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not load " + player.getName() + "'s kit layouts", error);
                    return null;
                });
    }

    /** {@code player}'s layout of {@code kit}, if it still holds exactly the kit's items. */
    public Optional<List<ItemStack>> layout(UUID player, Kit kit) {
        Saved saved = online.getOrDefault(player, Map.of()).get(kit.name());
        if (saved == null) {
            return Optional.empty();
        }
        if (!kit.sameItems(saved.items)) {
            reset(player, kit.name());
            return Optional.empty();
        }
        return Optional.of(saved.items());
    }

    /** The name {@code player} gave their layout of {@code kit}, if any. */
    public Optional<String> name(UUID player, Kit kit) {
        return Optional.ofNullable(online.getOrDefault(player, Map.of()).get(kit.name())).map(Saved::name);
    }

    /** Gives {@code player} their layout of {@code kit}, or the kit as made. */
    public void apply(Player player, Kit kit) {
        layout(player.getUniqueId(), kit).ifPresentOrElse(layout -> Kit.apply(player, layout), () -> kit.apply(player));
    }

    /**
     * Stores {@code layout} as {@code player}'s layout of {@code kit}; callers check it with {@link Kit#sameItems}.
     *
     * @param name the name they gave it, or null
     */
    public void save(UUID player, Kit kit, List<ItemStack> layout, String name) {
        store(player, kit.name(), new Saved(layout, name, null, Map.of()));
    }

    /** What {@code player} stored under {@code key} (a kit's name, or a custom kit's {@link CustomKits} key), unchecked. */
    Optional<Saved> stored(UUID player, String key) {
        return Optional.ofNullable(online.getOrDefault(player, Map.of()).get(key));
    }

    /** Stores {@code saved} as {@code player}'s under {@code key}, replacing what was there. */
    void store(UUID player, String key, Saved saved) {
        online.computeIfAbsent(player, uuid -> new ConcurrentHashMap<>()).put(key, saved);
        String encoded = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(saved.items));
        String rules = rules(saved.rules);
        schema.thenCompose(ignored -> db.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
                delete.setString(1, player.toString());
                delete.setString(2, key);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setString(1, player.toString());
                insert.setString(2, key);
                insert.setString(3, encoded);
                insert.setString(4, saved.name);
                insert.setString(5, saved.arena);
                insert.setString(6, rules);
                return insert.executeUpdate();
            }
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save a kit layout of kit " + key, error);
            return null;
        });
    }

    /** Drops {@code player}'s layout of {@code kit}, if any; they get the kit as made again. */
    public void reset(UUID player, String kit) {
        Map<String, Saved> layouts = online.get(player);
        if (layouts != null) {
            layouts.remove(kit);
        }
        schema.thenCompose(ignored -> db.update(DELETE, player.toString(), kit)).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not delete a kit layout of kit " + kit, error);
            return null;
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        online.remove(event.getPlayer().getUniqueId());
    }

    /** Loads everyone already online, after a reload. */
    public void loadOnline() {
        Bukkit.getOnlinePlayers().forEach(this::load);
    }

    private static Integer createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int changed = statement.executeUpdate(CREATE);
            for (Map.Entry<String, String> column : ADDED.entrySet()) {
                if (!StatsService.hasColumn(statement, "duels_kit_layouts", column.getKey())) {
                    changed += statement.executeUpdate("ALTER TABLE duels_kit_layouts ADD COLUMN " + column.getKey() + " " + column.getValue());
                }
            }
            return changed;
        }
    }

    private static List<ItemStack> decode(String items) {
        return Arrays.asList(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(items)));
    }

    /** {@code rules} as {@code key=value,key=value}; null when there are none. */
    private static String rules(Map<KitRule, Object> rules) {
        if (rules.isEmpty()) {
            return null;
        }
        StringJoiner joined = new StringJoiner(",");
        rules.forEach((rule, value) -> joined.add(rule.key() + "=" + value));
        return joined.toString();
    }

    /** Reads {@link #rules(Map)}, skipping rules that no longer exist or values that no longer fit. */
    private static Map<KitRule, Object> rules(String stored) {
        Map<KitRule, Object> rules = new EnumMap<>(KitRule.class);
        if (stored == null || stored.isBlank()) {
            return rules;
        }
        for (String pair : stored.split(",")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                KitRule.byKey(parts[0]).ifPresent(rule -> {
                    try {
                        rules.put(rule, rule.parse(parts[1]));
                    } catch (IllegalArgumentException ignored) {
                        // A value from an older version that no longer fits: the rule takes its default.
                    }
                });
            }
        }
        return rules;
    }
}
