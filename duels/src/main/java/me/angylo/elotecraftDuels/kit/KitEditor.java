package me.angylo.elotecraftDuels.kit;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Tasks;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import me.angylo.elotecraftDuels.state.SnapshotStore;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The kit editor: a player gets a kit in their own inventory, moves the items around and saves that as
 * their layout of the kit. Or, for a {@link CustomKits custom kit}, they get the one they built in a slot (or
 * nothing) and pick items from the base kit's (the {@code /duel customkit items} menu) until they save it. Their inventory is saved to the database first and put back afterwards, so a
 * crash cannot lose it. While editing they count as busy (no queues, duels or other plugins' menus), in
 * adventure mode, and cannot drop, pick up, use or place items. Main thread only.
 */
public final class KitEditor implements Listener {

    private static final long MILLIS_PER_TICK = 50;

    private static final class Session {
        /** The kit being arranged, or the base kit of a custom kit. */
        private final Kit kit;
        /** The custom kit's slot, or 0 for a layout. */
        private final int customSlot;
        private final PlayerSnapshot saved;
        private BukkitTask timeout;
        private boolean ready;

        private Session(Kit kit, int customSlot, PlayerSnapshot saved) {
            this.kit = kit;
            this.customSlot = customSlot;
            this.saved = saved;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final SnapshotStore snapshots;
    private final KitLayouts layouts;
    private final CustomKits customKits;
    private final Predicate<Player> busy;
    private final Map<UUID, Session> sessions = new HashMap<>();

    /** @param busy whether a player is in a duel, spectating, or otherwise cannot edit */
    public KitEditor(Plugin plugin, Messages messages, Supplier<Settings> settings, SnapshotStore snapshots,
                     KitLayouts layouts, CustomKits customKits, Predicate<Player> busy) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messages = messages;
        this.settings = settings;
        this.snapshots = snapshots;
        this.layouts = layouts;
        this.customKits = customKits;
        this.busy = busy;
    }

    public boolean isEditing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** Saves {@code player}'s inventory, then gives them their layout of {@code kit} to rearrange. */
    public void start(Player player, Kit kit) {
        start(player, kit, 0);
    }

    /** Saves {@code player}'s inventory, then gives them their custom kit in {@code slot} to build. */
    public void startCustom(Player player, int slot) {
        Optional<Kit> base = customKits.base();
        if (base.isEmpty()) {
            messages.send(player, "custom-kit.disabled");
        } else if (!base.get().canUse(player)) {
            messages.send(player, "general.kit-locked", kitTag(base.get()));
        } else if (slot < 1 || slot > customKits.slots(player)) {
            messages.send(player, "custom-kit.no-slot", Placeholder.unparsed("slots", String.valueOf(customKits.slots(player))));
        } else {
            start(player, base.get(), slot);
        }
    }

    /** The base kit {@code player} is building a custom kit from, if they are. */
    public Optional<Kit> buildingFrom(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.ready && session.customSlot > 0 ? Optional.of(session.kit) : Optional.empty();
    }

    private void start(Player player, Kit kit, int customSlot) {
        if (isEditing(player)) {
            messages.send(player, "editor.already");
            return;
        }
        if (busy.test(player) || player.isDead()) {
            messages.send(player, "general.busy-self");
            return;
        }
        player.closeInventory();
        Session session = new Session(kit, customSlot, PlayerSnapshot.capture(player));
        CompletableFuture<Void> saved;
        try {
            saved = snapshots.save(Map.of(player.getUniqueId(), session.saved));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not save " + player.getName() + " before the kit editor", e);
            messages.send(player, "general.storage-error");
            return;
        }
        sessions.put(player.getUniqueId(), session);
        saved.whenComplete((ignored, error) -> Tasks.sync(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session || !player.isOnline()) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Could not save " + player.getName() + " before the kit editor", error);
                sessions.remove(player.getUniqueId());
                messages.send(player, "general.storage-error");
                return;
            }
            PlayerSnapshot.resetForDuel(player);
            player.setGameMode(GameMode.ADVENTURE);
            if (customSlot > 0) {
                Kit.apply(player, customKits.items(player, customSlot));
            } else {
                layouts.apply(player, kit);
            }
            session.ready = true;
            session.timeout = Tasks.later(plugin, () -> timeOut(player, session),
                    settings.get().kitEditorTimeout().toMillis() / MILLIS_PER_TICK);
            if (customSlot > 0) {
                messages.send(player, "editor.custom-started", kitTag(kit), Placeholder.unparsed("slot", String.valueOf(customSlot)),
                        Placeholder.styling("items", ClickEvent.runCommand("/duel customkit items"),
                                HoverEvent.showText(messages.get(player, "editor.items-hover"))),
                        Placeholder.styling("save", ClickEvent.runCommand("/duel editkit save"),
                                HoverEvent.showText(messages.get(player, "editor.save-hover"))),
                        Placeholder.styling("cancel", ClickEvent.runCommand("/duel editkit cancel"),
                                HoverEvent.showText(messages.get(player, "editor.cancel-hover"))));
                player.performCommand("duel customkit items");
                return;
            }
            messages.send(player, "editor.started", kitTag(kit),
                    Placeholder.styling("save", ClickEvent.runCommand("/duel editkit save"),
                            HoverEvent.showText(messages.get(player, "editor.save-hover"))),
                    Placeholder.styling("cancel", ClickEvent.runCommand("/duel editkit cancel"),
                            HoverEvent.showText(messages.get(player, "editor.cancel-hover"))));
        }));
    }

    /** Stores the player's inventory as their layout if it holds exactly the kit's items, then gives theirs back. */
    public void save(Player player) {
        Session session = readySession(player);
        if (session == null) {
            return;
        }
        player.closeInventory();
        List<ItemStack> layout = Arrays.asList(player.getInventory().getContents());
        if (session.customSlot > 0) {
            saveCustom(player, session, layout);
            return;
        }
        if (!session.kit.sameItems(layout)) {
            messages.send(player, "editor.invalid", kitTag(session.kit));
            return;
        }
        layouts.save(player.getUniqueId(), session.kit, layout);
        finish(player, session, false);
        messages.send(player, "editor.saved", kitTag(session.kit));
    }

    /** A custom kit is saved if every item came from the base kit; an empty inventory deletes it. */
    private void saveCustom(Player player, Session session, List<ItemStack> items) {
        if (!CustomKits.fits(session.kit, items)) {
            messages.send(player, "editor.custom-invalid", kitTag(session.kit));
            return;
        }
        customKits.store(player, session.customSlot, items);
        finish(player, session, false);
        boolean empty = items.stream().allMatch(item -> item == null || item.isEmpty());
        messages.send(player, empty ? "editor.custom-deleted" : "editor.custom-saved",
                Placeholder.unparsed("slot", String.valueOf(session.customSlot)));
    }

    public void cancel(Player player) {
        Session session = readySession(player);
        if (session != null) {
            finish(player, session, false);
            messages.send(player, "editor.cancelled");
        }
    }

    /** Drops {@code player}'s layout of {@code kit}: they get the kit as made again. */
    public void reset(Player player, Kit kit) {
        layouts.reset(player.getUniqueId(), kit.name());
        messages.send(player, "editor.reset", kitTag(kit));
    }

    /** Puts everyone back right away; for {@code onDisable}. */
    public void shutdown() {
        for (Map.Entry<UUID, Session> entry : Map.copyOf(sessions).entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && entry.getValue().ready) {
                finish(player, entry.getValue(), true);
            }
        }
        sessions.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        if (session.ready) {
            finish(event.getPlayer(), session, true);
        } else {
            // Still saving: the stored snapshot restores them on their next join.
            sessions.remove(event.getPlayer().getUniqueId());
        }
    }

    /** No potions, pearls, food or block use while editing. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private void timeOut(Player player, Session session) {
        if (sessions.get(player.getUniqueId()) == session && player.isOnline()) {
            finish(player, session, false);
            messages.send(player, "editor.timed-out");
        }
    }

    /** The session of a player whose kit is in their hands; explains otherwise. */
    private Session readySession(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.ready) {
            messages.send(player, "editor.not-editing");
            return null;
        }
        return session;
    }

    private void finish(Player player, Session session, boolean teleportNow) {
        sessions.remove(player.getUniqueId());
        if (session.timeout != null) {
            session.timeout.cancel();
        }
        player.closeInventory();
        snapshots.restore(player, session.saved, teleportNow);
    }

    private static TagResolver kitTag(Kit kit) {
        return Placeholder.component("kit", Text.mm(kit.displayName()));
    }
}
