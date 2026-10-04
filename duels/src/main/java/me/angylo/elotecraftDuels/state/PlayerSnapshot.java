package me.angylo.elotecraftDuels.state;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Everything a duel changes about a player, captured before it starts and put back afterwards:
 * position, inventory (armor and off hand included), health, hunger, experience, effects, game mode,
 * flight and a few timers. {@link #id()} tells snapshots of the same player apart in storage.
 */
public record PlayerSnapshot(UUID id, String world, double x, double y, double z, float yaw, float pitch,
                             List<ItemStack> inventory, double health, double absorption, int food,
                             float saturation, float exhaustion, int level, float exp, int totalExperience,
                             List<PotionEffect> effects, GameMode gameMode, boolean allowFlight, boolean flying,
                             int fireTicks, float fallDistance, int remainingAir, int freezeTicks) {

    private static final int MAX_FOOD = 20;
    private static final float DEFAULT_SATURATION = 5;

    public PlayerSnapshot {
        inventory = inventory.stream().map(item -> item == null ? ItemStack.empty() : item.clone()).toList();
        effects = List.copyOf(effects);
    }

    public static PlayerSnapshot capture(Player player) {
        Location location = player.getLocation();
        return new PlayerSnapshot(UUID.randomUUID(), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
                Arrays.asList(player.getInventory().getContents()), player.getHealth(), player.getAbsorptionAmount(),
                player.getFoodLevel(), player.getSaturation(), player.getExhaustion(), player.getLevel(),
                player.getExp(), player.getTotalExperience(), List.copyOf(player.getActivePotionEffects()),
                player.getGameMode(), player.getAllowFlight(), player.isFlying(), player.getFireTicks(),
                player.getFallDistance(), player.getRemainingAir(), player.getFreezeTicks());
    }

    /** Where the player was; the main world's spawn if that world is gone. */
    public Location location() {
        World saved = Bukkit.getWorld(world);
        if (saved == null) {
            return Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        return new Location(saved, x, y, z, yaw, pitch);
    }

    /**
     * Deletes the item on the cursor and in the 2x2 crafting grid, then closes the inventory. Done before
     * swapping inventories, because closing would otherwise drop or return those items.
     */
    public static void clearLooseItems(Player player) {
        player.setItemOnCursor(null);
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top != null && top.getType() == InventoryType.CRAFTING) {
            top.clear();
        }
        player.closeInventory();
    }

    /**
     * Readies {@code player} for a fight: loose items cleared, survival mode without flight or effects,
     * full health and hunger, no fire, falling, drowning or freezing. The inventory is left to the kit.
     */
    public static void resetForDuel(Player player) {
        clearLooseItems(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.clearActivePotionEffects();
        player.setHealth(maxHealth(player));
        player.setAbsorptionAmount(0);
        player.setFoodLevel(MAX_FOOD);
        player.setSaturation(DEFAULT_SATURATION);
        player.setExhaustion(0);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setFreezeTicks(0);
    }

    /** Puts everything back except the position; see {@link #location()}. */
    public void applyState(Player player) {
        clearLooseItems(player);
        ItemStack[] contents = new ItemStack[player.getInventory().getSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = slot < inventory.size() ? inventory.get(slot).clone() : ItemStack.empty();
        }
        player.getInventory().setContents(contents);
        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.clearActivePotionEffects();
        player.addPotionEffects(effects);
        player.setHealth(health > 0 ? Math.min(health, maxHealth(player)) : maxHealth(player));
        player.setAbsorptionAmount(absorption);
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setLevel(level);
        player.setExp(exp);
        player.setTotalExperience(totalExperience);
        player.setFireTicks(fireTicks);
        player.setFallDistance(fallDistance);
        player.setRemainingAir(remainingAir);
        player.setFreezeTicks(freezeTicks);
    }

    /** Stored form; read back with {@link #fromText(String)}. */
    public String toText() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", id.toString());
        yaml.set("world", world);
        yaml.set("x", x);
        yaml.set("y", y);
        yaml.set("z", z);
        yaml.set("yaw", yaw);
        yaml.set("pitch", pitch);
        yaml.set("inventory", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(inventory)));
        yaml.set("health", health);
        yaml.set("absorption", absorption);
        yaml.set("food", food);
        yaml.set("saturation", saturation);
        yaml.set("exhaustion", exhaustion);
        yaml.set("level", level);
        yaml.set("exp", exp);
        yaml.set("total-experience", totalExperience);
        yaml.set("effects", effects);
        yaml.set("game-mode", gameMode.name());
        yaml.set("allow-flight", allowFlight);
        yaml.set("flying", flying);
        yaml.set("fire-ticks", fireTicks);
        yaml.set("fall-distance", fallDistance);
        yaml.set("remaining-air", remainingAir);
        yaml.set("freeze-ticks", freezeTicks);
        return yaml.saveToString();
    }

    /** @throws IllegalArgumentException if {@code text} is not a stored snapshot */
    public static PlayerSnapshot fromText(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
            List<PotionEffect> effects = new ArrayList<>();
            for (Object effect : yaml.getList("effects", List.of())) {
                if (effect instanceof PotionEffect potion) {
                    effects.add(potion);
                }
            }
            return new PlayerSnapshot(UUID.fromString(yaml.getString("id", "")), yaml.getString("world", ""),
                    yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                    (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"),
                    Arrays.asList(ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(yaml.getString("inventory", "")))),
                    yaml.getDouble("health", 20), yaml.getDouble("absorption"), yaml.getInt("food", 20),
                    (float) yaml.getDouble("saturation", 5), (float) yaml.getDouble("exhaustion"),
                    yaml.getInt("level"), (float) yaml.getDouble("exp"), yaml.getInt("total-experience"), effects,
                    GameMode.valueOf(yaml.getString("game-mode", GameMode.SURVIVAL.name())),
                    yaml.getBoolean("allow-flight"), yaml.getBoolean("flying"), yaml.getInt("fire-ticks"),
                    (float) yaml.getDouble("fall-distance"), yaml.getInt("remaining-air", 300), yaml.getInt("freeze-ticks"));
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IllegalArgumentException("Not a stored player snapshot: " + e.getMessage(), e);
        }
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20 : attribute.getValue();
    }
}
