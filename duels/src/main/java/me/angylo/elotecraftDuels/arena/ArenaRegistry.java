package me.angylo.elotecraftDuels.arena;

import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftDuels.arena.Arena.Position;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Arenas stored in arenas.yml. Every change is saved right away. Main thread only.
 */
public final class ArenaRegistry {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Names Windows reserves for devices; they are also file names (arena snapshots). */
    private static final Pattern RESERVED = Pattern.compile("con|prn|aux|nul|com[0-9]|lpt[0-9]");
    private static final String ROOT = "arenas";
    /** Marks an arena entry as a copy made by the old {@code /duels arena pregen}. */
    private static final String OLD_COPY = "copy-of";
    /** Arenas a build duel changed and that were not put back yet, e.g. after a crash. */
    private static final String NEEDS_RESET = "needs-reset";
    /** Boxes of the arenas world holding pasted copies, cleared at the next start if still there. */
    private static final String POOL_BOXES = "pool-boxes";

    private final Plugin plugin;
    private final Logger logger;
    private final ConfigFile file;
    private final Map<String, Arena> arenas = new TreeMap<>();
    private final Set<String> needsReset = new TreeSet<>();
    private final Set<String> poolBoxes = new TreeSet<>();

    public ArenaRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.file = new ConfigFile(plugin, "arenas.yml");
        load();
        needsReset.addAll(file.get().getStringList(NEEDS_RESET));
        poolBoxes.addAll(file.get().getStringList(POOL_BOXES));
        dropOldCopies();
    }

    /** Arena and kit names are YAML keys and command arguments, so they are kept simple. */
    public static boolean validName(String name) {
        return NAME.matcher(name).matches() && !RESERVED.matcher(name).matches();
    }

    public Optional<Arena> get(String name) {
        return Optional.ofNullable(arenas.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every arena, sorted by name. */
    public List<Arena> all() {
        return List.copyOf(arenas.values());
    }

    public List<String> names() {
        return List.copyOf(arenas.keySet());
    }

    /**
     * Adds a new arena in {@code location}'s world.
     *
     * @throws IllegalArgumentException if the name is invalid or taken
     */
    public CompletableFuture<Arena> create(String name, Location location) {
        if (!validName(name) || arenas.containsKey(name)) {
            throw new IllegalArgumentException("Invalid or taken arena name: " + name);
        }
        Arena arena = Arena.create(name, location.getWorld().getName());
        return update(arena).thenApply(ignored -> arena);
    }

    /** Stores {@code arena}, replacing the one with its name, and saves the file. */
    public CompletableFuture<Void> update(Arena arena) {
        arenas.put(arena.name(), arena);
        write(arena);
        return file.save();
    }

    public CompletableFuture<Void> delete(String name) {
        arenas.remove(name);
        file.get().set(ROOT + "." + name, null);
        if (needsReset.remove(name)) {
            file.get().set(NEEDS_RESET, List.copyOf(needsReset));
        }
        return file.save();
    }

    /** Arenas a build duel changed that were not put back yet; see {@link #needsReset(String, boolean)}. */
    public Set<String> needingReset() {
        return Set.copyOf(needsReset);
    }

    public boolean needsReset(String arena) {
        return needsReset.contains(arena);
    }

    /**
     * Marks {@code arena} as changed by a build duel, or as put back. Saved right away, so a crash in
     * between leaves the mark for the next start to rebuild the arena.
     */
    public void needsReset(String arena, boolean value) {
        if (value ? needsReset.add(arena) : needsReset.remove(arena)) {
            file.get().set(NEEDS_RESET, List.copyOf(needsReset));
            // While disabling, async saves are refused; saveNow() writes it then.
            if (plugin.isEnabled()) {
                file.save().exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not save arenas.yml", error);
                    return null;
                });
            }
        }
    }

    /** Boxes of the arenas world that hold pasted copies; see {@link #poolBox(BoundingBox, boolean)}. */
    public List<BoundingBox> poolBoxes() {
        return poolBoxes.stream().map(ArenaRegistry::box).filter(Objects::nonNull).toList();
    }

    /**
     * Marks {@code box} of the arenas world as holding a copy, or as cleared. Saved right away, so copies
     * a crash or a restart left behind are cleared at the next start.
     */
    public void poolBox(BoundingBox box, boolean value) {
        String key = (int) box.getMinX() + "," + (int) box.getMinY() + "," + (int) box.getMinZ() + ","
                + (int) box.getMaxX() + "," + (int) box.getMaxY() + "," + (int) box.getMaxZ();
        if (value ? poolBoxes.add(key) : poolBoxes.remove(key)) {
            file.get().set(POOL_BOXES, List.copyOf(poolBoxes));
            if (plugin.isEnabled()) {
                file.save().exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not save arenas.yml", error);
                    return null;
                });
            }
        }
    }

    /**
     * Reloads arenas.yml; on a parse error the arenas in memory are kept and false is returned. Reset marks
     * are kept: a mark whose save is still pending must not be lost.
     */
    public boolean reload() {
        if (!file.reload()) {
            return false;
        }
        load();
        return true;
    }

    /** Blocking save for {@code onDisable}, in case an async save was still pending. */
    public void saveNow() {
        file.saveNow();
    }

    private void load() {
        arenas.clear();
        ConfigurationSection root = file.get().getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section != null && section.isConfigurationSection(OLD_COPY)) {
                continue;
            }
            String world = section == null ? null : section.getString("world");
            if (!validName(name) || world == null || world.isBlank()) {
                logger.warning("Skipping arena '" + name + "' in arenas.yml: it needs a lowercase name and a world");
                continue;
            }
            arenas.put(name, new Arena(name, section.getString("display-name", name), icon(section),
                    world, section.getBoolean("enabled", true),
                    position(section, "spawn1"), position(section, "spawn2"), position(section, "spectator"),
                    position(section, "corner1"), position(section, "corner2"), categories(section),
                    position(section, "center"), section.isInt("build-limit") ? section.getInt("build-limit") : null,
                    null, extraSpawns(section), new Arena.ModePoints(position(section, "goal1"), position(section, "goal2"),
                    position(section, "bed1"), position(section, "bed2"))));
        }
    }

    private Set<String> categories(ConfigurationSection section) {
        Set<String> categories = new HashSet<>();
        for (String raw : section.getStringList("categories")) {
            String category = raw.strip().toLowerCase(Locale.ROOT);
            if (validName(category)) {
                categories.add(category);
            } else {
                logger.warning("Arena '" + section.getName() + "' has an invalid category '" + raw + "'; skipping it");
            }
        }
        return categories;
    }

    private static List<Position> extraSpawns(ConfigurationSection arena) {
        ConfigurationSection section = arena.getConfigurationSection("extra-spawns");
        if (section == null) {
            return List.of();
        }
        return section.getKeys(false).stream().sorted(Comparator.comparingInt(ArenaRegistry::number))
                .map(key -> position(section, key)).filter(Objects::nonNull).toList();
    }

    private static int number(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Copies made by {@code /duels arena pregen} before copies were pasted on demand: their entries go, and
     * their boxes are cleared like copies a restart left behind.
     */
    private void dropOldCopies() {
        ConfigurationSection root = file.get().getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }
        List<String> old = root.getKeys(false).stream().filter(name -> root.isConfigurationSection(name + "." + OLD_COPY)).toList();
        for (String name : old) {
            ConfigurationSection section = root.getConfigurationSection(name);
            Position corner1 = position(section, "corner1");
            Position corner2 = position(section, "corner2");
            if (corner1 != null && corner2 != null) {
                poolBox(Arena.create(name, section.getString("world", "")).withCorner(1, corner1).withCorner(2, corner2).bounds(), true);
            }
            root.set(name, null);
            needsReset.remove(name);
        }
        if (!old.isEmpty()) {
            logger.info("Removed " + old.size() + " arena copies made by /duels arena pregen; copies are now pasted when needed");
            file.get().set(NEEDS_RESET, List.copyOf(needsReset));
            file.get().set(POOL_BOXES, List.copyOf(poolBoxes));
            file.save().exceptionally(error -> {
                logger.log(Level.WARNING, "Could not save arenas.yml", error);
                return null;
            });
        }
    }

    private static BoundingBox box(String key) {
        String[] parts = key.split(",");
        if (parts.length != 6) {
            return null;
        }
        try {
            int[] values = Arrays.stream(parts).mapToInt(part -> Integer.parseInt(part.strip())).toArray();
            return new BoundingBox(values[0], values[1], values[2], values[3], values[4], values[5]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Material icon(ConfigurationSection section) {
        String raw = section.getString("icon", Arena.DEFAULT_ICON.name());
        Material icon = Material.matchMaterial(raw);
        if (icon == null || !icon.isItem() || icon.isAir()) {
            logger.warning("Arena '" + section.getName() + "' has an unknown icon '" + raw + "'; using " + Arena.DEFAULT_ICON);
            return Arena.DEFAULT_ICON;
        }
        return icon;
    }

    private static Position position(ConfigurationSection arena, String key) {
        ConfigurationSection section = arena.getConfigurationSection(key);
        if (section == null) {
            return null;
        }
        return new Position(section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    private void write(Arena arena) {
        YamlConfiguration yaml = file.get();
        String path = ROOT + "." + arena.name();
        yaml.set(path, null);
        yaml.set(path + ".display-name", arena.displayName());
        yaml.set(path + ".icon", arena.icon().name());
        yaml.set(path + ".world", arena.world());
        yaml.set(path + ".enabled", arena.enabled());
        writePosition(yaml, path + ".spawn1", arena.spawn1());
        writePosition(yaml, path + ".spawn2", arena.spawn2());
        writePosition(yaml, path + ".spectator", arena.spectator());
        writePosition(yaml, path + ".corner1", arena.corner1());
        writePosition(yaml, path + ".corner2", arena.corner2());
        if (!arena.categories().isEmpty()) {
            yaml.set(path + ".categories", arena.categories().stream().sorted().toList());
        }
        writePosition(yaml, path + ".center", arena.center());
        writePosition(yaml, path + ".goal1", arena.points().goal1());
        writePosition(yaml, path + ".goal2", arena.points().goal2());
        writePosition(yaml, path + ".bed1", arena.points().bed1());
        writePosition(yaml, path + ".bed2", arena.points().bed2());
        for (int i = 0; i < arena.extraSpawns().size(); i++) {
            writePosition(yaml, path + ".extra-spawns." + (i + 1), arena.extraSpawns().get(i));
        }
        if (arena.buildLimit() != null) {
            yaml.set(path + ".build-limit", arena.buildLimit());
        }
    }

    private static void writePosition(YamlConfiguration yaml, String path, Position position) {
        if (position == null) {
            return;
        }
        yaml.set(path + ".x", position.x());
        yaml.set(path + ".y", position.y());
        yaml.set(path + ".z", position.z());
        yaml.set(path + ".yaw", position.yaw());
        yaml.set(path + ".pitch", position.pitch());
    }
}
