package me.angylo.elotecraftDuels;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duels kit toggle}: a disabled kit is hidden, and nobody can queue, duel or fight parties with it. */
class KitToggleTest extends DuelsTestBase {

    private TestPlayer admin;
    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void setUp() {
        admin = join("Admin");
        admin.setOp(true);
        alex = join("Alex");
        steve = join("Steve");
        swordKit();
        readyArena("pit");
    }

    private void disable() {
        assertSays(admin, "duels kit toggle sword off", "is off");
        tickUntil(() -> duels.kits().get("sword").orElseThrow().disabled());
    }

    @Test
    void toggleFlipsAndSurvivesAReload() {
        assertSays(admin, "duels kit toggle sword", "is off");
        tickUntil(() -> duels.kits().get("sword").orElseThrow().disabled());
        assertTrue(duels.kits().reload());
        assertTrue(duels.kits().get("sword").orElseThrow().disabled());

        assertSays(admin, "duels kit toggle sword", "is on");
        tickUntil(() -> !duels.kits().get("sword").orElseThrow().disabled());
        assertSays(admin, "duels kit toggle sword maybe", "Use /duels kit toggle");
    }

    @Test
    void aDisabledKitCannotBeQueuedOrChallengedWith() {
        disable();

        duels.queues().toggle(alex, duels.kits().get("sword").orElseThrow(), false);
        assertTrue(duels.queues().queued(alex.getUniqueId()).isEmpty());
        assertTrue(messages(alex).getLast().contains("can't use"));
        assertSays(alex, "duel Steve sword", "can't use");
        assertTrue(duels.requests().sendersOf(steve).isEmpty());
        assertFalse(server.getCommandMap().getCommand("duel").tabComplete(alex, "duel", new String[]{"Steve", ""}).contains("sword"));
    }

    @Test
    void disablingAKitEmptiesItsQueue() {
        duels.queues().toggle(alex, duels.kits().get("sword").orElseThrow(), false);
        assertTrue(duels.queues().queued(alex.getUniqueId()).isPresent());

        disable();
        tickUntil(() -> duels.queues().queued(alex.getUniqueId()).isEmpty());

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("was turned off")));
    }

    @Test
    void aPartyCannotFightWithADisabledKit() {
        assertSays(alex, "party Steve", "Invited");
        assertSays(steve, "party accept Alex", "joined the party");
        disable();

        assertSays(alex, "party split sword", "can't use");
        assertTrue(duels.matches().matchOf(alex).isEmpty());
    }
}
