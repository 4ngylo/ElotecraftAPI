package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.party.Party;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /party}: invites, members and leaders. */
class PartyTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private TestPlayer sam;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        sam = join("Sam");
    }

    private Party partyOf(TestPlayer player) {
        return duels.parties().partyOf(player.getUniqueId()).orElseThrow();
    }

    private boolean inParty(TestPlayer player) {
        return duels.parties().partyOf(player.getUniqueId()).isPresent();
    }

    /** Alex leads a party with the given members. */
    private void partyWith(TestPlayer... members) {
        for (TestPlayer member : members) {
            assertSays(alex, "party " + member.getName(), "Invited " + member.getName());
            assertSays(member, "party accept", "joined the party");
        }
    }

    @Test
    void inviteAcceptKickAndLeave() {
        assertSays(alex, "party Steve", "Invited Steve to your party.");
        assertTrue(messages(steve).stream().anyMatch(line -> line.contains("Alex invited you to their party. [ACCEPT] [DENY]")));
        assertSays(steve, "party accept alex", "Steve joined the party.");
        assertEquals(List.of(alex.getUniqueId(), steve.getUniqueId()), partyOf(alex).members());
        assertTrue(partyOf(steve).isLeader(alex.getUniqueId()));

        assertSays(alex, "party kick Steve", "Steve was kicked from the party.");
        assertTrue(messages(steve).stream().anyMatch(line -> line.contains("You were kicked from the party.")));
        assertTrue(!inParty(steve));

        assertSays(alex, "party leave", "You left the party.");
        assertTrue(!inParty(alex));
        assertSays(alex, "party leave", "You're not in a party.");
    }

    @Test
    void theNextMemberLeadsWhenTheLeaderQuits() {
        partyWith(steve, sam);
        messages(sam);

        alex.disconnect();
        tick();

        assertTrue(partyOf(sam).isLeader(steve.getUniqueId()));
        assertEquals(2, partyOf(steve).size());
        List<String> lines = messages(sam);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Alex left the party.")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Steve leads the party now.")));
    }

    @Test
    void invitesExpireAndAreDenied() {
        setConfig("parties.invite-expiry", "5s");
        assertSays(alex, "party Steve", "Invited Steve");
        ticks(20 * 6);
        assertSays(steve, "party accept", "You have no party invite.");

        assertSays(alex, "party Sam", "Invited Sam");
        assertSays(sam, "party deny", "You denied the party invite.");
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Sam denied your party invite.")));
        assertSays(sam, "party accept", "You have no party invite.");
    }

    @Test
    void onlyTheLeaderInvitesAndPlayersJoinOnePartyAtATime() {
        setConfig("parties.max-size", 2);
        partyWith(steve);

        assertSays(steve, "party Sam", "Only the party leader can do that.");
        assertSays(alex, "party Sam", "The party is full.");
        assertSays(alex, "party Alex", "You can't invite yourself.");
        assertSays(sam, "party create", "Created a party.");
        assertSays(sam, "party create", "You're already in a party.");
        assertSays(sam, "party Alex", "Alex is already in a party.");
    }

    @Test
    void promoteAndDisband() {
        partyWith(steve);

        assertSays(alex, "party promote Steve", "Steve leads the party now.");
        assertSays(alex, "party disband", "Only the party leader can do that.");
        assertSays(steve, "party info", "Alex");

        assertSays(steve, "party disband", "Steve disbanded the party.");
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Steve disbanded the party.")));
        assertTrue(!inParty(alex) && !inParty(steve));
    }

    @Test
    void theLeadersPermissionRaisesThePartySize() {
        setConfig("parties.max-size", 2);
        alex.addAttachment(plugin, "duels.party.size.3", true);
        alex.addAttachment(plugin, "duels.party.size.abc", true);

        partyWith(steve, sam);

        assertEquals(3, partyOf(alex).size());
        assertEquals(3, duels.parties().maxSize(partyOf(alex)));
        assertSays(alex, "party info", "3/3");
    }

    @Test
    void thePartySizePermissionStopsAtTheMaximum() {
        alex.addAttachment(plugin, "duels.party.size.5000", true);
        partyWith(steve);

        assertEquals(Settings.MAX_PARTY_SIZE, duels.parties().maxSize(partyOf(alex)));
    }
}
