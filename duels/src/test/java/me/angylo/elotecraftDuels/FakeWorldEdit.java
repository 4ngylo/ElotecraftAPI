package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.hook.WorldEditHook;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * WorldEdit for tests, which MockBukkit cannot run: copies, pastes and clears blocks with the Bukkit API at
 * once. Schematic files hold only a marker; their blocks stay in memory, so they survive a restart of the
 * plugin within one test. Pastes can be held back to test duels waiting for a copy.
 */
final class FakeWorldEdit implements WorldEditHook {

    /** Block data of a box, x fastest, then z, then y. */
    record Blocks(BlockData[] data, int sizeX, int sizeY, int sizeZ) implements Copy {

        @Override
        public int[] size() {
            return new int[]{sizeX, sizeY, sizeZ};
        }
    }

    private final Map<Path, Blocks> files = new HashMap<>();
    private final List<Runnable> held = new ArrayList<>();
    private BoundingBox selection;
    private boolean holdPastes;
    private boolean failPastes;
    private int pastes;

    /** Whether later pastes wait for {@link #releasePastes()}. */
    void holdPastes(boolean hold) {
        this.holdPastes = hold;
    }

    void failPastes(boolean fail) {
        this.failPastes = fail;
    }

    /** Runs the pastes held back so far. */
    void releasePastes() {
        List<Runnable> pending = List.copyOf(held);
        held.clear();
        pending.forEach(Runnable::run);
    }

    int pastes() {
        return pastes;
    }

    void selection(BoundingBox box) {
        this.selection = box;
    }

    /** Writes a schematic file holding {@code copy}. */
    void writeSchematic(Path file, Copy copy) {
        save(copy, file).join();
    }

    @Override
    public String name() {
        return "FakeWorldEdit";
    }

    @Override
    public Optional<BoundingBox> selection(Player player) {
        return Optional.ofNullable(selection);
    }

    @Override
    public CompletableFuture<Copy> copy(World world, BoundingBox box) {
        int sizeX = (int) box.getWidthX();
        int sizeY = (int) box.getHeight();
        int sizeZ = (int) box.getWidthZ();
        BlockData[] data = new BlockData[sizeX * sizeY * sizeZ];
        int index = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    data[index++] = world.getBlockAt((int) box.getMinX() + x, (int) box.getMinY() + y, (int) box.getMinZ() + z).getBlockData();
                }
            }
        }
        return CompletableFuture.completedFuture(new Blocks(data, sizeX, sizeY, sizeZ));
    }

    @Override
    public CompletableFuture<Copy> load(Path file) {
        Blocks blocks = files.get(file);
        if (blocks == null || !Files.isRegularFile(file)) {
            return CompletableFuture.failedFuture(new UncheckedIOException(new NoSuchFileException(file.getFileName().toString())));
        }
        return CompletableFuture.completedFuture(blocks);
    }

    @Override
    public CompletableFuture<Void> save(Copy copy, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "fake schematic");
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new UncheckedIOException(e));
        }
        files.put(file, (Blocks) copy);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> paste(Copy copy, World world, int x, int y, int z) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable paste = () -> {
            if (failPastes) {
                done.completeExceptionally(new IllegalStateException("Paste failed on purpose"));
                return;
            }
            pastes++;
            Blocks blocks = (Blocks) copy;
            int index = 0;
            for (int dy = 0; dy < blocks.sizeY(); dy++) {
                for (int dz = 0; dz < blocks.sizeZ(); dz++) {
                    for (int dx = 0; dx < blocks.sizeX(); dx++) {
                        world.getBlockAt(x + dx, y + dy, z + dz).setBlockData(blocks.data()[index++], false);
                    }
                }
            }
            done.complete(null);
        };
        if (holdPastes) {
            held.add(paste);
        } else {
            paste.run();
        }
        return done;
    }

    @Override
    public CompletableFuture<Void> clear(World world, BoundingBox box) {
        for (int x = (int) box.getMinX(); x < box.getMaxX(); x++) {
            for (int y = (int) box.getMinY(); y < box.getMaxY(); y++) {
                for (int z = (int) box.getMinZ(); z < box.getMaxZ(); z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
