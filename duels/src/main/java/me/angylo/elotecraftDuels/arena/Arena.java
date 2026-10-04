package me.angylo.elotecraftDuels.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A place for one duel at a time. Every position is in {@link #world()}; the corners are opposite blocks
 * of a box around the whole arena. Immutable: the {@code with...} methods return a changed copy.
 *
 * @param displayName MiniMessage, set by admins
 */
public record Arena(String name, String displayName, Material icon, String world, boolean enabled,
                    Position spawn1, Position spawn2, Position spectator, Position corner1, Position corner2) {

    /** Why an arena cannot host a duel; see {@link #problems()}. */
    public enum Problem {
        WORLD_NOT_LOADED, MISSING_SPAWN_1, MISSING_SPAWN_2, MISSING_CORNERS, SPAWN_OUTSIDE, SPECTATOR_OUTSIDE, DISABLED;

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
    }

    public static final Material DEFAULT_ICON = Material.GRASS_BLOCK;

    static Arena create(String name, String world) {
        return new Arena(name, name, DEFAULT_ICON, world, true, null, null, null, null, null);
    }

    /** @param number 1 or 2 */
    public Arena withSpawn(int number, Position position) {
        return number == 1
                ? new Arena(name, displayName, icon, world, enabled, position, spawn2, spectator, corner1, corner2)
                : new Arena(name, displayName, icon, world, enabled, spawn1, position, spectator, corner1, corner2);
    }

    /** @param number 1 or 2 */
    public Arena withCorner(int number, Position position) {
        return number == 1
                ? new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, spectator, position, corner2)
                : new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, spectator, corner1, position);
    }

    public Arena withSpectator(Position position) {
        return new Arena(name, displayName, icon, world, enabled, spawn1, spawn2, position, corner1, corner2);
    }

    public Arena withIcon(Material newIcon) {
        return new Arena(name, displayName, newIcon, world, enabled, spawn1, spawn2, spectator, corner1, corner2);
    }

    public Arena withDisplayName(String newDisplayName) {
        return new Arena(name, newDisplayName, icon, world, enabled, spawn1, spawn2, spectator, corner1, corner2);
    }

    public Arena withEnabled(boolean newEnabled) {
        return new Arena(name, displayName, icon, world, newEnabled, spawn1, spawn2, spectator, corner1, corner2);
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
        }
        if (!enabled) {
            problems.add(Problem.DISABLED);
        }
        return problems;
    }

    public boolean isReady() {
        return problems().isEmpty();
    }

    /** Whether {@code location} is inside the corners; false while they are not both set. */
    public boolean contains(Location location) {
        return corner1 != null && corner2 != null && location.getWorld() != null
                && location.getWorld().getName().equals(world)
                && bounds().contains(location.getX(), location.getY(), location.getZ());
    }

    /**
     * Where fighter 1 or 2 starts. Only for a {@link #isReady() ready} arena.
     *
     * @param number 1 or 2
     */
    public Location spawn(int number) {
        return (number == 1 ? spawn1 : spawn2).in(Bukkit.getWorld(world));
    }

    /** The spectator spawn, or halfway between the fighter spawns if none is set. Only for a ready arena. */
    public Location spectatorSpawn() {
        Position position = spectator != null ? spectator : new Position((spawn1.x() + spawn2.x()) / 2,
                (spawn1.y() + spawn2.y()) / 2, (spawn1.z() + spawn2.z()) / 2, spawn1.yaw(), 0);
        return position.in(Bukkit.getWorld(world));
    }

    /** The box covering both corner blocks fully. */
    private BoundingBox bounds() {
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
}
