package me.angylo.elotecraftAPI.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * A named YAML file in the plugin's data folder, e.g. {@code new ConfigFile(plugin, "shops.yml")}.
 * On first load the bundled copy from the jar is written out and used for defaults.
 * Load in {@code onEnable} or on an explicit reload; files are expected to be small.
 */
public final class ConfigFile {

    private final Plugin plugin;
    private final String name;
    private final Path path;
    private volatile YamlConfiguration config;
    private volatile boolean writable;

    public ConfigFile(Plugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
        this.path = plugin.getDataFolder().toPath().resolve(name);
        reload();
    }

    /**
     * Reloads from disk. On a parse error the error is logged, the previous values are kept,
     * saving is blocked until a reload succeeds, and {@code false} is returned.
     */
    public boolean reload() {
        if (Files.notExists(path) && hasBundledCopy()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration fresh = new YamlConfiguration();
        fresh.setDefaults(bundledDefaults());
        try {
            if (Files.exists(path)) {
                fresh.load(path.toFile());
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load " + name + "; keeping previous values and not saving over it", e);
            writable = false;
            if (config == null) {
                config = fresh;
            }
            return false;
        }
        config = fresh;
        writable = true;
        return true;
    }

    /** Live config; edit on the main thread, then {@link #save()}. */
    public YamlConfiguration get() {
        return config;
    }

    /**
     * Snapshots the config on the calling thread and writes it off the main thread.
     * Do not use in {@code onDisable}: pending async tasks are cancelled there; use {@link #saveNow()}.
     */
    public CompletableFuture<Void> save() {
        if (!writable) {
            return CompletableFuture.failedFuture(new IllegalStateException(name + " failed to load; not saving over it"));
        }
        String yaml = config.saveToString();
        return CompletableFuture.runAsync(() -> write(yaml), task -> Tasks.async(plugin, task));
    }

    /** Blocking save for {@code onDisable}. */
    public void saveNow() {
        if (!writable) {
            plugin.getLogger().warning(name + " failed to load; not saving over it");
            return;
        }
        write(config.saveToString());
    }

    // ponytail: saves are serialized but two async saves may land out of order; queue them if that ever matters
    private synchronized void write(String yaml) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, yaml, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + name, e);
            throw new UncheckedIOException("Could not save " + name, e);
        }
    }

    private boolean hasBundledCopy() {
        try (InputStream in = plugin.getResource(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private YamlConfiguration bundledDefaults() {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled " + name, e);
            return new YamlConfiguration();
        }
    }
}
