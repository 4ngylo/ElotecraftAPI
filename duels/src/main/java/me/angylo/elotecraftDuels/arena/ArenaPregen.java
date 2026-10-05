package me.angylo.elotecraftDuels.arena;

import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftDuels.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /duels arena pregen}: pastes copies of an arena from its snapshot on a grid in the arenas world,
 * so one arena layout hosts several duels at once. Copies are pasted one after another,
 * {@code regen.blocks-per-tick} blocks per tick, and each takes duels once it is fully pasted. Main thread only.
 */
public final class ArenaPregen {

    private static final long NEXT_TICK = 1;
    /** Copies per grid row. */
    private static final int COLUMNS = 8;
    /** Blocks looked at per tick while clearing copies; air is cheap to skip. */
    private static final int CHECKS_PER_TICK = 65_536;

    /** Where one copy goes: the offset from its source arena. */
    private record Place(int dx, int dz) {
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final ArenaRegistry arenas;
    private final World world;
    /** Arenas whose copies are being pasted or cleared. */
    private final Set<String> busy = new HashSet<>();

    /** @param world the arenas world, or null if it could not be loaded (pregen is off) */
    public ArenaPregen(Plugin plugin, Supplier<Settings> settings, ArenaRegistry arenas, World world) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.arenas = arenas;
        this.world = world;
    }

    /** Whether the arenas world is loaded, so copies can be made. */
    public boolean isAvailable() {
        return world != null;
    }

    public String worldName() {
        return world == null ? settings.get().arenasWorld() : world.getName();
    }

    /** Whether {@code arena}'s copies are being pasted or cleared right now. */
    public boolean isBusy(String arena) {
        return busy.contains(arena);
    }

    /** The name of {@code base}'s copy number {@code number}. */
    public static String copyName(String base, int number) {
        return base + "-" + number;
    }

    /**
     * Pastes {@code count} copies of {@code base} named {@code <base>-1} and up. Callers check that the arenas
     * world is loaded, the base is ready and not a copy, it has no copies and the names are free.
     *
     * @param pasted told the number of copies done after each one
     * @return completes with {@code count}; fails with {@link java.nio.file.NoSuchFileException} (as the
     * cause) if the base has no snapshot, or with what stopped the paste
     */
    public CompletableFuture<Integer> pregen(Arena base, int count, IntConsumer pasted) {
        if (world == null || !busy.add(base.name())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pregen of " + base.name() + " cannot start now"));
        }
        CompletableFuture<Integer> done = new CompletableFuture<>();
        ArenaTemplate.load(plugin, base.name()).whenComplete((template, error) -> Tasks.sync(plugin, () -> {
            Throwable problem = error != null || template.fits(base) ? error : new IllegalStateException("The snapshot of arena "
                    + base.name() + " was taken with other corners; take it again with /duels arena snapshot");
            if (problem != null) {
                busy.remove(base.name());
                done.completeExceptionally(problem);
                return;
            }
            paste(base, template, places(base, count), 0, pasted, done);
        }));
        return done;
    }

    /**
     * Unregisters every copy of {@code base} and empties their boxes over the next ticks. Callers check
     * that none is in use.
     *
     * @return the number of copies removed
     */
    public int clear(Arena base) {
        List<Arena> copies = arenas.copiesOf(base.name());
        if (copies.isEmpty() || !busy.add(base.name())) {
            return 0;
        }
        List<BoundingBox> boxes = new ArrayList<>();
        for (Arena copy : copies) {
            boxes.add(copy.bounds());
            arenas.needsReset(copy.name(), false);
            arenas.delete(copy.name()).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not save arenas.yml after removing " + copy.name(), error);
                return null;
            });
        }
        World copiesWorld = Bukkit.getWorld(copies.getFirst().world());
        if (copiesWorld == null) {
            busy.remove(base.name());
        } else {
            erase(base.name(), copiesWorld, boxes, 0, 0);
        }
        return copies.size();
    }

    /** Free grid places for {@code count} copies, clear of every arena in the arenas world by the spacing. */
    private List<Place> places(Arena base, int count) {
        BoundingBox source = base.bounds();
        int spacing = settings.get().pregenSpacing();
        int cellX = (int) source.getWidthX() + spacing;
        int cellZ = (int) source.getWidthZ() + spacing;
        List<BoundingBox> taken = new ArrayList<>(arenas.all().stream()
                .filter(arena -> arena.world().equals(world.getName()) && arena.corner1() != null && arena.corner2() != null)
                .map(Arena::bounds).toList());
        List<Place> places = new ArrayList<>(count);
        for (int cell = 0; places.size() < count; cell++) {
            int dx = (cell % COLUMNS) * cellX - (int) source.getMinX();
            int dz = (cell / COLUMNS) * cellZ - (int) source.getMinZ();
            BoundingBox box = source.clone().shift(dx, 0, dz);
            BoundingBox withSpacing = box.clone().expand(spacing - 1);
            if (taken.stream().noneMatch(withSpacing::overlaps)) {
                taken.add(box);
                places.add(new Place(dx, dz));
            }
        }
        return places;
    }

    /** Pastes copy {@code index}, a few blocks per tick, registers it, then goes on to the next. */
    private void paste(Arena base, ArenaTemplate template, List<Place> places, int index, IntConsumer pasted,
                       CompletableFuture<Integer> done) {
        if (index == places.size()) {
            busy.remove(base.name());
            done.complete(places.size());
            return;
        }
        Place place = places.get(index);
        try {
            if (!template.paste(world, settings.get().regenBlocksPerTick(), place.dx(), 0, place.dz())) {
                Tasks.later(plugin, () -> paste(base, template, places, index, pasted, done), NEXT_TICK);
                return;
            }
        } catch (RuntimeException e) {
            busy.remove(base.name());
            done.completeExceptionally(e);
            return;
        }
        Arena copy = base.copyAt(copyName(base.name(), index + 1), world.getName(), place.dx(), 0, place.dz());
        arenas.update(copy).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not save arenas.yml after adding " + copy.name(), error);
            return null;
        });
        template.rewind();
        pasted.accept(index + 1);
        Tasks.later(plugin, () -> paste(base, template, places, index + 1, pasted, done), NEXT_TICK);
    }

    /** Sets the blocks of {@code boxes} to air, a few per tick, starting at box {@code index}, block {@code cursor}. */
    private void erase(String base, World in, List<BoundingBox> boxes, int index, long cursor) {
        if (index == boxes.size()) {
            busy.remove(base);
            return;
        }
        BoundingBox box = boxes.get(index);
        int sizeX = (int) box.getWidthX();
        int sizeZ = (int) box.getWidthZ();
        long total = (long) sizeX * (int) box.getHeight() * sizeZ;
        int budget = settings.get().regenBlocksPerTick();
        long at = cursor;
        for (int checked = 0, changed = 0; at < total && checked < CHECKS_PER_TICK && changed < budget; checked++, at++) {
            Block block = in.getBlockAt((int) box.getMinX() + (int) (at % sizeX), (int) box.getMinY() + (int) (at / ((long) sizeX * sizeZ)),
                    (int) box.getMinZ() + (int) ((at / sizeX) % sizeZ));
            if (!block.getType().isAir()) {
                block.setType(Material.AIR, false);
                changed++;
            }
        }
        long next = at;
        if (next >= total) {
            Tasks.later(plugin, () -> erase(base, in, boxes, index + 1, 0), NEXT_TICK);
        } else {
            Tasks.later(plugin, () -> erase(base, in, boxes, index, next), NEXT_TICK);
        }
    }
}
