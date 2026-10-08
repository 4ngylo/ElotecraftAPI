package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
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
        boolean firstStart = !new File(plugin.getDataFolder(), "kits.yml").exists();
        this.file = new ConfigFile(plugin, "kits.yml");
        load();
        if (firstStart) {
            logger.info("Added " + installDefaults() + " default kits to kits.yml");
        }
    }

    /**
     * Adds the default kits that are missing; kits with their names are kept as they are.
     *
     * @return the number added
     */
    public int installDefaults() {
        int added = 0;
        for (Kit kit : DefaultKits.all()) {
            if (!kits.containsKey(kit.name())) {
                kits.put(kit.name(), kit);
                write(kit);
                added++;
            }
        }
        if (added > 0) {
            file.save().exceptionally(error -> {
                logger.log(Level.WARNING, "Could not save kits.yml", error);
                return null;
            });
        }
        return added;
    }

    /** Lowercase letters, digits, dots, - and _. */
    public static boolean validPermission(String permission) {
        return PERMISSION.matcher(permission).matches();
    }

    public Optional<Kit> get(String name) {
        return Optional.ofNullable(kits.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The kit as it is now: an admin kit read again by name (empty once deleted), a custom kit as it was. */
    public Optional<Kit> current(Kit kit) {
        return kit.isCustom() ? Optional.of(kit) : get(kit.name());
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
        if (!ArenaRegistry.validName(name) || kits.containsKey(name) || Kit.CUSTOM.equals(name)) {
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
            if (!ArenaRegistry.validName(name) || section == null || Kit.CUSTOM.equals(name)) {
                logger.warning("Skipping kit '" + name + "' in kits.yml: names use lowercase letters, digits, - or _, and '"
                        + Kit.CUSTOM + "' is for players' custom kits");
                continue;
            }
            try {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(section.getString("items", "")));
                kits.put(name, new Kit(name, section.getString("display-name", name), icon(section),
                        permission(section), Arrays.asList(items), section.getBoolean("build", false), arenaCategories(section),
                        section.getBoolean("damage", true), rules(section), rewards(section), effects(section), mode(section)));
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

    private Set<String> arenaCategories(ConfigurationSection section) {
        Set<String> categories = new HashSet<>();
        for (String raw : section.getStringList("arena-categories")) {
            String category = raw.strip().toLowerCase(Locale.ROOT);
            if (ArenaRegistry.validName(category)) {
                categories.add(category);
            } else {
                logger.warning("Kit '" + section.getName() + "' has an invalid arena category '" + raw + "'; skipping it");
            }
        }
        return categories;
    }

    private Map<KitRule, Object> rules(ConfigurationSection section) {
        Map<KitRule, Object> rules = new EnumMap<>(KitRule.class);
        ConfigurationSection raw = section.getConfigurationSection("rules");
        if (raw == null) {
            return rules;
        }
        for (String key : raw.getKeys(false)) {
            Optional<KitRule> rule = KitRule.byKey(key);
            Object value = raw.get(key);
            if (rule.isEmpty() || !rule.get().accepts(value)) {
                logger.warning("Kit '" + section.getName() + "' has an invalid rule '" + key + ": " + value + "'; skipping it");
                continue;
            }
            rules.put(rule.get(), value);
        }
        return rules;
    }

    /** {@code effects: ["speed 2", "jump_boost 1"]}: an effect name and its level. */
    private List<PotionEffect> effects(ConfigurationSection section) {
        List<PotionEffect> effects = new ArrayList<>();
        for (String raw : section.getStringList("effects")) {
            String[] parts = raw.strip().split("\\s+");
            Optional<PotionEffectType> type = Kit.effectType(parts[0]);
            int level = parts.length == 2 && parts[1].matches("\\d{1,2}") ? Integer.parseInt(parts[1]) : -1;
            if (type.isEmpty() || level < 1 || level > Kit.MAX_EFFECT_LEVEL) {
                logger.warning("Kit '" + section.getName() + "' has an invalid effect '" + raw + "' (use e.g. \"speed 2\"); skipping it");
                continue;
            }
            effects.add(Kit.effect(type.get(), level));
        }
        return effects;
    }

    private Kit.Rewards rewards(ConfigurationSection section) {
        return new Kit.Rewards(Reward.load(section, logger, "kits.yml", "rewards.win"),
                Reward.load(section, logger, "kits.yml", "rewards.loss"));
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

    private Kit.Mode mode(ConfigurationSection section) {
        String raw = section.getString("mode", Kit.Mode.NORMAL.key());
        return Kit.Mode.byKey(raw).orElseGet(() -> {
            logger.warning("Kit '" + section.getName() + "' has an unknown mode '" + raw + "'; using " + Kit.Mode.NORMAL.key());
            return Kit.Mode.NORMAL;
        });
    }

    private void write(Kit kit) {
        YamlConfiguration yaml = file.get();
        String path = ROOT + "." + kit.name();
        yaml.set(path, null);
        yaml.set(path + ".display-name", kit.displayName());
        yaml.set(path + ".icon", kit.icon().name());
        yaml.set(path + ".permission", kit.permission() == null ? "" : kit.permission());
        yaml.set(path + ".build", kit.build());
        yaml.set(path + ".damage", kit.damage());
        if (kit.mode() != Kit.Mode.NORMAL) {
            yaml.set(path + ".mode", kit.mode().key());
        }
        yaml.set(path + ".arena-categories", kit.arenaCategories().stream().sorted().toList());
        for (KitRule rule : KitRule.values()) {
            Object value = kit.rules().get(rule);
            if (value != null) {
                yaml.set(path + ".rules." + rule.key(), value);
            }
        }
        if (!kit.effects().isEmpty()) {
            yaml.set(path + ".effects", kit.effects().stream()
                    .map(effect -> effect.getType().getKey().getKey() + " " + (effect.getAmplifier() + 1)).toList());
        }
        writeReward(yaml, path + ".rewards.win", kit.rewards().win());
        writeReward(yaml, path + ".rewards.loss", kit.rewards().loss());
        yaml.set(path + ".items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(kit.items())));
    }

    /** Writes only what {@code reward} sets, so kits without rewards have no {@code rewards} section. */
    private static void writeReward(YamlConfiguration yaml, String path, Reward reward) {
        if (reward.money() > 0) {
            yaml.set(path + ".money", reward.money());
        }
        if (!reward.commands().isEmpty()) {
            yaml.set(path + ".commands", reward.commands());
        }
    }
}
