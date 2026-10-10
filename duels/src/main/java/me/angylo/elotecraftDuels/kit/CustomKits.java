package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftDuels.PermissionLimits;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * Players' custom kits: up to {@code custom-kits.slots} each (more with {@code duels.kit.custom.slots.<n>}), built in the
 * {@link KitEditor} from the items of the {@link KitPalette} and stored with the kit layouts under {@code custom:<slot>},
 * with the name, arena and rules the player picked. A custom kit takes everything else from the base kit
 * ({@code custom-kits.base-kit}, which may hold no items); one holding an item the palette no longer offers is not
 * offered until it is built again. Main thread only.
 */
public final class CustomKits {

    private static final String KEY = Kit.CUSTOM + ":";
    /** {@code custom} or {@code custom:<slot>}, typed where a kit goes. */
    private static final Pattern ARGUMENT = Pattern.compile(Kit.CUSTOM + "(?::(\\d))?");

    private final Supplier<Settings> settings;
    private final KitRegistry kits;
    private final ArenaRegistry arenas;
    private final KitLayouts layouts;
    private final KitPalette palette;

    public CustomKits(Supplier<Settings> settings, KitRegistry kits, ArenaRegistry arenas, KitLayouts layouts, KitPalette palette) {
        this.settings = settings;
        this.kits = kits;
        this.arenas = arenas;
        this.layouts = layouts;
        this.palette = palette;
    }

    /** The kit custom kits take their rules, arenas and the rest from; empty when they are off or it is missing. */
    public Optional<Kit> base() {
        Settings.CustomKitOptions options = settings.get().customKits();
        return options.enabled() ? kits.get(options.baseKit()) : Optional.empty();
    }

    /**
     * How many custom kits {@code player} keeps: config.yml {@code custom-kits.slots}, raised by their
     * {@code duels.kit.custom.slots.<n>} permission. Kits in slots past it are kept but not offered.
     */
    public int slots(Player player) {
        return PermissionLimits.highest(player, PermissionLimits.CUSTOM_KIT_SLOTS, settings.get().customKits().slots(),
                Settings.MAX_CUSTOM_KITS);
    }

    /** Whether every item in {@code items} is one the palette offers ({@link KitPalette#allows}). */
    public boolean fits(List<ItemStack> items) {
        return items.stream().filter(item -> item != null && !item.isEmpty()).allMatch(palette::allows);
    }

    /** {@code player}'s custom kit in {@code slot} (1 and up), if they built one that still fits the palette. */
    public Optional<Kit> get(Player player, int slot) {
        Optional<Kit> base = base();
        Optional<KitLayouts.Saved> saved = saved(player, slot);
        if (base.isEmpty() || saved.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStack> items = saved.get().items();
        if (items.stream().allMatch(ItemStack::isEmpty) || !fits(items)) {
            return Optional.empty();
        }
        // An arena that is gone or no longer taken by the base kit: any arena, as if none was picked.
        String arena = saved.get().arena() == null ? null
                : arenas.get(saved.get().arena()).filter(base.get()::accepts).map(found -> found.name()).orElse(null);
        return Optional.of(Kit.custom(base.get(), displayName(player, slot, saved.get().name()), items, saved.get().rules(), arena));
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

    /** What {@code player} stored in {@code slot}, unchecked; empty if nothing or the slot is past theirs. */
    public Optional<KitLayouts.Saved> saved(Player player, int slot) {
        return slot < 1 || slot > slots(player) ? Optional.empty() : layouts.stored(player.getUniqueId(), KEY + slot);
    }

    /** Stores {@code saved} in {@code player}'s {@code slot}; no items in it deletes the kit. Callers check {@link #fits}. */
    void store(Player player, int slot, KitLayouts.Saved saved) {
        if (saved.items().stream().allMatch(ItemStack::isEmpty)) {
            layouts.reset(player.getUniqueId(), KEY + slot);
        } else {
            layouts.store(player.getUniqueId(), KEY + slot, saved);
        }
    }

    /** The name duels show: the one the player gave it, or config.yml {@code custom-kits.display-name}. */
    private String displayName(Player player, int slot, String name) {
        if (name != null) {
            return MiniMessage.miniMessage().escapeTags(name);
        }
        // Player names are letters, digits and _, so they cannot add MiniMessage tags.
        return settings.get().customKits().displayName().replace("<player>", player.getName()).replace("<slot>", String.valueOf(slot));
    }
}
