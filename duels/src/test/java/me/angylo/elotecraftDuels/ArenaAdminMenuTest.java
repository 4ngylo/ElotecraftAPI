package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.input.InputListener;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code /duels arena} menus. Clicks call the buttons directly: MockBukkit cannot route them through MenuListener. */
class ArenaAdminMenuTest extends DuelsTestBase {

    /** The only arena in a centered list sits in the middle of the first row. */
    private static final int FIRST_ENTRY = 13;

    private TestPlayer admin;

    @BeforeEach
    void setUpAdmin() {
        server.getPluginManager().registerEvents(new InputListener(), plugin);
        admin = join("Admin");
        admin.setOp(true);
        readyArena("pit");
    }

    private Arena pit() {
        return duels.arenas().get("pit").orElseThrow();
    }

    private void click(String name, ClickType type) {
        clickNamed(admin, name, type);
    }

    private boolean loreHas(String button, String line) {
        return loreAt(admin, slotNamed(admin, button)).contains(line);
    }

    @Test
    void listShowsArenasAndOpensTheirSettings() {
        server.dispatchCommand(admin, "duels arena");

        assertEquals("Admin › Arenas", menuTitle(admin));
        assertEquals(Material.GRASS_BLOCK, admin.getOpenInventory().getTopInventory().getItem(FIRST_ENTRY).getType());
        assertTrue(loreAt(admin, FIRST_ENTRY).contains("▪ Status: ready"));
        assertTrue(loreAt(admin, FIRST_ENTRY).contains("▪ Copies: 0"));

        clickSlot(admin, FIRST_ENTRY, ClickType.LEFT);

        assertEquals("Arenas › pit", menuTitle(admin));
    }

    @Test
    void consoleGetsTheHelp() {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();

        server.dispatchCommand(console, "duels arena");

        assertTrue(Text.plain(console.nextComponentMessage()).contains("Arena setup"));
    }

    @Test
    void pointsAreTakenWhereYouStandInTheirSubmenus() {
        admin.teleport(new Location(arenaWorld, 7.5, 65, 9.5));
        server.dispatchCommand(admin, "duels arena pit");

        click("Points", ClickType.LEFT);
        assertEquals("pit › Points", menuTitle(admin));
        click("Spawn 1", ClickType.LEFT);

        assertEquals(new Arena.Position(7.5, 65, 9.5, 0, 0), pit().spawn1());
        assertTrue(loreHas("Spawn 1", "At: 7 65 9"));

        click("Back", ClickType.LEFT);
        click("Modes", ClickType.LEFT);
        click("Bridge goal 2", ClickType.LEFT);

        assertEquals(new Arena.Position(7.5, 65, 9.5, 0, 0), pit().points().goal2());
        assertTrue(loreHas("Bridge goal 2", "At: 7 65 9"));
    }

    @Test
    void enabledSwitchesInPlace() {
        server.dispatchCommand(admin, "duels arena pit");

        click("Enabled", ClickType.LEFT);

        assertFalse(pit().enabled());
        assertTrue(loreHas("Enabled", "Used for duels: Off"));
    }

    @Test
    void buildLimitIsTypedInChatAndClearedWithRightClick() {
        server.dispatchCommand(admin, "duels arena pit");

        click("Build limit", ClickType.LEFT);
        admin.chat("70");
        tickUntil(() -> Integer.valueOf(70).equals(pit().buildLimit()));
        tick();
        assertEquals("Arenas › pit", menuTitle(admin));

        click("Build limit", ClickType.RIGHT);

        assertNull(pit().buildLimit());
    }

    @Test
    void deleteAsksToConfirm() {
        server.dispatchCommand(admin, "duels arena pit");

        click("Delete pit", ClickType.LEFT);
        assertEquals("Are you sure?", menuTitle(admin));
        click("Cancel", ClickType.LEFT);

        assertTrue(duels.arenas().get("pit").isPresent());
        assertEquals("Arenas › pit", menuTitle(admin));

        click("Delete pit", ClickType.LEFT);
        click("Confirm", ClickType.LEFT);

        assertTrue(duels.arenas().get("pit").isEmpty());
        assertEquals("Admin › Arenas", menuTitle(admin));
    }

    @Test
    void resetAsksToConfirmAndComesBack() {
        server.dispatchCommand(admin, "duels arena pit");
        click("Snapshot and copies", ClickType.LEFT);
        messages(admin);

        click("Reset the blocks", ClickType.LEFT);

        assertTrue(messages(admin).isEmpty());
        assertEquals("Are you sure?", menuTitle(admin));

        click("Cancel", ClickType.LEFT);

        assertEquals("pit › Snapshot and copies", menuTitle(admin));
    }
}
