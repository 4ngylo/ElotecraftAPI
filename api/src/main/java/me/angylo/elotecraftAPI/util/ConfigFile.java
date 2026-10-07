package me.angylo.elotecraftAPI.util;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * A named YAML file in the plugin's data folder, e.g. {@code new ConfigFile(plugin, "shops.yml")}.
 * On first load the bundled copy from the jar is written out and used for defaults.
 * Load in {@code onEnable} or on an explicit reload; files are expected to be small.
 */
public final class ConfigFile {

    private static final long SAVE_DELAY_TICKS = 20;

    private final Plugin plugin;
    private final String name;
    private final Path path;
    private final AtomicLong snapshots = new AtomicLong();
    private volatile YamlConfiguration config;
    private volatile boolean writable;
    private long writtenSnapshot;
    private BukkitTask pendingSave;

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
        addMissingKeys();
        return true;
    }

    /**
     * Copies keys the bundled copy has but the file lacks (a file from an older version) into the file,
     * with their comments, and writes it once, keeping the original as {@code <name>.bak}.
     * Values already in the file are never changed or removed.
     */
    private void addMissingKeys() {
        YamlConfiguration file = config;
        Configuration bundled = file.getDefaults();
        if (bundled == null || Files.notExists(path)) {
            return;
        }
        List<String> added = new ArrayList<>();
        // Parents come before their children, so a new section is created before its keys are copied.
        for (String key : bundled.getKeys(true)) {
            if (file.contains(key, true)) {
                continue;
            }
            if (bundled.isConfigurationSection(key)) {
                file.createSection(key);
            } else {
                file.set(key, bundled.get(key));
                added.add(key);
            }
            file.setComments(key, bundled.getComments(key));
            file.setInlineComments(key, bundled.getInlineComments(key));
        }
        if (added.isEmpty()) {
            return;
        }
        try {
            Path backup = path.resolveSibling(name + ".bak");
            if (Files.notExists(backup)) {
                Files.copy(path, backup);
            }
            write(snapshots.incrementAndGet(), file.saveToString());
            plugin.getLogger().info("Added " + added.size() + " new settings to " + name + " (old file kept as " + name + ".bak): "
                    + String.join(", ", added));
        } catch (IOException | UncheckedIOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not add new settings to " + name + "; using the bundled values for them", e);
        }
    }

    /** Live config; edit on the main thread, then {@link #saveLater()} or {@link #save()}. */
    public YamlConfiguration get() {
        return config;
    }

    /**
     * Saves about a second from now; every call until then is folded into that one write.
     * Use this for frequent changes (per command, per click). Main thread only.
     * Call {@link #saveNow()} in {@code onDisable}, since a pending save is cancelled with the plugin.
     */
    public void saveLater() {
        if (pendingSave == null) {
            pendingSave = Tasks.later(plugin, () -> {
                pendingSave = null;
                save();
            }, SAVE_DELAY_TICKS);
        }
    }

    /**
     * Snapshots the config on the calling thread and writes it off the main thread.
     * Do not use in {@code onDisable}: pending async tasks are cancelled there; use {@link #saveNow()}.
     */
    public CompletableFuture<Void> save() {
        if (!writable) {
            return CompletableFuture.failedFuture(new IllegalStateException(name + " failed to load; not saving over it"));
        }
        long snapshot = snapshots.incrementAndGet();
        String yaml = config.saveToString();
        return CompletableFuture.runAsync(() -> write(snapshot, yaml), task -> Tasks.async(plugin, task));
    }

    /** Blocking save for {@code onDisable}; also cancels a pending {@link #saveLater()}. */
    public void saveNow() {
        if (pendingSave != null) {
            pendingSave.cancel();
            pendingSave = null;
        }
        if (!writable) {
            plugin.getLogger().warning(name + " failed to load; not saving over it");
            return;
        }
        write(snapshots.incrementAndGet(), config.saveToString());
    }

    /** Writes are serialized, and a snapshot older than the last one written is skipped. */
    private synchronized void write(long snapshot, String yaml) {
        if (snapshot <= writtenSnapshot) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, yaml, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            writtenSnapshot = snapshot;
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
