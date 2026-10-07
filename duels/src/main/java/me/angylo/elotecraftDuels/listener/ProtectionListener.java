package me.angylo.elotecraftDuels.listener;

import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Keeps kit items inside duels and duels from touching the world. Players in a duel (fighting or
 * spectating) cannot drop, pick up, store or trade items, change blocks, use other commands than
 * {@code /duel} and the configured ones, or teleport out of their arena. Explosions never break
 * arena blocks.
 */
public final class ProtectionListener implements Listener {

    private static final Set<InventoryType> OWN_INVENTORY = Set.of(InventoryType.CRAFTING, InventoryType.PLAYER);

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final MatchManager matches;
    private final ArenaRegistry arenas;
    private final Command duelCommand;

    /** @param duelCommand the registered {@code /duel}, always allowed */
    public ProtectionListener(Messages messages, Supplier<Settings> settings, MatchManager matches,
                              ArenaRegistry arenas, Command duelCommand) {
        this.messages = messages;
        this.settings = settings;
        this.matches = matches;
        this.arenas = arenas;
        this.duelCommand = duelCommand;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfBusy(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        cancelIfBusy(remover == null ? null : CombatListener.attacker(remover), event);
    }

    /** Stripping logs, tilling, trampling and projectiles hitting blocks. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        cancelIfBusy(CombatListener.attacker(event.getEntity()), event);
    }

    /** Blocks are never used (doors, chests, buttons...); items in hand still work. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (!matches.isBusy(event.getPlayer())) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    /** Chests, ender chests and other plugins' menus: anything but the player's own inventory. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && !OWN_INVENTORY.contains(event.getInventory().getType())) {
            cancelIfBusy(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (matches.isBusy(player) && !allowed(event.getMessage())) {
            event.setCancelled(true);
            messages.send(player, "match.blocked-command");
        }
    }

    /** Pearls and chorus fruit work inside the arena; nothing (commands, other plugins, portals) gets out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Match match = matches.matchOf(player).orElse(null);
        if (match != null && !match.arena().contains(event.getTo())) {
            event.setCancelled(true);
            messages.send(player, "match.blocked-teleport");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectArenas(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectArenas(event.blockList());
    }

    private void protectArenas(List<Block> blocks) {
        var all = arenas.all();
        blocks.removeIf(block -> all.stream().anyMatch(arena -> arena.contains(block.getLocation())));
    }

    private void cancelIfBusy(Player player, Cancellable event) {
        if (player != null && matches.isBusy(player)) {
            event.setCancelled(true);
        }
    }

    /** {@code /duel} and the configured commands, by label, plugin prefix or alias. */
    private boolean allowed(String message) {
        String label = message.replaceFirst("^/", "").split(" ", 2)[0].toLowerCase(Locale.ROOT);
        Set<String> allowed = settings.get().allowedCommands();
        String plain = label.substring(label.indexOf(':') + 1);
        if (allowed.contains(label) || allowed.contains(plain)) {
            return true;
        }
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command == null) {
            return false;
        }
        return command == duelCommand || allowed.contains(command.getName().toLowerCase(Locale.ROOT))
                || command.getAliases().stream().map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(allowed::contains);
    }
}
