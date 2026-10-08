package me.angylo.elotecraftDuels.arena;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.hook.WorldEditHook;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Copies of arenas, pasted with WorldEdit in the arenas world while every place of an arena is busy, so an
 * arena hosts as many duels as players need. A copy is pasted from the arena's schematic snapshot
 * ({@code arenas/<arena>.schem}, saved by {@code /duels arena snapshot}), lent to one duel at a time and
 * cleared once it stayed free for {@code arenas.pool.idle-timeout}, keeping {@code arenas.pool.warm} places
 * ready. Copies live in memory only; their boxes are recorded in arenas.yml, so a restart clears what was
 * left. Main thread only.
 */
public final class ArenaPool {

    /** Copies per grid row. */
    private static final int COLUMNS = 8;
    private static final long MILLIS_PER_TICK = 50;
    /** Ticks a copy stays after its duel before it may be cleared: players are still teleporting out. */
    private static final int GRACE_TICKS = 100;

    /** A copy lent to a duel: the copy, and when all its blocks are pasted. */
    public record Lease(Arena arena, CompletableFuture<Void> ready) {
    }

    /** How many copies of an arena there are, lent to duels and free. */
    public record Count(int inUse, int free) {
    }

    /** One copy: what it was pasted from, where, and whether a duel has it. */
    private static final class Slot {
        private final Arena source;
        private final WorldEditHook.Copy template;
        private final Arena copy;
        private final CompletableFuture<Void> pasted;
        private boolean leased;
        private int freeSince;

        private Slot(Arena source, WorldEditHook.Copy template, Arena copy, CompletableFuture<Void> pasted) {
            this.source = source;
            this.template = template;
            this.copy = copy;
            this.pasted = pasted;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final World world;
    private final WorldEditHook worldEdit;
    /** Snapshots copies are pasted from, by arena. */
    private final Map<String, WorldEditHook.Copy> templates = new HashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    /** Boxes being cleared, or reserved for a schematic import; nothing is pasted there until done. */
    private final List<BoundingBox> clearing = new ArrayList<>();

    /** @param world the arenas world, or null if it could not be loaded (no copies then) */
    public ArenaPool(Plugin plugin, Supplier<Settings> settings, ArenaRegistry arenas, World world, WorldEditHook worldEdit) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.arenas = arenas;
        this.world = world;
        this.worldEdit = worldEdit;
        if (world != null) {
            arenas.poolBoxes().forEach(this::clearBox);
        }
        arenas.all().forEach(this::loadTemplate);
    }

    public World world() {
        return world;
    }

    /** Whether the arenas world is loaded, so copies can be made. */
    public boolean isAvailable() {
        return world != null;
    }

    public String worldName() {
        return world == null ? settings.get().arenasWorld() : world.getName();
    }

    /** Whether copies of {@code arena} can be pasted: it has a snapshot of its current box. */
    public boolean hasTemplate(Arena arena) {
        WorldEditHook.Copy template = templates.get(arena.name());
        if (template == null || arena.corner1() == null || arena.corner2() == null) {
            return false;
        }
        int[] size = template.size();
        BoundingBox box = arena.bounds();
        return size[0] == (int) box.getWidthX() && size[1] == (int) box.getHeight() && size[2] == (int) box.getWidthZ();
    }

    /** Copies of {@code arena} that duels have and that are free. */
    public Count count(String arena) {
        int inUse = 0;
        int free = 0;
        for (Slot slot : slots) {
            if (slot.source.name().equals(arena)) {
                if (slot.leased) {
                    inUse++;
                } else {
                    free++;
                }
            }
        }
        return new Count(inUse, free);
    }

    /** Whether {@link #lease} would give a copy of {@code base} now. */
    public boolean canLease(Arena base) {
        return freeSlot(base).isPresent() || canGrow(base);
    }

    /**
     * Lends a copy of {@code base} to a duel: a free one, else a new one, pasted now. Give it back with
     * {@link #giveBack}.
     *
     * @return empty if no copy can be had
     */
    public Optional<Lease> lease(Arena base) {
        Slot slot = freeSlot(base).orElse(null);
        if (slot == null) {
            if (!canGrow(base)) {
                return Optional.empty();
            }
            slot = grow(base);
        }
        slot.leased = true;
        return Optional.of(new Lease(slot.copy, slot.pasted));
    }

