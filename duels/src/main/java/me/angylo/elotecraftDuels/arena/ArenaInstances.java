package me.angylo.elotecraftDuels.arena;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lends arenas to duels. An arena hosts one duel at a time, and while it is busy duels get copies of it from
 * the {@link ArenaPool}; after a build duel the changed blocks are put back a few per tick before the next
 * duel. Arenas a crash left changed are rebuilt from their template at start; copies are cleared instead.
 * Main thread only.
 */
public final class ArenaInstances {

    private static final long NEXT_TICK = 1;

    private final Plugin plugin;
    /** Marks mobs fighters spawned with eggs, so they go with the arena's leftovers. */
    private final NamespacedKey leftover;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final ArenaPool pool;
    /** Duels running, and arenas still being put back after one. */
    private final List<ArenaInstance> active = new ArrayList<>();
    /** Arenas being rebuilt from their template. */
    private final Set<String> resetting = new HashSet<>();

    public ArenaInstances(Plugin plugin, Supplier<Settings> settings, ArenaRegistry arenas, ArenaPool pool) {
        this.plugin = plugin;
        this.leftover = new NamespacedKey(plugin, "fight-leftover");
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.arenas = arenas;
        this.pool = pool;
        resetLeftovers();
    }

    /** Whether a duel can start in {@code arena} now: it is ready, and it or a copy of it is free. */
    public boolean available(Arena arena) {
        return arena.isReady() && (baseFree(arena) || pool.canLease(arena));
    }

    /** Whether a duel (running or being put back) or a rebuild uses {@code arena} itself, not a copy. */
    public boolean baseInUse(String arena) {
        return resetting.contains(arena) || active.stream().anyMatch(instance -> !instance.isCopy() && instance.arena().name().equals(arena));
    }

    /** Duels in {@code arena} and its copies, counting ones still being put back, and a rebuild from its template. */
    public int inUse(String arena) {
        int count = resetting.contains(arena) ? 1 : 0;
        for (ArenaInstance instance : active) {
            if (instance.arena().name().equals(arena)) {
                count++;
            }
        }
        return count;
    }

    /** Once a second: copies of busy arenas are pasted ahead, and copies free too long are cleared. */
    public void tick() {
        pool.maintain(this::baseFree);
    }

    /**
     * Reserves {@code arena} for a duel, or a copy of it while it is busy; a new copy is ready once
     * {@link ArenaInstance#ready()} completes. Callers check {@link #available} first.
     *
     * @param build whether fighters may change blocks
     * @return empty if the arena's world is not loaded or no copy can be had
     */
    public Optional<ArenaInstance> acquire(Arena arena, boolean build) {
        if (!baseFree(arena)) {
            return pool.lease(arena).map(lease -> add(new ArenaInstance(lease.arena(), pool.world(), build, lease.ready())));
        }
        World world = Bukkit.getWorld(arena.world());
        if (world == null) {
            return Optional.empty();
        }
        if (build) {
            arenas.needsReset(arena.name(), true);
        }
        return Optional.of(add(new ArenaInstance(arena, world, build, CompletableFuture.completedFuture(null))));
    }

    /**
     * Ends a duel's use of its arena once its players are sent back: leftover items and projectiles are
     * removed, then changed blocks are put back.
     */
    public void release(ArenaInstance instance) {
        if (instance.isClosing() || !active.contains(instance)) {
            return;
        }
        instance.close();
        clearLeftovers(instance);
        if (instance.isBuild()) {
            regenerate(instance);
        } else {
            done(instance);
        }
    }

    /**
     * Puts a duel's arena back between rounds, keeping it reserved: leftovers go at once, then a build
     * duel's changed blocks come back over the next ticks. Completes once a whole tick passed with nothing
     * left to put back, so water still flowing is caught too, or once the duel ended. The reset mark stays:
     * the next round changes the arena again.
     */
    public CompletableFuture<Void> resetRound(ArenaInstance instance) {
        clearLeftovers(instance);
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (instance.isBuild()) {
            restoreRound(instance, done, false);
        } else {
            done.complete(null);
        }
        return done;
    }

    private void restoreRound(ArenaInstance instance, CompletableFuture<Void> done, boolean emptyLastTick) {
        boolean empty = instance.changes().size() == 0;
        if (instance.isClosing() || (empty && emptyLastTick)) {
            done.complete(null);
            return;
        }
        try {
            instance.changes().restore(settings.get().regenBlocksPerTick());
        } catch (RuntimeException e) {
            done.completeExceptionally(e);
            return;
        }
        boolean emptyNow = instance.changes().size() == 0;
        Tasks.later(plugin, () -> restoreRound(instance, done, emptyNow), NEXT_TICK);
    }

