package me.angylo.elotecraftDuels.hud;

import me.angylo.elotecraftAPI.hologram.Hologram;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.stats.Divisions;
import me.angylo.elotecraftDuels.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Floating leaderboards placed with {@code /duels hologram}, stored in holograms.yml: the top players by wins,
 * by overall rating or by one kit's rating, with the lines of {@code /duel top}. Read from the database every
 * minute; respawned when their chunk loads again, as holograms are not saved with the world. Main thread only.
 */
public final class LeaderboardHolograms {

    private static final int REFRESH_SECONDS = 60;
    private static final String ROOT = "holograms";

    public enum Type {
        WINS, ELO;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Type> byKey(String key) {
            for (Type type : values()) {
                if (type.key().equalsIgnoreCase(key)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    /** @param kit the kit of an {@link Type#ELO} board; null for the overall rating, and for wins */
    public record Board(String name, Type type, String kit, String world, Position position) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final StatsService stats;
    private final KitRegistry kits;
    private final ConfigFile file;
    private final Map<String, Board> boards = new TreeMap<>();
    private final Map<String, Hologram> spawned = new HashMap<>();
    /** The last text read for each board, shown again when it respawns. */
    private final Map<String, Component> texts = new HashMap<>();
    private int seconds;
    /** Whether a board was placed or deleted, so shutdown writes a save that may still be pending. */
    private boolean changed;

    public LeaderboardHolograms(Plugin plugin, Messages messages, Supplier<Settings> settings, StatsService stats, KitRegistry kits) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.stats = stats;
        this.kits = kits;
        this.file = new ConfigFile(plugin, "holograms.yml");
        load();
    }

    /** Reads the boards every minute and spawns the ones whose chunk is loaded; once a second. */
    public void tick() {
        if (seconds++ % REFRESH_SECONDS == 0) {
            boards.values().forEach(this::refresh);
        }
        boards.values().forEach(this::spawn);
    }

    public Optional<Board> get(String name) {
        return Optional.ofNullable(boards.get(name));
    }

    public Collection<Board> all() {
        return List.copyOf(boards.values());
    }

    /** Adds or replaces {@code board}, reads its text and saves the file; it appears on the next tick. */
    public CompletableFuture<Void> put(Board board) {
        remove(board.name());
        boards.put(board.name(), board);
        write(board);
        refresh(board);
        changed = true;
        return file.save();
    }

    public CompletableFuture<Void> delete(String name) {
        remove(name);
        boards.remove(name);
        file.get().set(ROOT + "." + name, null);
        changed = true;
        return file.save();
    }

    /** The board's text as it is in the database now; completes on the main thread. */
    public CompletableFuture<Component> text(Board board) {
        int count = settings.get().hologramLines();
        if (board.type() == Type.WINS) {
            return stats.top(count).thenApply(top -> {
                List<Component> lines = new ArrayList<>();
                for (int i = 0; i < top.size(); i++) {
                    lines.add(messages.get("top.line", Placeholder.unparsed("rank", String.valueOf(i + 1)),
                            Placeholder.unparsed("player", top.get(i).name()),
                            Placeholder.unparsed("wins", String.valueOf(top.get(i).wins())),
                            Placeholder.unparsed("losses", String.valueOf(top.get(i).losses()))));
                }
                return join(messages.get("top.header"), lines);
            });
        }
        Optional<Kit> kit = Optional.ofNullable(board.kit()).flatMap(kits::get);
        if (board.kit() != null && kit.isEmpty()) {
            // The kit was deleted: an empty board until it is placed again.
            return CompletableFuture.completedFuture(join(messages.get("top.elo-kit-header",
                    Placeholder.unparsed("kit", board.kit())), List.of()));
        }
        Divisions divisions = settings.get().ranked().divisions();
        return (kit.isPresent() ? stats.topByElo(kit.get().name(), count) : stats.topByElo(kits.names(), count)).thenApply(top -> {
            List<Component> lines = new ArrayList<>();
            for (int i = 0; i < top.size(); i++) {
                lines.add(messages.get("top.elo-line", Placeholder.unparsed("rank", String.valueOf(i + 1)),
                        Placeholder.unparsed("player", top.get(i).name()),
                        Placeholder.unparsed("elo", String.valueOf(top.get(i).elo())),
                        Placeholder.component("division", divisions.name(top.get(i).elo())),
                        Placeholder.unparsed("wins", String.valueOf(top.get(i).wins())),
                        Placeholder.unparsed("losses", String.valueOf(top.get(i).losses()))));
            }
            return join(kit.map(found -> messages.get("top.elo-kit-header", Placeholder.component("kit", Text.mm(found.displayName()))))
                    .orElseGet(() -> messages.get("top.elo-header")), lines);
        });
    }

    /** Removes the holograms and writes a save that may still be pending; for shutdown. */
    public void shutdown() {
        spawned.values().forEach(Hologram::remove);
        spawned.clear();
        if (changed) {
            file.saveNow();
        }
    }

    private void refresh(Board board) {
        text(board).whenComplete((text, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not read the leaderboard of hologram " + board.name(), error);
                return;
            }
            if (boards.get(board.name()) != board) {
                return;
            }
            texts.put(board.name(), text);
            Hologram hologram = spawned.get(board.name());
            if (hologram != null && hologram.isValid()) {
                hologram.text(text);
            }
        });
    }

    /** Spawns {@code board} unless it is there, its world is not loaded or its chunk is not. */
    private void spawn(Board board) {
        Hologram hologram = spawned.get(board.name());
        if (hologram != null && hologram.isValid()) {
            return;
        }
        World world = plugin.getServer().getWorld(board.world());
        if (world == null) {
            return;
        }
        Location location = board.position().in(world);
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return;
        }
        if (hologram != null) {
            hologram.remove();
        }
        spawned.put(board.name(), Hologram.spawn(plugin, location,
                texts.getOrDefault(board.name(), messages.get("holograms.loading"))));
    }

    private void remove(String name) {
        Hologram hologram = spawned.remove(name);
        if (hologram != null) {
            hologram.remove();
        }
        texts.remove(name);
    }

    private Component join(Component header, List<Component> lines) {
        List<Component> all = new ArrayList<>();
        all.add(header);
        all.addAll(lines.isEmpty() ? List.of(messages.get("holograms.empty")) : lines);
        return Component.join(JoinConfiguration.newlines(), all);
    }

    private void load() {
        ConfigurationSection root = file.get().getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            Optional<Type> type = section == null ? Optional.empty() : Type.byKey(section.getString("type", ""));
            String world = section == null ? null : section.getString("world");
            if (!ArenaRegistry.validName(name) || type.isEmpty() || world == null || world.isBlank()) {
                plugin.getLogger().warning("Skipping hologram '" + name + "' in holograms.yml: it needs a lowercase name, a type and a world");
                continue;
            }
            boards.put(name, new Board(name, type.get(), section.getString("kit"), world,
                    new Position(section.getDouble("x"), section.getDouble("y"), section.getDouble("z"), 0, 0)));
        }
    }

    private void write(Board board) {
        YamlConfiguration yaml = file.get();
        String path = ROOT + "." + board.name();
        yaml.set(path, null);
        yaml.set(path + ".type", board.type().key());
        yaml.set(path + ".kit", board.kit());
        yaml.set(path + ".world", board.world());
        yaml.set(path + ".x", board.position().x());
        yaml.set(path + ".y", board.position().y());
        yaml.set(path + ".z", board.position().z());
    }
}
