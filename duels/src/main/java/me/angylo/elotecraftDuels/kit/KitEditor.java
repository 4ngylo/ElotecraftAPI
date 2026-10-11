package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * The kit editor's sessions: a copy of a kit (its 41 inventory slots, name, arena and rules) a player changes in the
 * editor menus, saved when they close the editor. Three kinds: a player's {@link Kind#LAYOUT layout} of an admin kit (only
 * its items moved around), a player's {@link Kind#CUSTOM custom kit} (items from the {@link KitPalette}) and an
 * {@link Kind#ADMIN admin kit} itself. The player's own inventory is never touched. Main thread only.
 */
public final class KitEditor implements Listener {

    /** A player inventory's slots: storage 0 to 35 (hotbar 0 to 8), boots to helmet 36 to 39, off hand 40. */
    public static final int SLOTS = 41;
    /** The longest name a player may give a kit. */
    public static final int MAX_NAME = 32;

    public enum Kind { LAYOUT, CUSTOM, ADMIN }

    /** One player's kit being edited. */
    public static final class Session {

        private final Kind kind;
        private final Kit kit;
        private final int customSlot;
        private final ItemStack[] items = new ItemStack[SLOTS];
        private final Map<KitRule, Object> rules = new EnumMap<>(KitRule.class);
        private final Set<Integer> changed = new HashSet<>();
        private String name;
        private String arena;
        private ItemStack last;
        private int picked = -1;
        private boolean asking;
        private boolean dirty;

        private Session(Kind kind, Kit kit, int customSlot, List<ItemStack> items, String name, String arena, Map<KitRule, Object> rules) {
            this.kind = kind;
            this.kit = kit;
            this.customSlot = customSlot;
            this.name = name;
            this.arena = arena;
            this.rules.putAll(rules);
            fill(items);
        }

        public Kind kind() {
            return kind;
        }

        /** The kit being arranged, the admin kit being edited, or the custom kits' base kit. */
        public Kit kit() {
            return kit;
        }

        /** The custom kit's slot, or 0. */
        public int customSlot() {
            return customSlot;
        }

        /** A copy of the item in inventory slot {@code slot}; empty if none. */
        public ItemStack item(int slot) {
            return items[slot].clone();
        }

        public List<ItemStack> items() {
            return Arrays.stream(items).map(ItemStack::clone).toList();
        }

        /** Puts {@code item} in inventory slot {@code slot}; a non-empty one becomes the last item, for copying. */
        public void set(int slot, ItemStack item) {
            items[slot] = item == null ? ItemStack.empty() : item.clone();
            changed.add(slot);
            dirty = true;
            if (!items[slot].isEmpty()) {
                last = items[slot].clone();
            }
        }

        /** The item last put in a slot, if any. */
        public Optional<ItemStack> last() {
            return Optional.ofNullable(last).map(ItemStack::clone);
        }

        /** How many slots changed since the editor opened. */
        public int changes() {
            return changed.size();
        }

        /** The name the player gave the kit, or null. */
        public String name() {
            return name;
        }

        public void name(String newName) {
            name = newName;
            dirty = true;
        }

        /** A custom kit's arena, or null for a random one. */
        public String arena() {
            return arena;
        }

        public void arena(String newArena) {
            arena = newArena;
            dirty = true;
        }

        /** A custom kit's own rules. */
        public Map<KitRule, Object> rules() {
            return Map.copyOf(rules);
        }

        /** @param value fitting {@code rule}, or null for the base kit's */
        public void rule(KitRule rule, Object value) {
            if (value == null) {
                rules.remove(rule);
            } else {
                rules.put(rule, value);
            }
            dirty = true;
        }

        /** The slot of the item a layout's player picked up to move, or -1. */
        public int picked() {
            return picked;
        }

        public void picked(int slot) {
            picked = slot;
        }

        /** Whether the player is typing an answer in an anvil, so the editor closing does not end the session. */
        public boolean asking() {
            return asking;
        }

        public void asking(boolean nowAsking) {
            asking = nowAsking;
        }

        private void fill(List<ItemStack> from) {
            for (int slot = 0; slot < SLOTS; slot++) {
                ItemStack item = slot < from.size() ? from.get(slot) : null;
                items[slot] = item == null ? ItemStack.empty() : item.clone();
            }
        }
    }

    private final Plugin plugin;
    private final Messages messages;
    private final KitLayouts layouts;
    private final CustomKits customKits;
    private final KitRegistry kits;
    private final Predicate<Player> busy;
    private final Map<UUID, Session> sessions = new HashMap<>();

    /** @param busy whether a player is in a duel, spectating, or otherwise cannot edit */
    public KitEditor(Plugin plugin, Messages messages, KitLayouts layouts, CustomKits customKits, KitRegistry kits, Predicate<Player> busy) {
        this.plugin = plugin;
        this.messages = messages;
        this.layouts = layouts;
        this.customKits = customKits;
        this.kits = kits;
        this.busy = busy;
    }

    public boolean isEditing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public Optional<Session> session(Player player) {
        return Optional.ofNullable(sessions.get(player.getUniqueId()));
    }

    /** Starts editing {@code player}'s layout of {@code kit}; empty, with the reason told, if they cannot. */
    public Optional<Session> start(Player player, Kit kit) {
        List<ItemStack> items = layouts.layout(player.getUniqueId(), kit).orElse(kit.items());
        return begin(player, new Session(Kind.LAYOUT, kit, 0, items, layouts.name(player.getUniqueId(), kit).orElse(null), null, Map.of()));
    }

    /** Starts editing {@code player}'s custom kit in {@code slot}; empty, with the reason told, if they cannot. */
    public Optional<Session> startCustom(Player player, int slot) {
        Optional<Kit> base = customKits.base();
        if (base.isEmpty()) {
            messages.send(player, "custom-kit.disabled");
            return Optional.empty();
        }
        if (!base.get().canUse(player)) {
            messages.send(player, "general.kit-locked", kitTag(base.get()));
            return Optional.empty();
        }
        if (slot < 1 || slot > customKits.slots(player)) {
            messages.send(player, "custom-kit.no-slot", Placeholder.unparsed("slots", String.valueOf(customKits.slots(player))));
            return Optional.empty();
        }
        Optional<KitLayouts.Saved> saved = customKits.saved(player, slot);
        return begin(player, new Session(Kind.CUSTOM, base.get(), slot, saved.map(KitLayouts.Saved::items).orElse(List.of()),
                saved.map(KitLayouts.Saved::name).orElse(null), saved.map(KitLayouts.Saved::arena).orElse(null),
                saved.map(KitLayouts.Saved::rules).orElse(Map.of())));
    }

    /** Starts editing the admin kit {@code kit} itself; empty, with the reason told, if {@code player} cannot. */
    public Optional<Session> startAdmin(Player player, Kit kit) {
        return begin(player, new Session(Kind.ADMIN, kit, 0, kit.items(), null, null, Map.of()));
    }

    private Optional<Session> begin(Player player, Session session) {
        // Editing counts as busy, so an editor already open is saved first.
        finish(player);
        if (busy.test(player) || player.isDead()) {
            messages.send(player, "general.busy-self");
            return Optional.empty();
        }
        sessions.put(player.getUniqueId(), session);
        return Optional.of(session);
    }

    /** Puts the items back as they were: the kit as made for a layout, nothing for a custom kit, the saved items for an admin kit. */
    public void resetItems(Session session) {
        List<ItemStack> items = switch (session.kind) {
            case LAYOUT -> session.kit.items();
            case CUSTOM -> List.of();
            case ADMIN -> kits.get(session.kit.name()).orElse(session.kit).items();
        };
        session.fill(items);
        session.picked = -1;
        session.dirty = true;
        for (int slot = 0; slot < SLOTS; slot++) {
            session.changed.add(slot);
        }
    }

    /** Ends {@code player}'s session, saving what changed. */
    public void finish(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null || !session.dirty) {
            return;
        }
        switch (session.kind) {
            case LAYOUT -> saveLayout(player, session);
            case CUSTOM -> saveCustom(player, session);
            case ADMIN -> saveAdmin(player, session);
        }
    }

    private void saveLayout(Player player, Session session) {
        List<ItemStack> layout = session.items();
        if (!session.kit.sameItems(layout)) {
            messages.send(player, "editor.invalid", kitTag(session.kit));
            return;
        }
        if (session.name == null && layout.equals(session.kit.items())) {
            layouts.reset(player.getUniqueId(), session.kit.name());
        } else {
            layouts.save(player.getUniqueId(), session.kit, layout, session.name);
        }
        messages.send(player, "editor.saved", kitTag(session.kit));
    }

    /** A custom kit is saved if every item is one the palette offers; no items deletes it. */
    private void saveCustom(Player player, Session session) {
        List<ItemStack> items = session.items();
        if (!customKits.fits(items)) {
            messages.send(player, "editor.custom-invalid");
            return;
        }
        customKits.store(player, session.customSlot, new KitLayouts.Saved(items, session.name, session.arena, session.rules));
        boolean empty = items.stream().allMatch(ItemStack::isEmpty);
        messages.send(player, empty ? "editor.custom-deleted" : "editor.custom-saved",
                Placeholder.unparsed("slot", String.valueOf(session.customSlot)));
    }

    /** Saves the items into the kit as it is now, so changes made meanwhile (a new name, rules) stay. */
    private void saveAdmin(Player player, Session session) {
        Optional<Kit> current = kits.get(session.kit.name());
        if (current.isEmpty()) {
            return;
        }
        kits.update(current.get().withItems(session.items())).whenComplete((ignored, error) -> Tasks.sync(plugin, () -> {
            if (error == null) {
                messages.send(player, "editor.admin-saved", kitTag(current.get()));
                return;
            }
            plugin.getLogger().log(Level.WARNING, "Could not save kit " + current.get().name(), error);
            messages.send(player, "admin.save-failed");
        }));
    }

    /** Drops {@code player}'s layout of {@code kit}: they get the kit as made again. */
    public void reset(Player player, Kit kit) {
        layouts.reset(player.getUniqueId(), kit.name());
        messages.send(player, "editor.reset", kitTag(kit));
    }

    /** Saves every session; for {@code onDisable}, after which no closing menu will. */
    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                finish(player);
            }
        }
        sessions.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        finish(event.getPlayer());
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }
}