    /** Ends a duel's use of {@code copy}, once its blocks are back; it waits for the next duel. */
    public void giveBack(Arena copy) {
        for (Slot slot : slots) {
            if (slot.copy == copy) {
                slot.leased = false;
                slot.freeSince = Bukkit.getCurrentTick();
                return;
            }
        }
    }

    /**
     * Saves {@code base}'s blocks as the snapshot its copies are pasted from; free copies of the old one are
     * cleared. Callers check that its world is loaded, its corners are set and no duel changes it.
     */
    public CompletableFuture<Void> snapshot(Arena base) {
        World source = Bukkit.getWorld(base.world());
        if (source == null || base.corner1() == null || base.corner2() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Arena " + base.name() + " has no loaded world or corners"));
        }
        BoundingBox box = base.bounds();
        return worldEdit.copy(source, box).thenCompose(copy -> worldEdit.save(copy, file(base.name())).thenRun(() -> {
            // Corners changed meanwhile: forget() dropped the snapshot, and this one is of the old box.
            if (arenas.get(base.name()).filter(arena -> arena.corner1() != null && arena.corner2() != null
                    && arena.bounds().equals(box)).isPresent()) {
                templates.put(base.name(), copy);
            }
        }));
    }

    /** Drops {@code arena}'s snapshot, e.g. after its box changed; its free copies are cleared. */
    public void forget(String arena) {
        templates.remove(arena);
        Path file = file(arena);
        Tasks.supplyAsync(plugin, () -> {
            try {
                return Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not delete " + file, error);
            return false;
        });
        clearFree(arena);
    }

    /**
     * Clears every free copy of {@code arena} now, but for copies whose duel just ended.
     *
     * @return how many were cleared
     */
    public int clearFree(String arena) {
        int cleared = 0;
        for (Slot slot : List.copyOf(slots)) {
            if (slot.source.name().equals(arena) && removable(slot)) {
                remove(slot);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Once a second: clears free copies that no longer match their arena or stayed free too long, and
     * pastes one ahead for an arena with fewer than {@code arenas.pool.warm} places ready.
     *
     * @param baseFree whether an arena itself can take a duel now
     */
    public void maintain(Predicate<Arena> baseFree) {
        for (Slot slot : List.copyOf(slots)) {
            if (removable(slot) && !current(slot)) {
                remove(slot);
            }
        }
        Settings.Pool pool = settings.get().pool();
        long timeoutTicks = pool.idleTimeout().toMillis() / MILLIS_PER_TICK;
        int now = Bukkit.getCurrentTick();
        for (Arena base : arenas.all()) {
            if (!base.isReady()) {
                continue;
            }
            List<Slot> free = slots.stream().filter(slot -> !slot.leased && slot.source.name().equals(base.name()))
                    .sorted(Comparator.comparingInt(slot -> slot.freeSince)).toList();
            int ready = free.size() + (baseFree.test(base) ? 1 : 0);
            if (ready < pool.warm()) {
                if (canGrow(base)) {
                    grow(base);
                }
                continue;
            }
            for (Slot slot : free) {
                if (ready <= pool.warm()) {
                    break;
                }
                if (slot.pasted.isDone() && now - slot.freeSince >= timeoutTicks) {
                    remove(slot);
                    ready--;
                }
            }
        }
    }

    /**
     * Reserves a free place in the arenas world for a box of this size, at height {@code y}, so no copy is pasted
     * there; {@link #unreserve} it once the box is an arena or the paste failed.
     */
    public BoundingBox reserve(int sizeX, int sizeY, int sizeZ, int y) {
        int[] offset = place(new BoundingBox(0, y, 0, sizeX, y + sizeY, sizeZ));
        BoundingBox box = new BoundingBox(offset[0], y, offset[1], offset[0] + sizeX, y + sizeY, offset[1] + sizeZ);
        clearing.add(box);
        return box;
    }

    public void unreserve(BoundingBox box) {
        clearing.remove(box);
    }

    /** Whether {@code slot} may be cleared: free, pasted, and its duel's players had time to leave. */
    private static boolean removable(Slot slot) {
        return !slot.leased && slot.pasted.isDone() && Bukkit.getCurrentTick() - slot.freeSince >= GRACE_TICKS;
    }

    /** A free, current copy of {@code base}, pasted ones first. */
    private Optional<Slot> freeSlot(Arena base) {
        return slots.stream().filter(slot -> !slot.leased && slot.source.equals(base) && current(slot))
                .min(Comparator.comparing(slot -> !slot.pasted.isDone()));
    }

    private boolean canGrow(Arena base) {
        return world != null && base.isReady() && hasTemplate(base)
                && slots.stream().filter(slot -> slot.source.name().equals(base.name())).count() < settings.get().pool().maxCopies();
    }

    /** Whether {@code slot} still matches its arena and its snapshot. */
    private boolean current(Slot slot) {
        return arenas.get(slot.source.name()).filter(slot.source::equals).isPresent()
                && templates.get(slot.source.name()) == slot.template;
    }

    /** Pastes a new free copy of {@code base}; callers check {@link #canGrow} first. */
    private Slot grow(Arena base) {
        int[] offset = place(base.bounds());
        Arena copy = base.copyAt(world.getName(), offset[0], 0, offset[1]);
        BoundingBox box = copy.bounds();
        WorldEditHook.Copy template = templates.get(base.name());
        // Recorded before pasting: a crash mid-paste leaves blocks too.
        arenas.poolBox(box, true);
        CompletableFuture<Void> pasted = new CompletableFuture<>();
        Slot slot = new Slot(base, template, copy, pasted);
        slot.freeSince = Bukkit.getCurrentTick();
        slots.add(slot);
        worldEdit.paste(template, world, (int) box.getMinX(), (int) box.getMinY(), (int) box.getMinZ())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Could not paste a copy of arena " + base.name(), error);
                        slots.remove(slot);
                        clearBox(box);
                        pasted.completeExceptionally(error);
                    } else {
                        pasted.complete(null);
                    }
                });
        return slot;
    }

    private void remove(Slot slot) {
        slots.remove(slot);
        clearBox(slot.copy.bounds());
    }

    /** Sets {@code box} to air; it is free for copies once done. */
    private void clearBox(BoundingBox box) {
        clearing.add(box);
        worldEdit.clear(world, box).whenComplete((ignored, error) -> {
            clearing.remove(box);
            if (error == null) {
                arenas.poolBox(box, false);
            } else {
                // Still recorded, so the next start tries again.
                logger.log(Level.WARNING, "Could not clear an arena copy in " + world.getName() + " at " + box, error);
            }
        });
    }

    /** The offset of a free grid place for {@code source}, clear of every arena, copy and box being cleared by the spacing. */
    private int[] place(BoundingBox source) {
        int spacing = settings.get().pool().spacing();
        int cellX = (int) source.getWidthX() + spacing;
        int cellZ = (int) source.getWidthZ() + spacing;
        List<BoundingBox> taken = new ArrayList<>(clearing);
        slots.forEach(slot -> taken.add(slot.copy.bounds()));
        arenas.all().stream().filter(arena -> arena.world().equals(worldName()) && arena.corner1() != null && arena.corner2() != null)
                .forEach(arena -> taken.add(arena.bounds()));
        for (int cell = 0; ; cell++) {
            int dx = (cell % COLUMNS) * cellX - (int) source.getMinX();
            int dz = (cell / COLUMNS) * cellZ - (int) source.getMinZ();
            BoundingBox withSpacing = source.clone().shift(dx, 0, dz).expand(spacing - 1);
            if (taken.stream().noneMatch(withSpacing::overlaps)) {
                return new int[]{dx, dz};
            }
        }
    }

    /**
     * Reads {@code arena}'s snapshot. An arena with a block snapshot from before copies were pasted on demand
     * gets one from its blocks as they stand, once: nothing changes them at start.
     */
    private void loadTemplate(Arena arena) {
        worldEdit.load(file(arena.name())).whenComplete((copy, error) -> {
            if (error == null) {
                templates.putIfAbsent(arena.name(), copy);
            } else if (!(rootCause(error) instanceof NoSuchFileException)) {
                logger.log(Level.WARNING, "Could not read the snapshot of arena " + arena.name() + "; it gets no copies until"
                        + " /duels arena snapshot " + arena.name() + " saves it again", error);
            } else if (Files.isRegularFile(ArenaTemplate.file(plugin, arena.name())) && arena.isReady()
                    && !arenas.needsReset(arena.name())) {
                snapshot(arena).whenComplete((ignored, saveError) -> {
                    if (saveError != null) {
                        logger.log(Level.WARNING, "Could not save a snapshot of arena " + arena.name() + " for its copies", saveError);
                    }
                });
            }
        });
    }

    private Path file(String arena) {
        return plugin.getDataFolder().toPath().resolve("arenas").resolve(arena + ".schem");
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
