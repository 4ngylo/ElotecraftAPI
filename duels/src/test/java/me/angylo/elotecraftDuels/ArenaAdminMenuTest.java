package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.input.InputListener;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code /duels arena} menus. Clicks call the buttons directly: MockBukkit cannot route them through MenuListener. */
class ArenaAdminMenuTest extends DuelsTestBase {

    private static final int ENABLED = 1;
    private static final int SPAWN_1 = 2;
    private static final int GOAL_2 = 11;
    private static final int BUILD_LIMIT = 17;
    private static final int RESET = 20;
    private static final int DELETE = 22;

    private TestPlayer admin;

    @BeforeEach
    void setUpAdmin() {
        server.getPluginManager().registerEvents(new InputListener(), plugin);
        admin = join("Admin");
        admin.setOp(true);
        readyArena("pit");
    }

    private Inventory top() {
        return admin.getOpenInventory().getTopInventory();
    }

    private String title() {
        return Text.plain(admin.getOpenInventory().title());
    }

    private void click(int slot, ClickType type) {
        ((Menu) top().getHolder()).button(slot).orElseThrow().onClick().accept(admin, type);
        tick();
    }

    private List<String> lore(int slot) {
        return top().getItem(slot).getItemMeta().lore().stream().map(Text::plain).toList();
    }

    private Arena pit() {
        return duels.arenas().get("pit").orElseThrow();
    }

    @Test
    void listShowsArenasAndOpensTheirSettings() {
        server.dispatchCommand(admin, "duels arena");

        assertEquals("⚙ Arenas", title());
        assertEquals(Material.GRASS_BLOCK, top().getItem(0).getType());
        assertTrue(lore(0).contains("▪ Status: ready"));
        assertTrue(lore(0).contains("▪ Copies: 0"));

        click(0, ClickType.LEFT);

        assertEquals("⚙ pit", title());
    }

    @Test
    void consoleGetsTheHelp() {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();

        server.dispatchCommand(console, "duels arena");

        assertTrue(Text.plain(console.nextComponentMessage()).contains("Arena setup"));
    }

    @Test
    void pointsAreTakenWhereYouStand() {
        admin.teleport(new Location(arenaWorld, 7.5, 65, 9.5));
        server.dispatchCommand(admin, "duels arena pit");

        click(SPAWN_1, ClickType.LEFT);

        assertEquals(new Arena.Position(7.5, 65, 9.5, 0, 0), pit().spawn1());
        assertTrue(lore(SPAWN_1).contains("At: 7 65 9"));

        click(GOAL_2, ClickType.LEFT);

        assertEquals(new Arena.Position(7.5, 65, 9.5, 0, 0), pit().points().goal2());
        assertTrue(lore(GOAL_2).contains("At: 7 65 9"));
    }

    @Test
    void enabledSwitchesInPlace() {
        server.dispatchCommand(admin, "duels arena pit");

        click(ENABLED, ClickType.LEFT);

        assertFalse(pit().enabled());
        assertTrue(lore(ENABLED).contains("Used for duels: Off"));
    }

    @Test
    void buildLimitIsTypedInChatAndClearedWithRightClick() {
        server.dispatchCommand(admin, "duels arena pit");

        click(BUILD_LIMIT, ClickType.LEFT);
        admin.chat("70");
        tickUntil(() -> Integer.valueOf(70).equals(pit().buildLimit()));
        tick();
        assertEquals("⚙ pit", title());

        click(BUILD_LIMIT, ClickType.RIGHT);

        assertNull(pit().buildLimit());
    }

    @Test
    void deleteAndResetNeedShiftRightClick() {
        server.dispatchCommand(admin, "duels arena pit");
        messages(admin);

        click(RESET, ClickType.LEFT);
        click(DELETE, ClickType.LEFT);
        assertTrue(messages(admin).isEmpty());
        assertTrue(duels.arenas().get("pit").isPresent());

        click(DELETE, ClickType.SHIFT_RIGHT);

        assertTrue(duels.arenas().get("pit").isEmpty());
        assertEquals("⚙ Arenas", title());
    }
}
