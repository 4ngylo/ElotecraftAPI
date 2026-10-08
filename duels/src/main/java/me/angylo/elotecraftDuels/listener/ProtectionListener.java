package me.angylo.elotecraftDuels.listener;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
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
 * spectating) cannot drop, pick up, store or trade items, use blocks, entities or bone meal, use other
 * commands than {@code /duel} and the configured ones, or teleport out of their arena. Placing and
 * breaking blocks is up to {@link BuildListener}.
 * <p>
 * Fighters of a kit with {@link KitRule#ITEM_DROPS} drop items; with it, {@link KitRule#BLOCK_DROPS} or
 * {@link KitRule#DEATH_DROPS} they pick items up inside their arena. Items
 * dropped in a duel are tagged so nothing outside it (players, mobs, hoppers) can take them, even if they
 * land past the arena's edge where the arena cleanup does not reach.
 */
public final class ProtectionListener implements Listener {

    private static final Set<InventoryType> OWN_INVENTORY = Set.of(InventoryType.CRAFTING, InventoryType.PLAYER);
    private static final String DUEL_DROP_TAG = "elotecraft-duels-drop";
    /** Any of them lets fighters pick items up; throwing items needs {@code item-drops}. */
    private static final List<KitRule> DROP_RULES = List.of(KitRule.ITEM_DROPS, KitRule.BLOCK_DROPS, KitRule.DEATH_DROPS);

    private final Messages messages;
    private final Supplier<Settings> settings;
    private final MatchManager matches;
    private final Command duelCommand;

    /** @param duelCommand the registered {@code /duel}, always allowed */
    public ProtectionListener(Messages messages, Supplier<Settings> settings, MatchManager matches, Command duelCommand) {
        this.messages = messages;
        this.settings = settings;
        this.matches = matches;
        this.duelCommand = duelCommand;
    }

    /** Marks an item dropped in a duel, by a fighter, a broken block or a knocked-out fighter. */
    static void markDuelDrop(Item item) {
        item.addScoreboardTag(DUEL_DROP_TAG);
    }

    private static boolean isDuelDrop(Item item) {
        return item.getScoreboardTags().contains(DUEL_DROP_TAG);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!matches.isRestricted(player)) {
            return;
        }
        if (dropMatch(player, List.of(KitRule.ITEM_DROPS)) != null) {
            markDuelDrop(event.getItemDrop());
        } else {
            event.setCancelled(true);
        }
    }

    /** Fighters of a kit with any drop rule pick up items inside their arena; duel drops reach nobody else. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (!(event.getEntity() instanceof Player player)) {
            if (isDuelDrop(item)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!matches.isRestricted(player) && !isDuelDrop(item)) {
            return;
        }
        Match match = dropMatch(player, DROP_RULES);
        if (match == null || !match.contains(item.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Hoppers would carry duel drops out of the arena. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (isDuelDrop(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** A duel drop merging into another item would hand its stack to an untagged item, or the other way. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        if (isDuelDrop(event.getEntity()) != isDuelDrop(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    /** The match {@code player} is fighting in if its kit has one of {@code rules} on, or null. */
    private Match dropMatch(Player player, List<KitRule> rules) {
        Match match = matches.matchOf(player).orElse(null);
        if (match == null || !match.isFighting(player)) {
            return null;
        }
        Settings current = settings.get();
        return rules.stream().anyMatch(rule -> match.kit().flag(rule, current)) ? match : null;
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

    /**
     * Blocks are not used (doors, buttons, beds...); items in hand still work. Build fighters use blocks in
     * their arena normally: denying block use also stops placing blocks against them.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Match match = matches.matchOf(player).orElse(null);
        if (match == null) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && !match.canBuild(player, event.getClickedBlock().getLocation())) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    /** A bed or respawn anchor in an arena must not become anyone's respawn point. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        cancelIfBusy(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        cancelIfBusy(event.getPlayer(), event);
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
        if (matches.isRestricted(player) && !allowed(event.getMessage())) {
            event.setCancelled(true);
            messages.send(player, "match.blocked-command");
        }
    }

    /** Pearls and chorus fruit work inside the arena; nothing (commands, other plugins, portals) gets out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Match match = matches.matchOf(player).orElse(null);
        if (match != null && !match.contains(event.getTo())) {
            event.setCancelled(true);
            messages.send(player, "match.blocked-teleport");
        }
    }

    private void cancelIfBusy(Player player, Cancellable event) {
        if (player != null && matches.isRestricted(player)) {
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
