package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftAPI.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Players' own layouts of kits, made with the kit editor, in the {@code duels_kit_layouts} table. Online
 * players' layouts are cached so duels never wait for the database. A layout is only used while it holds
 * exactly its kit's items; once an admin changes the kit, it is dropped and the kit is used as made.
 * Main thread only.
 */
public final class KitLayouts implements Listener {

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS duels_kit_layouts (
                uuid  VARCHAR(36) NOT NULL,
                kit   VARCHAR(32) NOT NULL,
                items TEXT NOT NULL,
                PRIMARY KEY (uuid, kit)
            )""";
    private static final String FIND = "SELECT kit, items FROM duels_kit_layouts WHERE uuid = ?";
    private static final String DELETE = "DELETE FROM duels_kit_layouts WHERE uuid = ? AND kit = ?";
    private static final String INSERT = "INSERT INTO duels_kit_layouts (uuid, kit, items) VALUES (?, ?, ?)";

    private record Row(String kit, String items) {
    }

    private final Logger logger;
    private final Database db;
    private final CompletableFuture<Integer> schema;
    /** Online player, then kit name, to their layout. */
    private final Map<UUID, Map<String, List<ItemStack>>> online = new ConcurrentHashMap<>();

    public KitLayouts(Plugin plugin, Database db) {
        this.logger = plugin.getLogger();
        this.db = db;
        this.schema = db.update(CREATE);
    }

    /** Completes once the table exists. */
    public CompletableFuture<Integer> ready() {
        return schema;
    }

    /** Reads {@code player}'s layouts into the cache. */
    public void load(Player player) {
        UUID uuid = player.getUniqueId();
        schema.thenCompose(ignored -> db.query(FIND, row -> new Row(row.getString("kit"), row.getString("items")), uuid))
                .thenAccept(rows -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Map<String, List<ItemStack>> layouts = new HashMap<>();
                    for (Row row : rows) {
                        try {
                            layouts.put(row.kit(), decode(row.items()));
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
        List<ItemStack> layout = online.getOrDefault(player, Map.of()).get(kit.name());
        if (layout == null) {
            return Optional.empty();
        }
        if (!kit.sameItems(layout)) {
            reset(player, kit.name());
            return Optional.empty();
        }
        return Optional.of(layout.stream().map(item -> item == null ? null : item.clone()).toList());
    }

    /** Gives {@code player} their layout of {@code kit}, or the kit as made. */
    public void apply(Player player, Kit kit) {
        layout(player.getUniqueId(), kit).ifPresentOrElse(layout -> Kit.apply(player, layout), () -> kit.apply(player));
    }

    /** Stores {@code layout} as {@code player}'s layout of {@code kit}; callers check it with {@link Kit#sameItems}. */
    public void save(UUID player, Kit kit, List<ItemStack> layout) {
        store(player, kit.name(), layout);
    }

    /** {@code player}'s items stored under {@code key} (a kit's name, or a custom kit's {@link CustomKits} key), unchecked. */
    Optional<List<ItemStack>> stored(UUID player, String key) {
        List<ItemStack> items = online.getOrDefault(player, Map.of()).get(key);
        return items == null ? Optional.empty() : Optional.of(items.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList());
    }

    /** Stores {@code items} as {@code player}'s under {@code key}, replacing what was there. */
    void store(UUID player, String key, List<ItemStack> items) {
        List<ItemStack> copy = items.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList();
        online.computeIfAbsent(player, uuid -> new ConcurrentHashMap<>()).put(key, copy);
        String encoded = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(copy));
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
                return insert.executeUpdate();
            }
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save a kit layout of kit " + key, error);
            return null;
        });
    }

    /** Drops {@code player}'s layout of {@code kit}, if any; they get the kit as made again. */
    public void reset(UUID player, String kit) {
        Map<String, List<ItemStack>> layouts = online.get(player);
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

    private static List<ItemStack> decode(String items) {
        return Arrays.asList(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(items)));
    }
}
