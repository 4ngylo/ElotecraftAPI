package me.angylo.elotecraftDuels.hook;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import me.angylo.elotecraftAPI.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Optional AdvancedSlimePaper (ASP) support. A slime world saved in {@code slime-worlds/} is an arena
 * template: it is loaded for admins to build in, and every duel in an arena there gets its own copy, made
 * in memory in milliseconds and thrown away afterwards. Build duels leave nothing to put back and one
 * arena hosts several duels at once. ASP's classes are only touched in {@link Hook}, created only on a
 * server that runs ASP, so the plugin runs on plain Paper. Main thread only, except {@link #isTemplate}.
 */
public final class SlimeWorlds {

    private static final String API_CLASS = "com.infernalsuite.asp.api.AdvancedSlimePaperAPI";

    private final Plugin plugin;
    private final Logger logger;
    private final Hook hook;

    private SlimeWorlds(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.hook = new Hook(plugin.getDataFolder().toPath().resolve("slime-worlds"));
    }

    /** The hook if the server runs AdvancedSlimePaper; logged if it does but cannot be used. */
    public static Optional<SlimeWorlds> detect(Plugin plugin) {
        try {
            Class.forName(API_CLASS, false, SlimeWorlds.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SlimeWorlds(plugin));
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "AdvancedSlimePaper is installed but could not be used, so arenas are not copied", e);
            return Optional.empty();
        }
    }

    /** Whether {@code world} is a saved template whose duels run in copies. Safe from any thread. */
    public boolean isTemplate(String world) {
        return hook.templates.containsKey(world);
    }

    /**
     * Reads every template and loads the ones not loaded yet, so admins can edit them and arenas there are
     * ready. Completes once all are done; failures are logged.
     */
    public CompletableFuture<Void> loadTemplates() {
        CompletableFuture<Void> loaded = Tasks.supplyAsync(plugin, hook::listTemplates).thenCompose(names -> CompletableFuture.allOf(names.stream()
                .map(name -> Tasks.supplyAsync(plugin, () -> hook.readForEditing(name))
                        .thenCompose(world -> onMain(() -> hook.load(world)))
                        .exceptionally(error -> {
                            logger.log(Level.SEVERE, "Could not load the duel arena world " + name, error);
                            return null;
                        }))
                .toArray(CompletableFuture[]::new)));
        loaded.exceptionally(error -> {
            logger.log(Level.SEVERE, "Could not list the duel arena worlds in slime-worlds/", error);
            return null;
        });
        return loaded;
    }

    /**
     * Loads a copy of {@code template} named {@code name}, for one duel.
     *
     * @return empty if it could not be made (logged)
     */
    public Optional<World> copy(String template, String name) {
        try {
            return Optional.of(hook.copy(template, name));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not copy the duel arena world " + template, e);
            return Optional.empty();
        }
    }

    /** Creates, loads and saves an empty template world with a block to stand on at 0 63 0. */
    public CompletableFuture<World> create(String name) {
        return Tasks.supplyAsync(plugin, () -> hook.requireFree(name))
                .thenCompose(ignored -> onMain(() -> hook.createEmpty(name)))
                .thenCompose(world -> save(name).thenApply(ignored -> world));
    }

    /** Turns the unloaded world folder {@code folder} into the template {@code name} and loads it. */
    public CompletableFuture<World> importWorld(String folder, String name) {
        File directory = new File(Bukkit.getWorldContainer(), folder);
        return Tasks.supplyAsync(plugin, () -> hook.importVanilla(directory, name))
                .thenCompose(world -> onMain(() -> hook.load(world)));
    }

    /** Saves the loaded template {@code name}; new duels there use what it looks like now. */
    public CompletableFuture<Void> save(String name) {
        Hook.Copy copy = hook.copyForSaving(name);
        return Tasks.supplyAsync(plugin, () -> {
            hook.save(copy);
            return null;
        });
    }

    /** Runs {@code step} on the main thread on the next tick. */
    private <T> CompletableFuture<T> onMain(Supplier<T> step) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Tasks.sync(plugin, () -> {
            try {
                result.complete(step.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static final class Hook {

        private final AdvancedSlimePaperAPI api = AdvancedSlimePaperAPI.instance();
        private final FolderLoader loader;
        /** Read-only template copies, never loaded, cloned for each duel. */
        private final Map<String, SlimeWorld> templates = new ConcurrentHashMap<>();

        Hook(Path folder) {
            this.loader = new FolderLoader(folder);
        }

        /** A saved, never-loaded copy of a template, handed from the main thread to the saving one. */
        record Copy(SlimeWorld world) {
        }

        List<String> listTemplates() {
            try {
                return loader.listWorlds();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Off the main thread: the template to load, plus its read-only copy for cloning. */
        SlimeWorld readForEditing(String name) {
            try {
                templates.put(name, api.readWorld(loader, name, true, new SlimePropertyMap()));
                return api.readWorld(loader, name, false, new SlimePropertyMap());
            } catch (Exception e) {
                throw new IllegalStateException("Could not read slime world " + name, e);
            }
        }

        World load(SlimeWorld world) {
            World loaded = Bukkit.getWorld(world.getName());
            return loaded != null ? loaded : api.loadWorld(world, true).getBukkitWorld();
        }

        World copy(String template, String name) {
            SlimeWorld source = templates.get(template);
            if (source == null) {
                throw new IllegalStateException("No template world " + template);
            }
            return api.loadWorld(source.clone(name), false).getBukkitWorld();
        }

        Void requireFree(String name) {
            if (loader.worldExists(name)) {
                throw new IllegalStateException("A slime world named " + name + " already exists");
            }
            return null;
        }

        World createEmpty(String name) {
            if (Bukkit.getWorld(name) != null) {
                throw new IllegalStateException("A world named " + name + " is already loaded");
            }
            SlimePropertyMap properties = new SlimePropertyMap();
            properties.setValue(SlimeProperties.PVP, true);
            properties.setValue(SlimeProperties.ALLOW_MONSTERS, false);
            properties.setValue(SlimeProperties.ALLOW_ANIMALS, false);
            properties.setValue(SlimeProperties.DIFFICULTY, "normal");
            properties.setValue(SlimeProperties.SPAWN_X, 0);
            properties.setValue(SlimeProperties.SPAWN_Y, 64);
            properties.setValue(SlimeProperties.SPAWN_Z, 0);
            World world = api.loadWorld(api.createEmptyWorld(name, false, properties, loader), true).getBukkitWorld();
            world.getBlockAt(0, 63, 0).setType(Material.STONE);
            return world;
        }

        SlimeWorld importVanilla(File directory, String name) {
            if (!directory.isDirectory()) {
                throw new IllegalStateException("There is no world folder named " + directory.getName());
            }
            requireFree(name);
            try {
                SlimeWorld world = api.readVanillaWorld(directory, name, loader);
                api.saveWorld(world);
                templates.put(name, api.readWorld(loader, name, true, new SlimePropertyMap()));
                return world;
            } catch (Exception e) {
                throw new IllegalStateException("Could not import world folder " + directory.getName(), e);
            }
        }

        Copy copyForSaving(String name) {
            SlimeWorldInstance loaded = api.getLoadedWorld(name);
            if (loaded == null) {
                throw new IllegalStateException(name + " is not a loaded slime world");
            }
            return new Copy(loaded.getSerializableCopy());
        }

        void save(Copy copy) {
            try {
                api.saveWorld(copy.world());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            templates.put(copy.world().getName(), copy.world());
        }
    }

    /** Stores each slime world as {@code <name>.slime} in one folder. Names are checked by the commands. */
    private static final class FolderLoader implements SlimeLoader {

        private static final String EXTENSION = ".slime";

        private final Path folder;

        FolderLoader(Path folder) {
            this.folder = folder;
        }

        @Override
        public byte[] readWorld(String name) throws UnknownWorldException, IOException {
            try {
                return Files.readAllBytes(file(name));
            } catch (NoSuchFileException e) {
                throw new UnknownWorldException(name);
            }
        }

        @Override
        public boolean worldExists(String name) {
            return Files.exists(file(name));
        }

        @Override
        public List<String> listWorlds() throws IOException {
            if (Files.notExists(folder)) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(folder)) {
                return files.map(path -> path.getFileName().toString())
                        .filter(file -> file.endsWith(EXTENSION))
                        .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                        .toList();
            }
        }

        @Override
        public void saveWorld(String name, byte[] data) throws IOException {
            Files.createDirectories(folder);
            Path temporary = folder.resolve(name + EXTENSION + ".tmp");
            Files.write(temporary, data);
            Files.move(temporary, file(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public void deleteWorld(String name) throws UnknownWorldException, IOException {
            if (!Files.deleteIfExists(file(name))) {
                throw new UnknownWorldException(name);
            }
        }

        private Path file(String name) {
            return folder.resolve(name + EXTENSION);
        }
    }
}
