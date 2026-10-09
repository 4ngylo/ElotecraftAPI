package me.angylo.elotecraftDuels.arena;

import me.angylo.elotecraftAPI.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Every block of an arena's box, saved with {@code /duels arena snapshot} to
 * {@code arenas/<arena>.blocks}, to rebuild the arena after a crash cut a build duel short. Block data only: containers come back empty and signs blank. Read and written off
 * the main thread; pasted on it, a few blocks per tick, by one {@link #paste} call per tick.
 */
public final class ArenaTemplate {

    /** Largest box a template takes: 256 x 128 x 256 blocks, at most 256 wide each way. */
    public static final long MAX_BLOCKS = 256L * 128 * 256;
    private static final int MAX_WIDTH = 256;
    /** Bytes of block names a template may hold, so a damaged file cannot fill the memory. */
    private static final long MAX_PALETTE_BYTES = 16L * 1024 * 1024;
    private static final int FORMAT = 1;
    private static final int MAX_PALETTE = 0xFFFF;
    /** Blocks compared per tick while pasting; unchanged blocks are cheap to skip. */
    private static final int CHECKS_PER_TICK = 65_536;
    private static final int CHUNK_SHIFT = 4;
    private static final int CHUNK_MASK = 15;

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<String> palette;
    private final short[] blocks;
    private BlockData[] parsed;
    private int cursor;
    private int changed;

    private ArenaTemplate(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, List<String> palette, short[] blocks) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.blocks = blocks;
    }

    /** Whether {@code arena}'s box is small enough to save; only while both corners are set. */
    public static boolean fitsLimits(Arena arena) {
        BoundingBox box = arena.bounds();
        return box.getWidthX() <= MAX_WIDTH && box.getWidthZ() <= MAX_WIDTH
                && (long) box.getWidthX() * (long) box.getHeight() * (long) box.getWidthZ() <= MAX_BLOCKS;
    }

    /** Whether this template was saved from {@code arena}'s current box. */
    public boolean fits(Arena arena) {
        BoundingBox box = arena.bounds();
        return minX == (int) box.getMinX() && minY == (int) box.getMinY() && minZ == (int) box.getMinZ()
                && sizeX == (int) box.getWidthX() && sizeY == (int) box.getHeight() && sizeZ == (int) box.getWidthZ();
    }

    /** Deletes {@code arena}'s template, if any, off the main thread. */
    public static CompletableFuture<Boolean> delete(Plugin plugin, String arena) {
        Path file = file(plugin, arena);
        return Tasks.supplyAsync(plugin, () -> {
            try {
                return Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not delete " + file, e);
            }
        });
    }

    /**
     * Saves every block in {@code arena}'s box. Main thread: copies the chunks here, then reads and writes
     * them on another thread. The arena needs both corners, its world loaded and at most {@link #MAX_BLOCKS}.
     *
     * @return the number of blocks saved
     */
    public static CompletableFuture<Integer> save(Plugin plugin, Arena arena) {
        World world = Bukkit.getWorld(arena.world());
        if (world == null || arena.corner1() == null || arena.corner2() == null || !fitsLimits(arena)) {
            throw new IllegalArgumentException("Arena " + arena.name() + " has no loaded world, no corners or is too big");
        }
        BoundingBox box = arena.bounds();
        int minX = (int) box.getMinX();
        int minY = (int) box.getMinY();
        int minZ = (int) box.getMinZ();
        int sizeX = (int) box.getWidthX();
        int sizeY = (int) box.getHeight();
        int sizeZ = (int) box.getWidthZ();
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int cx = minX >> CHUNK_SHIFT; cx <= (minX + sizeX - 1) >> CHUNK_SHIFT; cx++) {
            for (int cz = minZ >> CHUNK_SHIFT; cz <= (minZ + sizeZ - 1) >> CHUNK_SHIFT; cz++) {
                chunks.put(chunkKey(cx, cz), world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
            }
        }
        Path file = file(plugin, arena.name());
        return Tasks.supplyAsync(plugin, () -> {
            Map<String, Integer> palette = new LinkedHashMap<>();
            short[] blocks = new short[sizeX * sizeY * sizeZ];
            int index = 0;
            for (int y = minY; y < minY + sizeY; y++) {
                for (int z = minZ; z < minZ + sizeZ; z++) {
                    for (int x = minX; x < minX + sizeX; x++) {
                        ChunkSnapshot chunk = chunks.get(chunkKey(x >> CHUNK_SHIFT, z >> CHUNK_SHIFT));
                        String data = chunk.getBlockData(x & CHUNK_MASK, y, z & CHUNK_MASK).getAsString();
                        Integer id = palette.computeIfAbsent(data, key -> palette.size());
                        if (id > MAX_PALETTE) {
                            throw new IllegalStateException("Arena " + arena.name() + " has too many kinds of blocks");
                        }
                        blocks[index++] = (short) (int) id;
                    }
                }
            }
            write(file, new ArenaTemplate(minX, minY, minZ, sizeX, sizeY, sizeZ, List.copyOf(palette.keySet()), blocks));
            return blocks.length;
        });
    }

    /** Reads {@code arena}'s template on another thread; fails with {@link java.nio.file.NoSuchFileException} if none was saved. */
    public static CompletableFuture<ArenaTemplate> load(Plugin plugin, String arena) {
        Path file = file(plugin, arena);
        return Tasks.supplyAsync(plugin, () -> read(file));
    }

    /**
     * Sets the next blocks that differ from the template, without physics. Call once per tick until it
     * returns true. Main thread.
     *
     * @param budget most blocks to change in this call
     * @return whether the whole box matches the template
     */
    public boolean paste(World world, int budget) {
        if (parsed == null) {
            parsed = palette.stream().map(Bukkit::createBlockData).toArray(BlockData[]::new);
        }
        int changedNow = 0;
        for (int checked = 0; cursor < blocks.length && checked < CHECKS_PER_TICK && changedNow < budget; checked++, cursor++) {
            int x = cursor % sizeX;
            int z = (cursor / sizeX) % sizeZ;
            int y = cursor / (sizeX * sizeZ);
            BlockData data = parsed[Short.toUnsignedInt(blocks[cursor])];
            Block block = world.getBlockAt(minX + x, minY + y, minZ + z);
            if (!block.getBlockData().equals(data)) {
                block.setBlockData(data, false);
                changedNow++;
            }
        }
        changed += changedNow;
        return cursor >= blocks.length;
    }

    /** Blocks changed by {@link #paste} so far. */
    public int changed() {
        return changed;
    }

    static Path file(Plugin plugin, String arena) {
        return plugin.getDataFolder().toPath().resolve("arenas").resolve(arena + ".blocks");
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static void write(Path file, ArenaTemplate template) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                out.writeInt(FORMAT);
                for (int value : new int[]{template.minX, template.minY, template.minZ, template.sizeX, template.sizeY, template.sizeZ}) {
                    out.writeInt(value);
                }
                out.writeInt(template.palette.size());
                for (String data : template.palette) {
                    out.writeUTF(data);
                }
                for (short block : template.blocks) {
                    out.writeShort(block);
                }
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    private static ArenaTemplate read(Path file) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != FORMAT) {
                throw new IOException("Unknown template format");
            }
            int minX = in.readInt();
            int minY = in.readInt();
            int minZ = in.readInt();
            int sizeX = in.readInt();
            int sizeY = in.readInt();
            int sizeZ = in.readInt();
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || sizeX > MAX_WIDTH || sizeZ > MAX_WIDTH
                    || (long) sizeX * sizeY * sizeZ > MAX_BLOCKS) {
                throw new IOException("Invalid template size");
            }
            int paletteSize = in.readInt();
            if (paletteSize <= 0 || paletteSize > MAX_PALETTE + 1) {
                throw new IOException("Invalid template palette");
            }
            List<String> palette = new ArrayList<>(paletteSize);
            long paletteBytes = 0;
            for (int i = 0; i < paletteSize; i++) {
                String data = in.readUTF();
                paletteBytes += data.length();
                if (paletteBytes > MAX_PALETTE_BYTES) {
                    throw new IOException("Invalid template palette");
                }
                palette.add(data);
            }
            short[] blocks = new short[sizeX * sizeY * sizeZ];
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = in.readShort();
                if (Short.toUnsignedInt(blocks[i]) >= paletteSize) {
                    throw new IOException("Invalid block in template");
                }
            }
            return new ArenaTemplate(minX, minY, minZ, sizeX, sizeY, sizeZ, List.copyOf(palette), blocks);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
