package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custom kits: built in the kit editor from the base kit's items, used to challenge. */
class CustomKitTest extends DuelsTestBase {

    /** Slots of the base kit's items in the default menus.yml {@code custom-kit-items}: sword, bow, arrows. */
    private static final int SWORD = 0;
    private static final int ARROWS = 2;

    private TestPlayer alex;
    private TestPlayer bob;

    @BeforeEach
    void setUpKits() {
        alex = join("Alex");
        bob = join("Bob");
        readyArena("pit");
        await(duels.kits().update(pool(List.of(ItemStack.of(Material.DIAMOND_SWORD), ItemStack.of(Material.BOW),
                ItemStack.of(Material.ARROW, 16)))));
        setConfig("custom-kits.base-kit", "pool");
    }

    private static Kit pool(List<ItemStack> items) {
        return new Kit("pool", "<gold>Pool", Material.CHEST, null, items, Set.of());
    }

    private String title(TestPlayer player) {
        return Text.plain(player.getOpenInventory().title());
    }

    private void click(TestPlayer player, int slot) {
        ((Menu) player.getOpenInventory().getTopInventory().getHolder()).button(slot).orElseThrow()
                .onClick().accept(player, ClickType.LEFT);
        tick();
    }

    private long count(TestPlayer player, Material material) {
        return Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull)
                .filter(item -> item.getType() == material).count();
    }

    /** Alex builds custom kit 1: a sword and arrows. */
    private void buildSwordAndArrows() {
        alex.performCommand("duel customkit 1");
        tickUntil(() -> duels.editor().buildingFrom(alex).isPresent());
        tick();
        assertEquals("Pick items", title(alex));
        click(alex, SWORD);
        click(alex, ARROWS);
        assertSays(alex, "duel editkit save", "Saved custom kit 1");
        tickUntil(() -> !duels.editor().isEditing(alex));
    }

    @Test
    void aBuiltKitIsUsedByBothFighters() {
        buildSwordAndArrows();

        assertSays(alex, "duel Bob custom", "Bob");
        bob.performCommand("duel accept Alex");
        Match match = duels.matches().matchOf(bob).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN || match.state() == Match.State.FIGHTING);

        assertTrue(match.kit().isCustom());
        assertEquals("Alex's custom kit 1", Text.plain(Text.mm(match.kit().displayName())));
        assertEquals(1, count(bob, Material.DIAMOND_SWORD));
        assertEquals(1, count(bob, Material.ARROW));
        assertEquals(0, count(bob, Material.BOW));
    }

    @Test
    void itemsNotFromTheBaseKitCannotBeSaved() {
        alex.performCommand("duel customkit 2");
        tickUntil(() -> duels.editor().buildingFrom(alex).isPresent());
        alex.closeInventory();
        alex.getInventory().setItem(5, ItemStack.of(Material.DIRT));

        assertSays(alex, "duel editkit save", "Only items from Pool");
        assertTrue(duels.editor().isEditing(alex));
    }

    @Test
    void clickingAnItemInYourInventoryUnderTheItemsMenuTakesItOut() {
        alex.performCommand("duel customkit 1");
        tickUntil(() -> duels.editor().buildingFrom(alex).isPresent());
        tick();
        click(alex, SWORD);
        InventoryView view = alex.getOpenInventory();
        int sword = IntStream.range(view.getTopInventory().getSize(), view.countSlots())
                .filter(raw -> view.getItem(raw) != null && view.getItem(raw).getType() == Material.DIAMOND_SWORD).findFirst().orElseThrow();

        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, sword,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);

        assertEquals(0, count(alex, Material.DIAMOND_SWORD));
    }

    @Test
    void customKitsAreNotOfferedOnceTheBaseKitDropsAnItemOrForBets() {
        buildSwordAndArrows();
        assertSays(alex, "duel Bob custom bet 100", "Bets are not allowed on custom kits");

        await(duels.kits().update(pool(List.of(ItemStack.of(Material.DIAMOND_SWORD), ItemStack.of(Material.BOW)))));

        assertTrue(duels.customKits().of(alex).isEmpty());
        assertSays(alex, "duel Bob custom", "There is no kit called 'custom'");
    }

    @Test
    void withoutABaseKitCustomKitsAreOff() {
        setConfig("custom-kits.base-kit", "");

        assertSays(alex, "duel customkit", "Custom kits are off");
        assertSays(alex, "duel customkit 1", "Custom kits are off");
    }

    @Test
    void adminKitsCannotBeCalledCustom() {
        assertThrows(IllegalArgumentException.class, () -> duels.kits().create(Kit.CUSTOM, Material.STONE, alex.getInventory()));
        assertFalse(duels.kits().get(Kit.CUSTOM).isPresent());
    }

    @Test
    void aPermissionGivesMoreCustomKitSlotsUpToNine() {
        assertSays(alex, "duel customkit 4", "Pick a custom kit from 1 to 3.");

        alex.addAttachment(plugin, "duels.kit.custom.slots.5", true);
        assertEquals(5, duels.customKits().slots(alex));
        assertSays(alex, "duel customkit 6", "Pick a custom kit from 1 to 5.");

        alex.addAttachment(plugin, "duels.kit.custom.slots.99", true);
        assertEquals(9, duels.customKits().slots(alex));
    }
}
