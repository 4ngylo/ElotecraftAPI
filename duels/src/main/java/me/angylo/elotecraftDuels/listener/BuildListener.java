package me.angylo.elotecraftDuels.listener;

import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaInstance;
import me.angylo.elotecraftDuels.arena.ArenaInstances;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Blocks in duels. Fighters with a build kit may place blocks inside their arena while fighting, and break
 * the ones placed during the duel (any block with {@code build.break-arena-blocks}); everyone else in a duel
 * changes nothing. Broken blocks drop their item only with the kit's {@link KitRule#BLOCK_DROPS}. Every block a build duel changes, by players, fluids, fire, falling blocks or
 * explosions, is recorded so the arena can be put back, and nothing flows, burns, falls or blows up out of
 * the arena. Pistons, dispensers, trees and sponges do not work in it. Explosions never break other arena
 * blocks.
 * <p>
 * Changes are recorded when they are allowed, even if another plugin cancels them later: putting back a
 * block that never changed does nothing.
 */
public final class BuildListener implements Listener {

    private static final List<BlockFace> NEIGHBOURS = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN);

    private final Supplier<Settings> settings;
    private final MatchManager matches;
    private final ArenaInstances instances;
    private final ArenaRegistry arenas;

    public BuildListener(Supplier<Settings> settings, MatchManager matches, ArenaInstances instances, ArenaRegistry arenas) {
        this.settings = settings;
        this.matches = matches;
        this.instances = instances;
        this.arenas = arenas;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!matches.isRestricted(player)) {
            return;
        }
        ArenaInstance instance = buildable(player, event.getBlock());
        List<BlockState> replaced = event instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates() : List.of(event.getBlockReplacedState());
        if (instance == null || !replaced.stream().allMatch(state -> instance.allowsPlacingAt(state.getLocation()))
                || bridgeProtects(player, replaced)) {
            event.setCancelled(true);
            return;
        }
        replaced.forEach(instance.changes()::record);
        replaced.forEach(state -> recordNeighbours(instance, state.getBlock()));
    }

    /** {@link KitRule#AUTO_IGNITE_TNT}: placed TNT is lit at once, as if its placer lit it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceTnt(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();
        if (block.getType() != Material.TNT || !matches.matchOf(player)
                .filter(match -> match.kit().flag(KitRule.AUTO_IGNITE_TNT, settings.get())).isPresent()) {
            return;
        }
        block.setType(Material.AIR);
        block.getWorld().spawn(block.getLocation().toCenterLocation(), TNTPrimed.class, tnt -> tnt.setSource(player));
    }

    /**
     * Build fighters use blocks normally in their arena ({@link ProtectionListener} lets them, as denying
     * it would stop block placing too); what they click (doors, levers, both halves) is put back afterwards.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null || event.useInteractedBlock() == Event.Result.DENY
                || !matches.isRestricted(event.getPlayer())) {
            return;
        }
        ArenaInstance instance = buildable(event.getPlayer(), clicked);
        if (instance != null) {
            instance.changes().remember(clicked);
            recordNeighbours(instance, clicked);
        }
    }

    /** Bridge: nobody builds near a spawn or goal, so neither can be walled off. */
    private boolean bridgeProtects(Player player, List<BlockState> replaced) {
        Match match = matches.matchOf(player).orElse(null);
        int radius = settings.get().modes().protectRadius();
        return match != null && match.mode() == Kit.Mode.BRIDGE
                && replaced.stream().anyMatch(state -> match.nearSpawnOrGoal(state.getLocation(), radius));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (breaksBed(event)) {
            return;
        }
        if (!removeBlock(event.getPlayer(), event.getBlock(), event)) {
            return;
        }
        event.setExpToDrop(0);
        boolean drops = matches.matchOf(event.getPlayer())
                .map(match -> match.kit().flag(KitRule.BLOCK_DROPS, settings.get())).orElse(false);
        if (!drops) {
            event.setDropItems(false);
        }
    }

    /**
     * Bed fight: a fighter breaks a side's bed, an arena block, which is put back with the arena. Their own bed
     * stays.
     *
     * @return whether the block was a side's bed
     */
    private boolean breaksBed(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Match match = matches.matchOf(player).orElse(null);
        if (match == null || match.mode() != Kit.Mode.BED_FIGHT || !Tag.BEDS.isTagged(block.getType())
                || !match.instance().isBuild() || match.bedAt(block) < 0) {
            return false;
        }
        if (!matches.breakBed(player, match.bedAt(block))) {
            event.setCancelled(true);
            return true;
        }
        match.instance().changes().record(block);
        recordNeighbours(match.instance(), block);
        event.setDropItems(false);
        event.setExpToDrop(0);
        return true;
    }

    /** Items from blocks broken in a build duel stay in it: see {@link ProtectionListener}. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (matches.isRestricted(event.getPlayer())) {
            event.getItems().forEach(ProtectionListener::markDuelDrop);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        addBlock(event.getPlayer(), event.getBlock(), event);
    }

    /** Scooping up water or lava takes a block away, like breaking it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        removeBlock(event.getPlayer(), event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            addBlock(event.getPlayer(), event.getBlock(), event);
            return;
        }
        Block source = event.getIgnitingBlock();
        if (source != null && escapes(source, event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        natural(event.getBlock(), event, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (escapes(event.getBlock(), event.getToBlock())) {
            event.setCancelled(true);
            return;
        }
        natural(event.getToBlock(), event, false);
    }

    /** Ice, snow, concrete, cobblestone from lava and the like; also frost walker. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        natural(event.getBlock(), event, false);
    }

    /** Fire, grass and mushrooms spreading. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (escapes(event.getSource(), event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        natural(event.getBlock(), event, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        natural(event.getBlock(), event, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block source = event.getIgnitingBlock();
        if (source != null && escapes(source, event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        natural(event.getBlock(), event, true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        natural(event.getBlock(), event, false);
    }

    /** Trees and sponges change many blocks, some maybe outside the arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (instances.buildAt(event.getLocation()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent event) {
        if (instances.buildAt(event.getBlock().getLocation()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /** Dispensers place fluids, fire, blocks and bone meal without a player event, inside the arena or out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (instances.buildAt(event.getBlock().getLocation()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent event) {
        natural(event.getBlock(), event, true);
    }

    /** Mobs from spawn eggs in a fight belong to it: they are removed with the arena's leftovers. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || reason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG) {
            instances.at(event.getLocation()).ifPresent(instance -> instances.markLeftover(event.getEntity()));
        }
    }

    /** Sand and gravel falling and landing; changes by players were already cancelled by {@link ProtectionListener}. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        Location origin = event.getEntity() instanceof FallingBlock falling ? falling.getOrigin() : null;
        if (origin != null && escapes(origin, event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getEntity().remove();
            return;
        }
        natural(event.getBlock(), event, false);
    }

    /** Pistons could push blocks out of the arena or pull arena blocks away. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (instances.buildAt(event.getBlock().getLocation()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (instances.buildAt(event.getBlock().getLocation()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Player cause = CombatListener.attacker(event.getEntity());
        Match match = cause == null ? null : matches.matchOf(cause).orElse(null);
        ArenaInstance source = match != null ? match.instance() : instances.at(event.getLocation()).orElse(null);
        if (explode(source, event.blockList())) {
            event.setYield(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (explode(instances.at(event.getBlock().getLocation()).orElse(null), event.blockList())) {
            event.setYield(0);
        }
    }

    /**
     * An explosion from a duel (its arena, or TNT a player in it lit) breaks only what that duel may break:
     * blocks placed during a build duel, or any of its arena's blocks if allowed, and nothing outside it.
     * Other explosions never break arena blocks.
     *
     * @param source the duel the explosion comes from, or null
     * @return whether it came from a duel, so its blocks must not drop items
     */
    private boolean explode(ArenaInstance source, List<Block> blocks) {
        if (source != null) {
            blocks.removeIf(block -> {
                if (!source.contains(block.getLocation()) || !source.isBuild() || source.isClosing() || !mayRemove(source, block)) {
                    return true;
                }
                source.changes().record(block);
                return false;
            });
            return true;
        }
        List<Arena> all = arenas.all();
        blocks.removeIf(block -> {
            ArenaInstance instance = instances.at(block.getLocation()).orElse(null);
            if (instance == null) {
                return all.stream().anyMatch(arena -> arena.contains(block.getLocation()));
            }
            if (!instance.isBuild() || instance.isClosing() || !mayRemove(instance, block)) {
                return true;
            }
            instance.changes().record(block);
            return false;
        });
        return false;
    }

    /**
     * A player in a duel puts something into {@code block}: allowed only in their build duel's arena, up to
     * its build limit.
     */
    private void addBlock(Player player, Block block, Cancellable event) {
        if (!matches.isRestricted(player)) {
            return;
        }
        ArenaInstance instance = buildable(player, block);
        if (instance == null || !instance.allowsPlacingAt(block.getLocation())) {
            event.setCancelled(true);
            return;
        }
        instance.changes().record(block);
    }

    /**
     * A player in a duel takes {@code block} away: allowed only in their build duel's arena, and only for
     * blocks placed during the duel unless arena blocks may be broken.
     *
     * @return whether it is allowed
     */
    private boolean removeBlock(Player player, Block block, Cancellable event) {
        if (!matches.isRestricted(player)) {
            return false;
        }
        ArenaInstance instance = buildable(player, block);
        if (instance == null || !mayRemove(instance, block)) {
            event.setCancelled(true);
            return false;
        }
        instance.changes().record(block);
        recordNeighbours(instance, block);
        return true;
    }

    /** The build duel {@code player} may change {@code block} in right now, or null. */
    private ArenaInstance buildable(Player player, Block block) {
        Match match = matches.matchOf(player).orElse(null);
        return match != null && match.canBuild(player, block.getLocation()) ? match.instance() : null;
    }

    private boolean mayRemove(ArenaInstance instance, Block block) {
        return settings.get().breakArenaBlocks() || instance.changes().changed(block);
    }

    /**
     * A change nobody did directly. Inside a build duel it is recorded; it is stopped while the arena is
     * being put back, or if it would remove an arena block that may not be broken.
     */
    private void natural(Block block, Cancellable event, boolean removesBlock) {
        ArenaInstance instance = instances.buildAt(block.getLocation()).orElse(null);
        if (instance == null) {
            return;
        }
        if (instance.isClosing() || (removesBlock && !mayRemove(instance, block))) {
            event.setCancelled(true);
            return;
        }
        instance.changes().record(block);
    }

    /**
     * Fences, panes, walls, chests and door halves next to a changed block change shape with it; they are
     * remembered so they get their old shape back.
     */
    private static void recordNeighbours(ArenaInstance instance, Block block) {
        for (BlockFace face : NEIGHBOURS) {
            Block neighbour = block.getRelative(face);
            if (instance.contains(neighbour.getLocation())) {
                instance.changes().remember(neighbour);
            }
        }
    }

    /** Whether something would spread from a build duel's arena to outside it. */
    private boolean escapes(Block from, Block to) {
        return escapes(from.getLocation(), to.getLocation());
    }

    private boolean escapes(Location from, Location to) {
        return instances.buildAt(from).filter(instance -> !instance.contains(to)).isPresent();
    }
}
