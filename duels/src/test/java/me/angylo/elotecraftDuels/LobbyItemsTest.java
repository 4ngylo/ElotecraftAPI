package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hotbar items in the lobby: given by state, clickable, never moved, never replacing a player's own items. */
class LobbyItemsTest extends DuelsTestBase {

    private static final String UNRANKED = "Unranked queue (right-click)";
    private static final int SYNC_TICKS = 20;

    private TestPlayer alex;

    @BeforeEach
    void setUp() {
        setConfig("lobby-items.enabled", true);
        swordKit();
        readyArena("pit");
        alex = join("Alex");
        ticks(SYNC_TICKS);
    }

    private String name(int slot) {
        ItemStack item = alex.getInventory().getItem(slot);
        return item == null || item.isEmpty() ? "" : Text.plain(item.getItemMeta().displayName());
    }

    /** How many items named {@code name} the player carries. */
    private long count(TestPlayer player, String name) {
        return Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull).filter(ItemStack::hasItemMeta)
                .filter(item -> item.getItemMeta().hasDisplayName() && Text.plain(item.getItemMeta().displayName()).equals(name)).count();
    }

    private PlayerInteractEvent rightClick(int slot) {
        alex.getInventory().setHeldItemSlot(slot);
        PlayerInteractEvent event = new PlayerInteractEvent(alex, Action.RIGHT_CLICK_AIR, alex.getInventory().getItem(slot),
                null, BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void aDisabledItemIsNotGiven() {
        File file = new File(plugin.getDataFolder(), "menus.yml");
        YamlConfiguration menus = YamlConfiguration.loadConfiguration(file);
        menus.set("lobby-items.history.enabled", false);
        try {
            menus.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        duels.reload();
        ticks(SYNC_TICKS);

        assertEquals("", name(8));
    }

    @Test
    void lobbyPlayersGetTheItemsInTheirSlots() {
        assertEquals(List.of(UNRANKED, "Ranked queue (right-click)", "Party (right-click)", "Cosmetics (right-click)", "Events (right-click)", "Free-for-all (right-click)", "2v2 queue (right-click)",
                "Edit kits (right-click)", "Match history (right-click)"), List.of(name(0), name(1), name(2), name(3),
                name(4), name(5), name(6), name(7), name(8)));
    }

    @Test
    void queuedPlayersGetTheLeaveItemInstead() {
        server.dispatchCommand(alex, "duel queue sword");
        ticks(SYNC_TICKS);
        assertEquals("Leave the queue (right-click)", name(0));
        assertEquals("", name(1));

        rightClick(0);
        ticks(2);

        assertTrue(duels.queues().queued(alex.getUniqueId()).isEmpty());
        assertEquals(UNRANKED, name(0));
    }

    @Test
    void rightClickRunsTheItemsCommand() {
        assertTrue(rightClick(0).isCancelled());

        assertEquals("Play › Unranked", Text.plain(alex.getOpenInventory().title()));
    }

    @Test
    void aDuelLeavesExactlyOneSetBehind() {
        TestPlayer steve = join("Steve");
        ticks(SYNC_TICKS);
        assertTrue(duels.matches().start(alex, steve, duels.kits().get("sword").orElseThrow(), duels.arenas().get("pit").orElseThrow()));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        ticks(SYNC_TICKS);
        assertEquals(0, count(alex, UNRANKED));

        steve.simulateDamage(100, alex);
        tickUntil(() -> !duels.matches().isBusy(alex));
        ticks(SYNC_TICKS);

        assertEquals(1, count(alex, UNRANKED));
        assertEquals(1, count(steve, UNRANKED));
    }

    @Test
    void ownItemsAreNeverReplacedAndTurningItOffTakesTheItemsAway() {
        setConfig("lobby-items.enabled", false);
        ticks(SYNC_TICKS);
        assertEquals("", name(0));
        alex.getInventory().setItem(4, ItemStack.of(Material.DIRT));

        setConfig("lobby-items.enabled", true);
        ticks(SYNC_TICKS);

        assertEquals(Material.DIRT, alex.getInventory().getItem(4).getType());
        assertEquals(UNRANKED, name(0));
    }

    @Test
    void otherWorldsHaveNoItems() {
        setConfig("lobby-items.worlds", List.of("world"));
        alex.teleport(arenaWorld.getSpawnLocation());
        ticks(SYNC_TICKS);

        assertEquals(0, count(alex, UNRANKED));
    }

    @Test
    void itemsNeedTheirPermission() {
        alex.addAttachment(plugin, "duels.queue.ranked", false);
        ticks(SYNC_TICKS);

        assertEquals("", name(1));
    }

    @Test
    void lobbyItemsCannotBeDroppedOrSwapped() {
        Item dropped = world.dropItem(alex.getLocation(), alex.getInventory().getItem(0).clone());
        PlayerDropItemEvent drop = new PlayerDropItemEvent(alex, dropped);
        server.getPluginManager().callEvent(drop);
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(alex, ItemStack.empty(), alex.getInventory().getItem(0));
        server.getPluginManager().callEvent(swap);
        PlayerDropItemEvent ownDrop = new PlayerDropItemEvent(alex, world.dropItem(alex.getLocation(), ItemStack.of(Material.DIRT)));
        server.getPluginManager().callEvent(ownDrop);

        assertTrue(drop.isCancelled());
        assertTrue(swap.isCancelled());
        assertTrue(!ownDrop.isCancelled());
    }
}
