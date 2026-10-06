package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Duels never leak items or effects into the world, and the world never reaches into a duel. */
class ProtectionTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private TestPlayer outsider;
    private Match match;

    @BeforeEach
    void startDuel() {
        alex = join("Alex");
        steve = join("Steve");
        outsider = join("Outsider");
        assertTrue(duels.matches().start(alex, steve, swordKit(), readyArena("pit")));
        match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
    }

    private <T extends Event & Cancellable> boolean cancelled(T event) {
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void fightersCannotUseTheWorldButOutsidersCan() {
        Block bed = arenaWorld.getBlockAt(alex.getLocation());
        Item item = arenaWorld.dropItem(alex.getLocation(), ItemStack.of(Material.DIAMOND));

        assertTrue(cancelled(new PlayerInteractEvent(alex, Action.PHYSICAL, null, bed, null)));
        assertTrue(cancelled(new PlayerInteractEntityEvent(alex, steve)));
        assertTrue(cancelled(new EntityPickupItemEvent(alex, item, 0)));

        assertFalse(cancelled(new PlayerInteractEvent(outsider, Action.PHYSICAL, null, bed, null)));
        assertFalse(cancelled(new PlayerInteractEntityEvent(outsider, steve)));
        assertFalse(cancelled(new EntityPickupItemEvent(outsider, item, 0)));
    }

    @Test
    void onlyTheOpponentCanHurtAFighter() {
        assertTrue(alex.simulateDamage(2, outsider).isCancelled());
        assertTrue(outsider.simulateDamage(2, alex).isCancelled());
        assertFalse(alex.simulateDamage(2, steve).isCancelled());
    }

    @Test
    void nobodyIsHurtBeforeTheFight() {
        TestPlayer first = join("First");
        TestPlayer second = join("Second");
        duels.matches().start(first, second, swordKit(), readyArena("other"));
        Match countdown = duels.matches().matchOf(first).orElseThrow();
        tickUntil(() -> countdown.state() == Match.State.COUNTDOWN);

        assertTrue(first.simulateDamage(2, second).isCancelled());
    }

    @Test
    void commandsAreLimitedToDuelAndTheAllowedOnes() {
        assertTrue(command(alex, "/home"));
        assertTrue(command(alex, "/essentials:home"));
        assertFalse(command(alex, "/msg Steve gg"));
        assertFalse(command(alex, "/duel leave"));
        assertFalse(command(outsider, "/home"));
    }

    @Test
    void fightersCannotTeleportOutOrDropItems() {
        Location before = alex.getLocation();

        alex.teleport(world.getSpawnLocation());
        Item item = arenaWorld.dropItem(before, ItemStack.of(Material.DIAMOND_SWORD));
        PlayerDropItemEvent drop = new PlayerDropItemEvent(alex, item);
        server.getPluginManager().callEvent(drop);

        assertEquals(arenaWorld, alex.getWorld());
        assertTrue(drop.isCancelled());
    }

    @Test
    void fightersCannotChangeBlocks() {
        Block block = arenaWorld.getBlockAt(6, 63, 6);
        block.setType(Material.STONE);

        BlockBreakEvent event = alex.simulateBlockBreak(block);

        assertTrue(event == null || event.isCancelled());
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    void leavingTheArenaSendsAFighterBack() {
        PlayerMoveEvent move = alex.simulatePlayerMove(new Location(arenaWorld, 50, 64, 50));

        assertEquals(match.spawnOf(alex), move.getTo());
    }

    /** @return whether the command was blocked */
    private boolean command(TestPlayer player, String message) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, message);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }
}