    /** The duel (running or being put back) whose arena holds {@code location}. */
    public Optional<ArenaInstance> at(Location location) {
        for (ArenaInstance instance : active) {
            if (instance.contains(location)) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    /** Like {@link #at}, for build duels only; cheap when none runs, for frequent events such as water flowing. */
    public Optional<ArenaInstance> buildAt(Location location) {
        for (ArenaInstance instance : active) {
            if (instance.isBuild() && instance.contains(location)) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    /**
     * Rebuilds {@code arena} from the template saved with {@code /duels arena snapshot}. It takes no duels
     * meanwhile. Callers check that it is not in use and its world is loaded.
     *
     * @return the number of blocks changed; fails with {@link java.nio.file.NoSuchFileException} (as the
     * cause) if no template was saved
     */
    public CompletableFuture<Integer> reset(Arena arena) {
        World world = Bukkit.getWorld(arena.world());
        if (world == null || arena.corner1() == null || arena.corner2() == null || !resetting.add(arena.name())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Arena " + arena.name() + " cannot be reset now"));
        }
        CompletableFuture<Integer> done = new CompletableFuture<>();
        ArenaTemplate.load(plugin, arena.name()).whenComplete((template, error) -> Tasks.sync(plugin, () -> {
            Throwable problem = error != null || template.fits(arena) ? error
                    : new IllegalStateException("The snapshot of arena " + arena.name() + " was taken with other corners;"
                    + " take it again with /duels arena snapshot");
            if (problem != null) {
                resetting.remove(arena.name());
                done.completeExceptionally(problem);
                return;
            }
            paste(arena, world, template, done);
        }));
        return done;
    }

    /** Puts every arena back right away. For {@code onDisable}, after the duels ended. */
    public void shutdown() {
        for (ArenaInstance instance : List.copyOf(active)) {
            instance.close();
            clearLeftovers(instance);
            // Copies are cleared at the next start instead.
            if (instance.isBuild() && !instance.isCopy()) {
                restore(instance, Integer.MAX_VALUE);
            }
        }
        active.clear();
        // An unfinished rebuild keeps its mark and runs again on the next start.
        resetting.clear();
    }

    /** Whether {@code arena} itself (not a copy) can take a duel now. */
    private boolean baseFree(Arena arena) {
        // A mark with no duel left in the arena means a crash left it changed and it was not rebuilt yet.
        return !arenas.needsReset(arena.name()) && !baseInUse(arena.name());
    }

    private ArenaInstance add(ArenaInstance instance) {
        active.add(instance);
        return instance;
    }

    /** The arena is free for the next duel; a copy goes back to the pool. */
    private void done(ArenaInstance instance) {
        active.remove(instance);
        if (instance.isCopy()) {
            pool.giveBack(instance.arena());
        }
    }

    /** Rebuilds the arenas a crash left changed. */
    private void resetLeftovers() {
        for (String name : arenas.needingReset()) {
            Optional<Arena> arena = arenas.get(name);
            if (arena.isEmpty() || Bukkit.getWorld(arena.get().world()) == null) {
                logger.warning("Arena " + name + " was changed by a build duel that a crash cut short, but its world"
                        + " is not loaded; it takes no duels until /duels arena reset " + name + " rebuilds it");
                continue;
            }
            logger.info("Rebuilding arena " + name + ", changed by a build duel that a crash cut short; it takes no duels until then");
            reset(arena.get()).whenComplete((changed, error) -> {
                if (error == null) {
                    logger.info("Rebuilt arena " + name + " (" + changed + " blocks)");
                } else {
                    logger.log(Level.SEVERE, "Could not rebuild arena " + name + ", so it takes no duels. Fix it by hand"
                            + " and run /duels arena snapshot " + name + ", or /duels arena reset " + name + " if a snapshot exists", error);
                }
            });
        }
    }

    private void paste(Arena arena, World world, ArenaTemplate template, CompletableFuture<Integer> done) {
        if (!resetting.contains(arena.name())) {
            done.completeExceptionally(new IllegalStateException("Stopped by shutdown"));
            return;
        }
        try {
            if (!template.paste(world, settings.get().regenBlocksPerTick())) {
                Tasks.later(plugin, () -> paste(arena, world, template, done), NEXT_TICK);
                return;
            }
        } catch (RuntimeException e) {
            resetting.remove(arena.name());
            done.completeExceptionally(e);
            return;
        }
        resetting.remove(arena.name());
        arenas.needsReset(arena.name(), false);
        done.complete(template.changed());
    }

    /** Puts the blocks of a finished build duel back over the next ticks; the arena stays reserved until then. */
    private void regenerate(ArenaInstance instance) {
        if (!active.contains(instance)) {
            return;
        }
        if (restore(instance, settings.get().regenBlocksPerTick())) {
            // Sand or gravel still falling when the duel ended has landed by now.
            clearLeftovers(instance);
            done(instance);
        } else {
            Tasks.later(plugin, () -> regenerate(instance), NEXT_TICK);
        }
    }

    /** @return whether every block is back; the arena's reset mark is cleared then */
    private boolean restore(ArenaInstance instance, int budget) {
        boolean done;
        try {
            done = instance.changes().restore(budget);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not put a block of arena " + instance.arena().name() + " back", e);
            done = instance.changes().size() == 0;
        }
        if (done && !instance.isCopy()) {
            arenas.needsReset(instance.arena().name(), false);
        }
        return done;
    }

    /** Makes {@code entity}, spawned in a fight, go with its arena's leftovers. */
    public void markLeftover(Entity entity) {
        entity.getPersistentDataContainer().set(leftover, PersistentDataType.BOOLEAN, true);
    }

    /**
     * Removes arrows, tridents, pearls, dropped items, falling blocks, lit TNT and mobs from spawn eggs left in the arena:
     * kit items must not be picked up later, and a pearl landing after the duel would pull its thrower back in.
     */
    private void clearLeftovers(ArenaInstance instance) {
        instance.world().getNearbyEntities(instance.arena().bounds(), entity -> entity instanceof Projectile
                        || entity instanceof Item || entity instanceof FallingBlock || entity instanceof TNTPrimed
                        || entity.getPersistentDataContainer().has(leftover))
                .forEach(Entity::remove);
    }
}
