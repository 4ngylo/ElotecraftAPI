package me.angylo.elotecraftDuels.hook;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.loaders.SlimeSerializationAdapter;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataContainer;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stands in for AdvancedSlimePaper in tests, found through {@code META-INF/services}: worlds hold no chunks,
 * and loading one adds an empty mock world with its name. Saving writes a placeholder through the loader.
 */
public final class FakeSlimeApi implements AdvancedSlimePaperAPI {

    private final Map<String, FakeWorld> loaded = new ConcurrentHashMap<>();

    @Override
    public SlimeWorld readWorld(SlimeLoader loader, String name, boolean readOnly, SlimePropertyMap properties)
            throws UnknownWorldException, IOException {
        loader.readWorld(name);
        return new FakeWorld(name, loader, readOnly, null);
    }

    @Override
    public SlimeWorldInstance getLoadedWorld(String name) {
        return loaded.get(name);
    }

    @Override
    public List<SlimeWorldInstance> getLoadedWorlds() {
        return List.copyOf(loaded.values());
    }

    @Override
    public SlimeWorldInstance loadWorld(SlimeWorld world, boolean callWorldLoadEvent) {
        if (MockBukkit.getMock().getWorld(world.getName()) != null) {
            throw new IllegalArgumentException("World " + world.getName() + " is already loaded");
        }
        FakeWorld instance = new FakeWorld(world.getName(), world.getLoader(), world.isReadOnly(),
                MockBukkit.getMock().addSimpleWorld(world.getName()));
        loaded.put(world.getName(), instance);
        return instance;
    }

    @Override
    public boolean worldLoaded(SlimeWorld world) {
        return loaded.containsKey(world.getName());
    }

    @Override
    public void saveWorld(SlimeWorld world) throws IOException {
        world.getLoader().saveWorld(world.getName(), new byte[]{1});
    }

    @Override
    public void migrateWorld(String name, SlimeLoader from, SlimeLoader to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SlimeWorld createEmptyWorld(String name, boolean readOnly, SlimePropertyMap properties, SlimeLoader loader) {
        return new FakeWorld(name, loader, readOnly, null);
    }

    @Override
    public SlimeWorld readVanillaWorld(File directory, String name, SlimeLoader loader) {
        return new FakeWorld(name, loader, false, null);
    }

    @Override
    public SlimeSerializationAdapter getSerializer() {
        throw new UnsupportedOperationException();
    }

    private record FakeWorld(String name, SlimeLoader loader, boolean readOnly, World bukkitWorld) implements SlimeWorldInstance {

        @Override
        public World getBukkitWorld() {
            return bukkitWorld;
        }

        @Override
        public SlimeWorld getSerializableCopy() {
            return new FakeWorld(name, loader, readOnly, null);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public SlimeLoader getLoader() {
            return loader;
        }

        @Override
        public SlimeChunk getChunk(int x, int z) {
            return null;
        }

        @Override
        public Collection<SlimeChunk> getChunkStorage() {
            return List.of();
        }

        @Override
        public ConcurrentMap<String, BinaryTag> getExtraData() {
            return new ConcurrentHashMap<>();
        }

        @Override
        public Collection<CompoundBinaryTag> getWorldMaps() {
            return List.of();
        }

        @Override
        public SlimePropertyMap getPropertyMap() {
            return new SlimePropertyMap();
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public SlimeWorld clone(String newName) {
            return new FakeWorld(newName, null, true, null);
        }

        @Override
        public SlimeWorld clone(String newName, SlimeLoader newLoader) {
            return new FakeWorld(newName, newLoader, false, null);
        }

        @Override
        public int getDataVersion() {
            return 0;
        }

        @Override
        public PersistentDataContainer getPersistentDataContainer() {
            throw new UnsupportedOperationException();
        }
    }
}
