package me.angylo.elotecraftAPI.menu;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Two rows: slots 0-8 are content, 9 is previous, 17 is next. Item amount = index + 1. */
class PaginatedMenuTest {

    private static final int PREVIOUS = 9;
    private static final int NEXT = 17;

    private PluginMock plugin;
    private PlayerMock player;
    private final List<Integer> clicked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private List<Button> buttons(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> Button.of(new ItemStack(Material.STONE, index + 1), (clicker, click) -> clicked.add(index)))
                .toList();
    }

    private PaginatedMenu openWith(int count) {
        PaginatedMenu menu = new PaginatedMenu(plugin, 2, "Pages").items(buttons(count));
        menu.open(player);
        return menu;
    }

    private void click(PaginatedMenu menu, int slot) {
        menu.handleClick(player.simulateInventoryClick(slot));
    }

    @Test
    void firstPageShowsFirstItemsAndOnlyNextArrow() {
        PaginatedMenu menu = openWith(20);
        Inventory inventory = menu.getInventory();

        assertEquals(3, menu.pages());
        assertEquals(1, inventory.getItem(0).getAmount());
        assertEquals(9, inventory.getItem(8).getAmount());
        assertNull(inventory.getItem(PREVIOUS));
        assertEquals(Material.ARROW, inventory.getItem(NEXT).getType());
    }

    @Test
    void arrowsTurnPagesAndItemsStayClickable() {
        PaginatedMenu menu = openWith(20);

        click(menu, NEXT);
        click(menu, 0);

        assertEquals(1, menu.page());
        assertEquals(10, menu.getInventory().getItem(0).getAmount());
        assertEquals(Material.ARROW, menu.getInventory().getItem(PREVIOUS).getType());
        assertEquals(List.of(9), clicked);

        click(menu, PREVIOUS);
        assertEquals(0, menu.page());
    }

    @Test
    void lastPageClearsLeftoverSlotsAndHidesNext() {
        PaginatedMenu menu = openWith(20);

        click(menu, NEXT);
        click(menu, NEXT);

        Inventory inventory = menu.getInventory();
        assertEquals(19, inventory.getItem(0).getAmount());
        assertEquals(20, inventory.getItem(1).getAmount());
        assertNull(inventory.getItem(2));
        assertNull(inventory.getItem(NEXT));
    }

    @Test
    void fillCoversEmptySlotsButKeepsFixedButtons() {
        PaginatedMenu menu = new PaginatedMenu(plugin, 2, "Pages")
                .items(buttons(3))
                .set(13, new ItemStack(Material.BARRIER))
                .fill(new ItemStack(Material.GLASS_PANE));
        menu.open(player);

        Inventory inventory = menu.getInventory();
        assertEquals(Material.GLASS_PANE, inventory.getItem(5).getType());
        assertEquals(Material.GLASS_PANE, inventory.getItem(PREVIOUS).getType());
        assertEquals(Material.GLASS_PANE, inventory.getItem(NEXT).getType());
        assertEquals(Material.BARRIER, inventory.getItem(13).getType());
    }

    @Test
    void emptyListIsOnePageWithoutArrows() {
        PaginatedMenu menu = openWith(0);

        assertEquals(1, menu.pages());
        assertNull(menu.getInventory().getItem(PREVIOUS));
        assertNull(menu.getInventory().getItem(NEXT));
    }

    @Test
    void customArrowsAreUsed() {
        PaginatedMenu menu = new PaginatedMenu(plugin, 2, "Pages")
                .nextButton(new ItemStack(Material.LIME_DYE))
                .items(buttons(20));
        menu.open(player);

        assertEquals(Material.LIME_DYE, menu.getInventory().getItem(NEXT).getType());
    }

    @Test
    void centeredUsesTheInnerColumnsBelowAnEmptyTopRow() {
        PaginatedMenu menu = new PaginatedMenu(plugin, 4, "Pages").centered().items(buttons(20));
        menu.open(player);

        Inventory inventory = menu.getInventory();
        assertEquals(2, menu.pages());
        assertNull(inventory.getItem(0));
        assertNull(inventory.getItem(9));
        assertEquals(1, inventory.getItem(10).getAmount());
        assertEquals(7, inventory.getItem(16).getAmount());
        assertNull(inventory.getItem(17));
        assertEquals(14, inventory.getItem(25).getAmount());
        assertEquals(Material.ARROW, inventory.getItem(35).getType());

        menu.handleClick(player.simulateInventoryClick(35));
        menu.handleClick(player.simulateInventoryClick(11));
        assertEquals(List.of(15), clicked);
    }

    @Test
    void centeredCentersARowThatIsNotFull() {
        assertEquals(List.of(4), PaginatedMenu.centeredColumns(1));
        assertEquals(List.of(3, 5), PaginatedMenu.centeredColumns(2));
        assertEquals(List.of(3, 4, 5), PaginatedMenu.centeredColumns(3));
        assertEquals(List.of(2, 3, 5, 6), PaginatedMenu.centeredColumns(4));
        assertEquals(List.of(1, 2, 3, 5, 6, 7), PaginatedMenu.centeredColumns(6));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), PaginatedMenu.centeredColumns(7));

        PaginatedMenu menu = new PaginatedMenu(plugin, 4, "Pages").centered().items(buttons(9));
        menu.open(player);
        assertEquals(8, menu.getInventory().getItem(21).getAmount());
        assertEquals(9, menu.getInventory().getItem(23).getAmount());
        assertNull(menu.getInventory().getItem(22));
    }

    @Test
    void centeredKeepsButtonsInTheTopRow() {
        PaginatedMenu menu = new PaginatedMenu(plugin, 4, "Pages").centered()
                .set(4, new ItemStack(Material.NETHER_STAR))
                .items(buttons(20));
        menu.open(player);

        menu.handleClick(player.simulateInventoryClick(35));
        assertEquals(Material.NETHER_STAR, menu.getInventory().getItem(4).getType());
    }

    @Test
    void centeredWithTwoRowsUsesTheTopRow() {
        PaginatedMenu menu = new PaginatedMenu(plugin, 2, "Pages").centered().items(buttons(1));
        menu.open(player);

        assertEquals(1, menu.getInventory().getItem(4).getAmount());
    }

    @Test
    void needsANavigationRow() {
        assertThrows(IllegalArgumentException.class, () -> new PaginatedMenu(plugin, 1, "Pages"));
    }
}
