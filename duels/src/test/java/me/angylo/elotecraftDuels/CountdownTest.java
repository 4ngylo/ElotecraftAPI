package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** During the countdown fighters stand still but may arrange their inventory and draw a bow or load a crossbow. */
class CountdownTest extends DuelsTestBase {

    private TestPlayer alex;
    private Match match;

    @BeforeEach
    void startCountdown() {
        alex = join("Alex");
        Kit kit = new Kit("archer", "<green>Archer", Material.BOW, null, List.of(ItemStack.of(Material.BOW),
                ItemStack.of(Material.CROSSBOW), ItemStack.of(Material.ENDER_PEARL, 4), ItemStack.of(Material.ARROW, 16)), Set.of());
        await(duels.kits().update(kit));
        assertTrue(duels.matches().start(alex, join("Steve"), kit, readyArena("pit")));
        match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
    }

    private <T extends Event & Cancellable> boolean cancelled(T event) {
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private PlayerInteractEvent rightClick(Material item) {
        return new PlayerInteractEvent(alex, Action.RIGHT_CLICK_AIR, ItemStack.of(item), null, null, EquipmentSlot.HAND);
    }

    private EntityShootBowEvent shot() {
        Arrow arrow = alex.getWorld().spawn(alex.getLocation(), Arrow.class);
        return new EntityShootBowEvent(alex, ItemStack.of(Material.BOW), ItemStack.of(Material.ARROW), arrow, EquipmentSlot.HAND, 1, true);
    }

    /** A click in the air counts as cancelled (no block to use), so the item use is what matters. */
    private boolean usesItem(PlayerInteractEvent event) {
        server.getPluginManager().callEvent(event);
        return event.useItemInHand() != Event.Result.DENY;
    }

    @Test
    void bowsAndCrossbowsCanBeDrawnButNothingElseUsed() {
        assertTrue(usesItem(rightClick(Material.BOW)));
        assertTrue(usesItem(rightClick(Material.CROSSBOW)));
        assertFalse(usesItem(rightClick(Material.ENDER_PEARL)));
        assertFalse(usesItem(new PlayerInteractEvent(alex, Action.LEFT_CLICK_AIR, ItemStack.of(Material.BOW), null, null, EquipmentSlot.HAND)));
    }

    @Test
    void anArrowReleasedBeforeTheFightIsRefusedAndOneAfterFlies() {
        assertTrue(cancelled(shot()));

        tickUntil(() -> match.state() == Match.State.FIGHTING);

        assertFalse(cancelled(shot()));
    }

    /** MockBukkit cannot convert view slots (SimpleInventoryViewMock.convertSlot), so this is reported as skipped. */
    @Test
    void theInventoryCanBeArranged() {
        InventoryClickEvent click = new InventoryClickEvent(alex.getOpenInventory(), InventoryType.SlotType.QUICKBAR, 36,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);

        assertFalse(cancelled(click));
    }
}
