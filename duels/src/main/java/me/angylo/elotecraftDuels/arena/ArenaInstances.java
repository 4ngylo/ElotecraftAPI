package me.angylo.elotecraftDuels.arena;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.hook.SlimeWorlds;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
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
 * Lends arenas to duels. An arena in a normal world hosts one duel at a time; after a build duel its
 * changed blocks are put back a few per tick before the next duel. An arena in an AdvancedSlimePaper
 * template world gives every duel its own copy, unloaded afterwards, up to
 * {@code slime.copies-per-arena} at once. Arenas a crash left changed are rebuilt from their template at
 * start. Main thread only.
 */
public final class ArenaInstances {

    private static final long NEXT_TICK = 1;
    private static final long UNLOAD_RETRY_TICKS = 20;
    /** Seconds to wait for players to leave a copy before moving them out. */
    private static final int UNLOAD_ATTEMPTS = 10;

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final SlimeWorlds slime;
    /** Duels running, and arenas still being put back or unloaded after one. */
    private final List<ArenaInstance> active = new ArrayList<>();
    /** Arenas being rebuilt from their template. */
    private final Set<String> resetting = new HashSet<>();
    private int copies;

    /** @param slime null without AdvancedSlimePaper */
    public ArenaInstances(Plugin plugin, Supplier<Settings> settings, ArenaRegistry arenas, SlimeWorlds slime) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.arenas = arenas;
        this.slime = slime;
        resetLeftovers();
    }

    /** Whether a duel can start in {@code arena} now: it is ready and free, or has a copy to spare. */
    public boolean available(Arena arena) {
        // A mark with no duel left in the arena means a crash left it changed and it was not rebuilt yet.
        if (!arena.isReady() || arenas.needsReset(arena.name())) {
            return false;
        }
        int using = inUse(arena.name());
        return usesCopies(arena) ? using < settings.get().slimeCopiesPerArena() : using == 0;
    }

    /** Duels in {@code arena}, counting ones still being put back, and a rebuild from its template. */
    public int inUse(String arena) {
        int count = resetting.contains(arena) ? 1 : 0;
        for (ArenaInstance instance : active) {
            if (instance.arena().name().equals(arena)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Reserves {@code arena} for a duel. Callers check {@link #available} first.
     *
     * @param build whether fighters may change blocks
     * @return empty if a copy of the arena could not be made (logged)
     */
    public Optional<ArenaInstance> acquire(Arena arena, boolean build) {
        boolean copy = usesCopies(arena);
        World world = copy
                ? slime.copy(arena.world(), "duels_" + arena.name() + "_" + ++copies).orElse(null)
                : Bukkit.getWorld(arena.world());
        if (world == null) {
            return Optional.empty();
        }
        if (build && !copy) {
            arenas.needsReset(arena.name(), true);
        }
        ArenaInstance instance = new ArenaInstance(arena, world, copy, build);
        active.add(instance);
        return Optional.of(instance);
    }

    /**
     * Ends a duel's use of its arena once its players are sent back: leftover items and projectiles are
     * removed, then a copy is unloaded or changed blocks are put back.
     */
    public void release(ArenaInstance instance) {
        if (instance.isClosing() || !active.contains(instance)) {
            return;
        }
        instance.close();
        if (instance.isCopy()) {
            // Not this tick: a teleport into the copy may still be on its way.
            Tasks.later(plugin, () -> unloadWhenEmpty(instance, 0), UNLOAD_RETRY_TICKS);
            return;
        }
        clearLeftovers(instance);
        if (instance.isBuild()) {
            regenerate(instance);
        } else {
            active.remove(instance);
        }
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
     * Rebuilds {@code arena} from the template saved with {@code /duels arena snapshot}; it takes no duels
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
            Throwable problem = error != null || template.fits(arena) ? error : new IllegalStateException("The snapshot of arena "
                    + arena.name() + " was taken with other corners; take it again with /duels arena snapshot");
            if (problem != null) {
                resetting.remove(arena.name());
                done.completeExceptionally(problem);
                return;
            }
            paste(arena, world, template, done);
        }));
        return done;
    }

    /** Puts every arena back right away and unloads every copy. For {@code onDisable}, after the duels ended. */
    public void shutdown() {
        for (ArenaInstance instance : List.copyOf(active)) {
            instance.close();
            if (instance.isCopy()) {
                unload(instance);
                continue;
            }
            clearLeftovers(instance);
            if (instance.isBuild()) {
                restore(instance, Integer.MAX_VALUE);
            }
        }
        active.clear();
        // An unfinished rebuild keeps its mark and runs again on the next start.
        resetting.clear();
    }

    private boolean usesCopies(Arena arena) {
        return slime != null && slime.isTemplate(arena.world());
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
            active.remove(instance);
            // Sand or gravel still falling when the duel ended has landed by now.
            clearLeftovers(instance);
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
        if (done) {
            arenas.needsReset(instance.arena().name(), false);
        }
        return done;
    }

    /** Unloads a copy once its players are gone; ones still there after a while are moved out. */
    private void unloadWhenEmpty(ArenaInstance instance, int attempt) {
        if (!active.contains(instance)) {
            return;
        }
        if (!instance.world().getPlayers().isEmpty() && attempt < UNLOAD_ATTEMPTS) {
            Tasks.later(plugin, () -> unloadWhenEmpty(instance, attempt + 1), UNLOAD_RETRY_TICKS);
            return;
        }
        unload(instance);
    }

    private void unload(ArenaInstance instance) {
        active.remove(instance);
        World world = instance.world();
        Location fallback = Bukkit.getWorlds().getFirst().getSpawnLocation();
        for (Player player : world.getPlayers()) {
            player.teleport(fallback, TeleportCause.PLUGIN);
        }
        if (!Bukkit.unloadWorld(world, false)) {
            logger.warning("Could not unload " + world.getName() + ", the copy of arena " + instance.arena().name());
        }
    }

    /**
     * Removes arrows, tridents, pearls, dropped items, falling blocks and lit TNT left in the arena: kit
     * items must not be picked up later, and a pearl landing after the duel would pull its thrower back in.
     */
    private static void clearLeftovers(ArenaInstance instance) {
        instance.world().getNearbyEntities(instance.arena().bounds(), entity -> entity instanceof Projectile
                        || entity instanceof Item || entity instanceof FallingBlock || entity instanceof TNTPrimed)
                .forEach(Entity::remove);
    }
}
