package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import org.bukkit.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /party} argument handling: help, unknown players, kits and arenas, and tab completion. */
class PartyCommandTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Command party;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        readyArena("pit");
        swordKit();
        party = server.getCommandMap().getCommand("party");
    }

    @Test
    void helpAndUnknownPlayers() {
        assertSays(alex, "party help", "Parties");
        assertSays(alex, "party Nobody", "'Nobody' is not online.");
        assertSays(alex, "party invite Nobody", "'Nobody' is not online.");
        assertSays(alex, "party duel Nobody", "'Nobody' is not online.");
        assertTrue(duels.parties().partyOf(alex.getUniqueId()).isEmpty());
    }

    @Test
    void theConsoleCannotInvite() {
        server.dispatchCommand(server.getConsoleSender(), "party Steve");

        assertTrue(duels.parties().partyOf(steve.getUniqueId()).isEmpty());
    }

    @Test
    void fightsCheckTheKitAndArena() {
        assertSays(alex, "party split nokit", "There is no kit called 'nokit'.");
        assertSays(alex, "party ffa sword nowhere", "There is no arena called 'nowhere'.");

        alex.addAttachment(plugin, "duels.select-arena", false);
        assertSays(alex, "party split sword pit", "You don't have permission to do that.");
    }

    @Test
    void aFightWithoutAKitOpensTheKitMenu() {
        server.dispatchCommand(alex, "party ffa");

        assertInstanceOf(Menu.class, alex.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void tabCompletesMembersKitsAndArenas() {
        assertSays(alex, "party Steve", "Invited Steve");
        assertSays(steve, "party accept", "joined the party");

        assertEquals(List.of("Steve"), party.tabComplete(alex, "party", new String[]{"kick", ""}));
        assertEquals(List.of(), party.tabComplete(server.getConsoleSender(), "party", new String[]{"kick", ""}));
        assertEquals(List.of("sword"), party.tabComplete(alex, "party", new String[]{"split", ""}));
        assertEquals(List.of("pit"), party.tabComplete(alex, "party", new String[]{"ffa", "sword", ""}));
        assertEquals(List.of("pit"), party.tabComplete(alex, "party", new String[]{"duel", "Steve", "sword", ""}));
        assertEquals(List.of(), party.tabComplete(alex, "party", new String[]{"ffa", "sword", "pit", ""}));

        alex.addAttachment(plugin, "duels.select-arena", false);
        assertEquals(List.of(), party.tabComplete(alex, "party", new String[]{"ffa", "sword", ""}));
    }
}
