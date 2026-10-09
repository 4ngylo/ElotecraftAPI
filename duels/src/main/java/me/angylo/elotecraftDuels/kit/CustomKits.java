package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.PermissionLimits;
import me.angylo.elotecraftDuels.Settings;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Players' custom kits: up to {@code custom-kits.slots} each (more with {@code duels.kit.custom.slots.<n>}), built in the {@link KitEditor} from the items of the
 * base kit ({@code custom-kits.base-kit}) and stored with the kit layouts under {@code custom:<slot>}. A custom kit
 * is the base kit with the player's items ({@link Kit#custom}); one holding an item the base kit no longer has is
 * not offered until it is built again. Main thread only.
 */
public final class CustomKits {

    private static final String KEY = Kit.CUSTOM + ":";
    /** {@code custom} or {@code custom:<slot>}, typed where a kit goes. */
    private static final Pattern ARGUMENT = Pattern.compile(Kit.CUSTOM + "(?::(\\d))?");

    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final KitLayouts layouts;

    public CustomKits(Supplier<Settings> settings, KitRegistry kits, KitLayouts layouts) {
        this.settings = settings;
        this.kits = kits;
        this.layouts = layouts;
    }

    /** The kit custom kits are built from; empty when they are off or it is missing or empty. */
    public Optional<Kit> base() {
        Settings.CustomKitOptions options = settings.get().customKits();
        return options.enabled() ? kits.get(options.baseKit()).filter(kit -> !kit.isEmpty()) : Optional.empty();
    }

    /**
     * How many custom kits {@code player} keeps: config.yml {@code custom-kits.slots}, raised by their
     * {@code duels.kit.custom.slots.<n>} permission. Kits in slots past it are kept but not offered.
     */
    public int slots(Player player) {
        return PermissionLimits.highest(player, PermissionLimits.CUSTOM_KIT_SLOTS, settings.get().customKits().slots(),
                Settings.MAX_CUSTOM_KITS);
    }

    /** The base kit's items, each once: what custom kits are built from. */
    public static List<ItemStack> palette(Kit base) {
        List<ItemStack> palette = new ArrayList<>();
        for (ItemStack item : base.items()) {
            if (!item.isEmpty() && palette.stream().noneMatch(item::isSimilar)) {
                palette.add(item);
            }
        }
        return palette;
    }

    /** Whether every item in {@code items} is one of the base kit's (any amount up to a full stack). */
    public static boolean fits(Kit base, List<ItemStack> items) {
        List<ItemStack> palette = palette(base);
        return items.stream().filter(item -> item != null && !item.isEmpty())
                .allMatch(item -> item.getAmount() <= item.getMaxStackSize() && palette.stream().anyMatch(item::isSimilar));
    }

    /** {@code player}'s custom kit in {@code slot} (1 and up), if they built one that still fits the base kit. */
    public Optional<Kit> get(Player player, int slot) {
        Optional<Kit> base = base();
        if (base.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStack> items = items(player, slot);
        return items.stream().allMatch(ItemStack::isEmpty) || !fits(base.get(), items) ? Optional.empty()
                : Optional.of(Kit.custom(base.get(), displayName(player, slot), items));
    }

    /** {@code player}'s custom kits they can use now, by slot. */
    public List<Kit> of(Player player) {
        List<Kit> custom = new ArrayList<>();
        for (int slot = 1; slot <= slots(player); slot++) {
            get(player, slot).filter(kit -> kit.canUse(player)).ifPresent(custom::add);
        }
        return custom;
    }

    /** The arguments naming {@code player}'s usable custom kits, for tab completion. */
    public List<String> arguments(Player player) {
        List<String> names = new ArrayList<>();
        for (int slot = 1; slot <= slots(player); slot++) {
            if (get(player, slot).filter(kit -> kit.canUse(player)).isPresent()) {
                names.add(KEY + slot);
            }
        }
        return names;
    }

    /**
     * The kit {@code name} names for {@code player}: {@code custom} (slot 1) or {@code custom:<slot>} their custom kit,
     * anything else an admin kit.
     */
    public Optional<Kit> resolve(Player player, String name) {
        Matcher matcher = ARGUMENT.matcher(name.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return kits.get(name);
        }
        return get(player, matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1)));
    }

    /** What {@code player} stored in {@code slot}, unchecked; empty if nothing. */
    public List<ItemStack> items(Player player, int slot) {
        if (slot < 1 || slot > slots(player)) {
            return List.of();
        }
        return layouts.stored(player.getUniqueId(), KEY + slot).orElse(List.of());
    }

    /** Stores {@code items} in {@code player}'s {@code slot}; nothing in them deletes the kit. Callers check {@link #fits}. */
    void store(Player player, int slot, List<ItemStack> items) {
        if (items.stream().allMatch(item -> item == null || item.isEmpty())) {
            layouts.reset(player.getUniqueId(), KEY + slot);
        } else {
            layouts.store(player.getUniqueId(), KEY + slot, items);
        }
    }

    private String displayName(Player player, int slot) {
        // Player names are letters, digits and _, so they cannot add MiniMessage tags.
        return settings.get().customKits().displayName().replace("<player>", player.getName()).replace("<slot>", String.valueOf(slot));
    }
}
