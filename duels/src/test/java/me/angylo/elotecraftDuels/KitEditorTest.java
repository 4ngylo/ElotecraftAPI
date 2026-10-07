package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duel editkit}: players' own layouts of kits. */
class KitEditorTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void setUp() {
        alex = join("Alex");
        steve = join("Steve");
        alex.getInventory().addItem(ItemStack.of(Material.DIRT, 5));
        kit = new Kit("pvp", "<aqua>PvP", Material.DIAMOND_SWORD, null,
                List.of(ItemStack.of(Material.DIAMOND_SWORD), ItemStack.of(Material.GOLDEN_APPLE, 8)), false, Set.of(), true);
        await(duels.kits().update(kit));
        arena = readyArena("pit");
    }

    /** Opens the editor and swaps the sword and the apples. */
    private void editAndSwap() {
        assertSays(alex, "duel editkit pvp", "Arrange PvP in your inventory");
        assertEquals(Material.DIAMOND_SWORD, alex.getInventory().getItem(0).getType());
        alex.getInventory().setItem(0, ItemStack.of(Material.GOLDEN_APPLE, 8));
        alex.getInventory().setItem(1, ItemStack.of(Material.DIAMOND_SWORD));
    }

    /** The item in slot 0 once a duel with the kit counts down. */
    private Material firstSlotInDuel() {
        assertTrue(duels.matches().start(alex, steve, duels.kits().get("pvp").orElseThrow(), arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        return alex.getInventory().getItem(0).getType();
    }

    @Test
    void aSavedLayoutIsUsedInDuelsAndTheInventoryComesBack() {
        editAndSwap();
        assertEquals(GameMode.ADVENTURE, alex.getGameMode());
        assertTrue(duels.matches().isBusy(alex));

        assertSays(alex, "duel editkit save", "Saved your layout of PvP");

        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
        assertFalse(alex.getInventory().contains(Material.DIAMOND_SWORD));
        assertEquals(GameMode.SURVIVAL, alex.getGameMode());
        assertFalse(duels.matches().isBusy(alex));
        assertEquals(Material.GOLDEN_APPLE, firstSlotInDuel());
    }

    @Test
    void aLayoutWithOtherItemsIsRefused() {
        editAndSwap();
        alex.getInventory().setItem(1, ItemStack.empty());

        assertSays(alex, "duel editkit save", "must hold exactly the items of PvP");
        assertTrue(duels.editor().isEditing(alex));

        assertSays(alex, "duel editkit cancel", "nothing was saved");
        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
        assertEquals(Material.DIAMOND_SWORD, firstSlotInDuel());
    }

    @Test
    void editingPlayersCannotQueueAndQuittingGivesTheirItemsBack() {
        editAndSwap();
        assertSays(alex, "duel queue pvp", "You're already in a duel");
        assertSays(alex, "duel editkit pvp", "already editing");

        alex.disconnect();
        tick();

        assertFalse(duels.editor().isEditing(alex));
        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
    }

    @Test
    void aLayoutIsDroppedWhenTheKitChangesAndCanBeReset() {
        editAndSwap();
        assertSays(alex, "duel editkit save", "Saved your layout");
        await(duels.kits().update(kit.withIcon(Material.STONE_SWORD)));
        assertEquals(Material.GOLDEN_APPLE, firstSlotInDuel());
        duels.matches().stop(alex);
        tickUntil(() -> !duels.matches().isBusy(alex));

        await(duels.kits().update(new Kit("pvp", "<aqua>PvP", Material.DIAMOND_SWORD, null,
                List.of(ItemStack.of(Material.DIAMOND_SWORD), ItemStack.of(Material.GOLDEN_APPLE, 16)), false, Set.of(), true)));
        assertEquals(Material.DIAMOND_SWORD, firstSlotInDuel());
        duels.matches().stop(alex);
        tickUntil(() -> !duels.matches().isBusy(alex));

        assertSays(alex, "duel editkit reset pvp", "Your layout of PvP is gone");
    }

    @Test
    void theEditorTimesOutAndBlocksItemUse() {
        setConfig("kit-editor.timeout", "30s");
        editAndSwap();
        PlayerInteractEvent drink = new PlayerInteractEvent(alex, Action.RIGHT_CLICK_AIR, alex.getInventory().getItem(0), null, null);
        server.getPluginManager().callEvent(drink);
        assertTrue(drink.useItemInHand() == Event.Result.DENY);

        messages(alex);
        ticks(20 * 31);

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("The kit editor timed out")));
        assertFalse(duels.editor().isEditing(alex));
        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
        assertSays(alex, "duel editkit save", "You're not editing a kit.");
    }

    @Test
    void duelLeaveStopsEditing() {
        editAndSwap();

        assertSays(alex, "duel leave", "Stopped editing; nothing was saved.");

        assertFalse(duels.editor().isEditing(alex));
        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
    }

    @Test
    void shuttingDownGivesEditorsTheirItemsBack() {
        editAndSwap();

        duels.shutdown();

        assertTrue(alex.getInventory().contains(Material.DIRT, 5));
        assertEquals(GameMode.SURVIVAL, alex.getGameMode());
        duels = Duels.start(plugin);
        await(duels.ready());
    }

    @Test
    void layoutsSurviveARestart() {
        editAndSwap();
        assertSays(alex, "duel editkit save", "Saved your layout");
        server.getScheduler().waitAsyncTasksFinished();

        duels.shutdown();
        duels = Duels.start(plugin);
        await(duels.ready());
        tickUntil(() -> {
            server.getScheduler().waitAsyncTasksFinished();
            return true;
        });
        ticks(5);

        assertEquals(Material.GOLDEN_APPLE, firstSlotInDuel());
    }
}
