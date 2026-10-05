package me.angylo.elotecraftDuels.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A place for duels. Every position is in {@link #world()}; the corners are opposite blocks of a box around
 * the whole arena. Duels run in that world, one at a time (see {@link ArenaInstances}). Immutable: the
 * {@code with...} methods return a changed copy.
 *
 * @param displayName MiniMessage, set by admins
 * @param categories  arena pools kits pick from; empty means only kits that accept any arena use it
 * @param center      optional middle of the arena; where spectators appear when no spectator point is set
 * @param buildLimit  highest block Y fighters may place blocks at, or null for the whole box
 * @param copy        where this arena was pasted from by pregen, or null for an arena built by hand
 */
public record Arena(String name, String displayName, Material icon, String world, boolean enabled,
                    Position spawn1, Position spawn2, Position spectator, Position corner1, Position corner2,
                    Set<String> categories, Position center, Integer buildLimit, Copy copy) {

    /** Why an arena cannot host a duel; see {@link #problems()}. */
    public enum Problem {
        WORLD_NOT_LOADED, MISSING_SPAWN_1, MISSING_SPAWN_2, MISSING_CORNERS, SPAWN_OUTSIDE, SPECTATOR_OUTSIDE,
        CENTER_OUTSIDE, DISABLED;

        /** Key under {@code admin.arena.problems} in messages.yml. */
        public String messageKey() {
            return "admin.arena.problems." + name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** A point with a facing; nullable fields of {@link Arena} use null for "not set". */
    public record Position(double x, double y, double z, float yaw, float pitch) {

        public static Position of(Location location) {
            return new Position(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }

        public Location in(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }

        Position offset(int dx, int dy, int dz) {
            return new Position(x + dx, y + dy, z + dz, yaw, pitch);
        }
    }

    /** A pregen copy: pasted from arena {@code source}'s snapshot, moved by the offset. */
    public record Copy(String source, int dx, int dy, int dz) {
    }

    public static final Material DEFAULT_ICON = Material.GRASS_BLOCK;

    public Arena {
        categories = Set.copyOf(categories);
    }

    static Arena create(String name, String world) {
        return new Arena(name, name, DEFAULT_ICON, world, true, null, null, null, null, null, Set.of(), null, null, null);
    }

    /** @param number 1 or 2 */
    public Arena withSpawn(int number, Position position) {
        return number == 1
                ? new Arena(name, displayName, icon, world, enabled, position, spawn2, spectator, corner1, corner2, categories, center, buildLimit, copy)
                : new Arena(name, displayName, icon, world, enabled, spawn1, position, spectator, corner1, corner2, categories, center, buildLimit, copy);
    }

    /** @param number 1 or 2 */
    public Arena withCorner(int number, Position position) {
        return number == 1
                ? new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, spectator, position, corner2, categories, center, buildLimit, copy)
                : new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, spectator, corner1, position, categories, center, buildLimit, copy);
    }

    public Arena withSpectator(Position position) {
        return new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, position, corner1, corner2, categories, center, buildLimit, copy);
    }

    public Arena withIcon(Material newIcon) {
        return new Arena(name, displayName, newIcon, world, enabled, spawn1, spawn2, spectator, corner1, corner2, categories, center, buildLimit, copy);
    }

    public Arena withDisplayName(String newDisplayName) {
        return new Arena(name, newDisplayName, icon, world, enabled, spawn1, spawn2, spectator, corner1, corner2, categories, center, buildLimit, copy);
    }

    public Arena withEnabled(boolean newEnabled) {
        return new Arena(name, displayName, icon, world, newEnabled, spawn1, spawn2, spectator, corner1, corner2, categories, center, buildLimit, copy);
    }

    public Arena withCategories(Set<String> newCategories) {
        return new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, spectator, corner1, corner2, newCategories, center, buildLimit, copy);
    }

    public Arena withCenter(Position position) {
        return new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, spectator, corner1, corner2, categories, position, buildLimit, copy);
    }

    /** @param newBuildLimit null for no limit */
    public Arena withBuildLimit(Integer newBuildLimit) {
        return new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, spectator, corner1, corner2, categories, center, newBuildLimit, copy);
    }

    /**
     * A pregen copy of this arena named {@code copyName} in {@code copyWorld}, every point moved by the
     * offset. Only for a {@link #isReady() ready} arena.
     */
    public Arena copyAt(String copyName, String copyWorld, int dx, int dy, int dz) {
        return new Arena(copyName, displayName, icon, copyWorld, true, spawn1.offset(dx, dy, dz), spawn2.offset(dx, dy, dz),
                offset(spectator, dx, dy, dz), corner1.offset(dx, dy, dz), corner2.offset(dx, dy, dz), categories,
                offset(center, dx, dy, dz), buildLimit == null ? null : buildLimit + dy, new Copy(name, dx, dy, dz));
    }

    /** Everything stopping a duel here; empty means ready. */
    public List<Problem> problems() {
        List<Problem> problems = new ArrayList<>();
        if (Bukkit.getWorld(world) == null) {
            problems.add(Problem.WORLD_NOT_LOADED);
        }
        if (spawn1 == null) {
            problems.add(Problem.MISSING_SPAWN_1);
        }
        if (spawn2 == null) {
            problems.add(Problem.MISSING_SPAWN_2);
        }
        if (corner1 == null || corner2 == null) {
            problems.add(Problem.MISSING_CORNERS);
        } else {
            BoundingBox bounds = bounds();
            if (outside(bounds, spawn1) || outside(bounds, spawn2)) {
                problems.add(Problem.SPAWN_OUTSIDE);
            }
            if (outside(bounds, spectator)) {
                problems.add(Problem.SPECTATOR_OUTSIDE);
            }
            if (outside(bounds, center)) {
                problems.add(Problem.CENTER_OUTSIDE);
            }
        }
        if (!enabled) {
            problems.add(Problem.DISABLED);
        }
        return problems;
    }

    public boolean isReady() {
        return problems().isEmpty();
    }

    /** Whether fighters may place a block at height {@code y}; the box limits them anyway. */
    public boolean allowsBuildingAt(int y) {
        return buildLimit == null || y <= buildLimit;
    }

    /** Whether {@code location} is inside the corners in the arena's world; false while they are not both set. */
    public boolean contains(Location location) {
        return location.getWorld() != null && location.getWorld().getName().equals(world) && inBox(location);
    }

    /** Whether {@code location} is inside the corners, in whatever world; false while they are not both set. */
    public boolean inBox(Location location) {
        return corner1 != null && corner2 != null && bounds().contains(location.getX(), location.getY(), location.getZ());
    }

    /**
     * Where fighter 1 or 2 starts, in {@code in} (the arena's world). Only for a {@link #isReady() ready}
     * arena.
     *
     * @param number 1 or 2
     */
    public Location spawn(int number, World in) {
        return (number == 1 ? spawn1 : spawn2).in(in);
    }

    /** The spectator spawn in the arena's world. Only for a ready arena. */
    public Location spectatorSpawn() {
        return spectatorSpawn(Bukkit.getWorld(world));
    }

    /**
     * The spectator spawn in {@code in}: the spectator point, else the center, else halfway between the
     * fighter spawns. Only for a ready arena.
     */
    public Location spectatorSpawn(World in) {
        Position position = spectator != null ? spectator : center != null ? center : new Position((spawn1.x() + spawn2.x()) / 2,
                (spawn1.y() + spawn2.y()) / 2, (spawn1.z() + spawn2.z()) / 2, spawn1.yaw(), 0);
        return position.in(in);
    }

    /** The box covering both corner blocks fully. Only while both corners are set. */
    public BoundingBox bounds() {
        return new BoundingBox(
                Math.min(Math.floor(corner1.x()), Math.floor(corner2.x())),
                Math.min(Math.floor(corner1.y()), Math.floor(corner2.y())),
                Math.min(Math.floor(corner1.z()), Math.floor(corner2.z())),
                Math.max(Math.floor(corner1.x()), Math.floor(corner2.x())) + 1,
                Math.max(Math.floor(corner1.y()), Math.floor(corner2.y())) + 1,
                Math.max(Math.floor(corner1.z()), Math.floor(corner2.z())) + 1);
    }

    private static boolean outside(BoundingBox bounds, Position position) {
        return position != null && !bounds.contains(position.x(), position.y(), position.z());
    }

    private static Position offset(Position position, int dx, int dy, int dz) {
        return position == null ? null : position.offset(dx, dy, dz);
    }
}
