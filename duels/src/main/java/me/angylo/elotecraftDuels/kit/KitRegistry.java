package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Kits stored in kits.yml. Items are saved with Paper's item serialization (base64), which upgrades
 * them when the server updates; edit them in game with {@code /duels kit load} and {@code save}.
 * Every change is saved right away. Main thread only.
 */
public final class KitRegistry {

    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final String ROOT = "kits";

    private final Logger logger;
    private final ConfigFile file;
    private final Map<String, Kit> kits = new TreeMap<>();

    public KitRegistry(Plugin plugin) {
        this.logger = plugin.getLogger();
        this.file = new ConfigFile(plugin, "kits.yml");
        load();
    }

    /** Lowercase letters, digits, dots, - and _. */
    public static boolean validPermission(String permission) {
        return PERMISSION.matcher(permission).matches();
    }

    public Optional<Kit> get(String name) {
        return Optional.ofNullable(kits.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every kit, sorted by name. */
    public List<Kit> all() {
        return List.copyOf(kits.values());
    }

    public List<String> names() {
        return List.copyOf(kits.keySet());
    }

    /**
     * Adds a kit holding a copy of {@code inventory}.
     *
     * @throws IllegalArgumentException if the name is invalid or taken
     */
    public CompletableFuture<Kit> create(String name, Material icon, PlayerInventory inventory) {
        if (!ArenaRegistry.validName(name) || kits.containsKey(name)) {
            throw new IllegalArgumentException("Invalid or taken kit name: " + name);
        }
        Kit kit = Kit.of(name, icon, inventory);
        return update(kit).thenApply(ignored -> kit);
    }

    /** Stores {@code kit}, replacing the one with its name, and saves the file. */
    public CompletableFuture<Void> update(Kit kit) {
        kits.put(kit.name(), kit);
        write(kit);
        return file.save();
    }

    public CompletableFuture<Void> delete(String name) {
        kits.remove(name);
        file.get().set(ROOT + "." + name, null);
        return file.save();
    }

    /** Reloads kits.yml; on a parse error the kits in memory are kept and false is returned. */
    public boolean reload() {
        if (!file.reload()) {
            return false;
        }
        load();
        return true;
    }

    /** Blocking save for {@code onDisable}, in case an async save was still pending. */
    public void saveNow() {
        file.saveNow();
    }

    private void load() {
        kits.clear();
        ConfigurationSection root = file.get().getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (!ArenaRegistry.validName(name) || section == null) {
                logger.warning("Skipping kit '" + name + "' in kits.yml: names use lowercase letters, digits, - or _");
                continue;
            }
            try {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(section.getString("items", "")));
                kits.put(name, new Kit(name, section.getString("display-name", name), icon(section),
                        permission(section), Arrays.asList(items), section.getBoolean("build", false)));
            } catch (RuntimeException e) {
                logger.warning("Skipping kit '" + name + "' in kits.yml: its items could not be read (" + e.getMessage() + ")");
            }
        }
    }

    private Material icon(ConfigurationSection section) {
        String raw = section.getString("icon", Kit.DEFAULT_ICON.name());
        Material icon = Material.matchMaterial(raw);
        if (icon == null || !icon.isItem() || icon.isAir()) {
            logger.warning("Kit '" + section.getName() + "' has an unknown icon '" + raw + "'; using " + Kit.DEFAULT_ICON);
            return Kit.DEFAULT_ICON;
        }
        return icon;
    }

    private String permission(ConfigurationSection section) {
        String raw = section.getString("permission", "").strip().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return null;
        }
        if (!validPermission(raw)) {
            logger.warning("Kit '" + section.getName() + "' has an invalid permission '" + raw + "'; only admins can use it until fixed");
            return "duels.admin.kit";
        }
        return raw;
    }

    private void write(Kit kit) {
        YamlConfiguration yaml = file.get();
        String path = ROOT + "." + kit.name();
        yaml.set(path, null);
        yaml.set(path + ".display-name", kit.displayName());
        yaml.set(path + ".icon", kit.icon().name());
        yaml.set(path + ".permission", kit.permission() == null ? "" : kit.permission());
        yaml.set(path + ".build", kit.build());
        yaml.set(path + ".items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(kit.items())));
    }
}
