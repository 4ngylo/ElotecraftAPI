package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ping range in the queues, the spectators option and the spectate menu, and lobby player visibility. */
class PlayerSettingsTest extends DuelsTestBase {

    /** Slot of the default menus.yml {@code options.ping-range}. */
    private static final int PING_RANGE = 15;

    private TestPlayer alex;
    private TestPlayer steve;
    private TestPlayer cid;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void players() {
        alex = join("Alex");
        steve = join("Steve");
        cid = join("Cid");
        kit = swordKit();
        arena = readyArena("pit");
    }

    private void click(TestPlayer player, int slot) {
        ((Menu) player.getOpenInventory().getTopInventory().getHolder()).button(slot).orElseThrow()
                .onClick().accept(player, ClickType.LEFT);
        tick();
    }

    private Match duel() {
        assertTrue(duels.matches().start(alex, steve, kit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        return match;
    }

    @Test
    void theQueuePairsOnlyPlayersWithinEachOthersPingRange() {
        PingRange.next(alex);
        PingRange.next(alex);
        assertEquals(100, PingRange.of(alex));
        steve.ping(250);

        alex.performCommand("duel queue sword");
        steve.performCommand("duel queue sword");
        ticks(20);
        assertTrue(duels.matches().matchOf(alex).isEmpty());

        steve.ping(80);
        ticks(20);
        assertTrue(duels.matches().matchOf(alex).isPresent());
    }

    @Test
    void theOptionsMenuCyclesThePingRange() {
        alex.performCommand("duel options");
        tick();

        click(alex, PING_RANGE);
        assertEquals(50, PingRange.of(alex));
        for (int i = 1; i < PingRange.CHOICES.size(); i++) {
            click(alex, PING_RANGE);
        }
        assertEquals(0, PingRange.of(alex));
    }

    @Test
    void fightersWithSpectatorsOffCannotBeWatchedExceptByStaff() {
        PlayerOptions.SPECTATORS.toggle(steve);
        Match match = duel();

        assertSays(cid, "duel spectate Alex", "That fight can't be watched.");
        cid.setOp(true);
        cid.performCommand("duel spectate Alex");
        tickUntil(() -> match.isSpectator(cid));
    }

    @Test
    void theSpectateMenuListsFightsAndWatchesTheClickedOne() {
        Match match = duel();

        cid.performCommand("duel spectate");
        tick();
        click(cid, 0);

        tickUntil(() -> match.isSpectator(cid));
    }

    @Test
    void theSpectateMenuSaysWhenThereIsNothingToWatch() {
        assertSays(cid, "duel spectate", "There's no fight you can watch right now.");
        PlayerOptions.SPECTATORS.toggle(alex);
        duel();
        assertSays(cid, "duel spectate", "There's no fight you can watch right now.");
    }

    @Test
    void lobbyPlayersOffHidesEveryoneButTheParty() {
        alex.performCommand("party invite Steve");
        steve.performCommand("party accept");
        PlayerOptions.LOBBY_PLAYERS.toggle(alex);
        ticks(20);
        assertFalse(alex.canSee(cid));
        assertTrue(alex.canSee(steve));
        assertTrue(cid.canSee(alex));

        PlayerOptions.LOBBY_PLAYERS.toggle(alex);
        ticks(20);
        assertTrue(alex.canSee(cid));
    }

    @Test
    void everyoneShowsInFights() {
        PlayerOptions.LOBBY_PLAYERS.toggle(alex);
        ticks(20);
        assertFalse(alex.canSee(steve));

        duel();
        ticks(20);

        assertTrue(alex.canSee(steve));
    }
}
