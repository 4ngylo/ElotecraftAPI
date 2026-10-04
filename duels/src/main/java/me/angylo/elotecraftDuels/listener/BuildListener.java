package me.angylo.elotecraftDuels.listener;

import me.angylo.elotecraftDuels.Settings;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaInstance;
import me.angylo.elotecraftDuels.arena.ArenaInstances;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.MatchManager;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
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
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Blocks in duels. Fighters with a build kit may place blocks inside their arena while fighting, and break
 * the ones placed during the duel (any block with {@code build.break-arena-blocks}); everyone else in a duel
 * changes nothing. Every block a build duel changes, by players, fluids, fire, falling blocks or
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
        if (!matches.isBusy(player)) {
            return;
        }
        ArenaInstance instance = buildable(player, event.getBlock());
        List<BlockState> replaced = event instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates() : List.of(event.getBlockReplacedState());
        if (instance == null || !replaced.stream().allMatch(state -> instance.contains(state.getLocation()))) {
            event.setCancelled(true);
            return;
        }
        replaced.forEach(instance.changes()::record);
        replaced.forEach(state -> recordNeighbours(instance, state.getBlock()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!removeBlock(event.getPlayer(), event.getBlock(), event)) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
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

    /** A player in a duel puts something into {@code block}: allowed only in their build duel's arena. */
    private void addBlock(Player player, Block block, Cancellable event) {
        if (!matches.isBusy(player)) {
            return;
        }
        ArenaInstance instance = buildable(player, block);
        if (instance == null) {
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
        if (!matches.isBusy(player)) {
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
        if (match == null || !match.isFighting(player)) {
            return null;
        }
        ArenaInstance instance = match.instance();
        return instance.isBuild() && !instance.isClosing() && instance.contains(block.getLocation()) ? instance : null;
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
     * Fences, panes, walls and chests next to a placed or broken block change shape to connect; they are
     * recorded so they get their old shape back.
     */
    private static void recordNeighbours(ArenaInstance instance, Block block) {
        for (BlockFace face : NEIGHBOURS) {
            Block neighbour = block.getRelative(face);
            if (instance.contains(neighbour.getLocation())) {
                instance.changes().record(neighbour);
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
