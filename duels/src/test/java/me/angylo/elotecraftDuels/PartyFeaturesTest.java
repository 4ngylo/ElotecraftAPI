package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftDuels.party.Party;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public parties, party chat and the party menus. */
class PartyFeaturesTest extends DuelsTestBase {

    /** Bottom-row slots of the default menus.yml {@code party} (4 rows): public is slot 2. */
    private static final int PUBLIC_BUTTON = 27 + 2;

    private TestPlayer alex;
    private TestPlayer steve;
    private TestPlayer cid;

    @BeforeEach
    void players() {
        alex = join("Alex");
        steve = join("Steve");
        cid = join("Cid");
    }

    private Party party(TestPlayer member) {
        return duels.parties().partyOf(member.getUniqueId()).orElseThrow();
    }

    private void click(TestPlayer player, int slot, ClickType type) {
        ((Menu) player.getOpenInventory().getTopInventory().getHolder()).button(slot).orElseThrow()
                .onClick().accept(player, type);
        tick();
    }

    private static boolean said(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void publicPartiesTakeAnyoneAndPrivateOnesRefuse() {
        alex.performCommand("party create");
        assertSays(steve, "party join Alex", "Alex's party is private");

        messages(steve);
        alex.performCommand("party public");
        assertTrue(said(messages(steve), "Alex opened their party to everyone. [JOIN]"));
        assertSays(steve, "party join Alex", "Steve joined the party");

        assertEquals(party(alex), party(steve));
        assertSays(cid, "party join Nobody", "Nobody doesn't lead a party.");
    }

    @Test
    void goingPublicIsAnnouncedOnceAMinute() {
        alex.performCommand("party create");
        alex.performCommand("party public");
        alex.performCommand("party public");
        messages(steve);

        alex.performCommand("party public");

        assertTrue(party(alex).isOpen());
        assertTrue(messages(steve).stream().noneMatch(line -> line.contains("opened their party")));
    }

    @Test
    void partyChatReachesMembersOnlyAsTyped() {
        alex.performCommand("party invite Steve");
        steve.performCommand("party accept");
        messages(alex);
        messages(cid);

        steve.performCommand("party chat <red>hello");
        alex.performCommand("pc hi there");

        List<String> lines = messages(alex);
        assertTrue(said(lines, "[Party] Steve: <red>hello"));
        assertTrue(said(lines, "[Party] Alex: hi there"));
        assertTrue(messages(cid).isEmpty());
        assertSays(cid, "pc hi", "You're not in a party.");
        assertSays(alex, "pc", "Use /party chat");
    }

    @Test
    void thePartyMenuPromotesMembersAndRunsItsButtons() {
        alex.performCommand("party invite Steve");
        steve.performCommand("party accept");

        alex.performCommand("party");
        tick();
        click(alex, PUBLIC_BUTTON, ClickType.LEFT);
        assertTrue(party(alex).isOpen());

        alex.performCommand("party");
        tick();
        // Members in the order they joined: Alex, then Steve.
        click(alex, 1, ClickType.LEFT);
        assertTrue(party(alex).isLeader(steve.getUniqueId()));
    }

    @Test
    void withoutAPartyTheMenuListsPublicPartiesToJoin() {
        alex.performCommand("party create");
        alex.performCommand("party public");

        steve.performCommand("party");
        tick();
        click(steve, 0, ClickType.LEFT);

        assertEquals(party(alex), party(steve));
    }
}
