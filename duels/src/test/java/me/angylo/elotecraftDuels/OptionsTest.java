package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest extends DuelsTestBase {

    /** Slots and materials of the default menus.yml {@code options}. */
    private static final int REQUESTS = 10;
    private static final int PARTY_INVITES = 12;
    private static final int SOUNDS = 16;

    private void click(TestPlayer player, int slot) {
        ((Menu) player.getOpenInventory().getTopInventory().getHolder()).button(slot).orElseThrow()
                .onClick().accept(player, ClickType.LEFT);
        tick();
    }

    private Material shown(TestPlayer player, int slot) {
        return player.getOpenInventory().getTopInventory().getItem(slot).getType();
    }

    @Test
    void everyOptionStartsOn() {
        TestPlayer alex = join("Alex");
        for (PlayerOptions option : PlayerOptions.values()) {
            assertTrue(option.isOn(alex), option.name());
        }
    }

    @Test
    void clickingAnOptionFlipsItAndRedrawsTheButton() {
        TestPlayer alex = join("Alex");
        alex.performCommand("duel options");
        tick();
        assertEquals(Material.LIME_DYE, shown(alex, REQUESTS));

        click(alex, REQUESTS);
        assertFalse(PlayerOptions.REQUESTS.isOn(alex));
        assertEquals(Material.GRAY_DYE, shown(alex, REQUESTS));

        click(alex, REQUESTS);
        assertTrue(PlayerOptions.REQUESTS.isOn(alex));
        assertEquals(Material.LIME_DYE, shown(alex, REQUESTS));
    }

    @Test
    void theMenuAndDuelToggleShareTheRequestsOption() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        swordKit();
        readyArena("pit");

        steve.performCommand("duel options");
        tick();
        click(steve, REQUESTS);
        assertSays(alex, "duel Steve sword", "Steve doesn't take duel requests");

        assertSays(steve, "duel toggle requests", "You get duel requests again");
        assertTrue(PlayerOptions.REQUESTS.isOn(steve));
    }

    @Test
    void playersWithPartyInvitesOffAreNotInvited() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        steve.performCommand("duel options");
        tick();
        click(steve, PARTY_INVITES);

        assertSays(alex, "party invite Steve", "Steve doesn't take party invites");
        assertTrue(duels.parties().partyOf(alex.getUniqueId()).isEmpty());
    }

    @Test
    void playersWithSoundsOffHearNoEffects() {
        TestPlayer alex = join("Alex");
        duels.settings().effects().play(alex, "menu-click");
        assertEquals(1, alex.getHeardSounds().size());

        alex.performCommand("duel options");
        tick();
        int heard = alex.getHeardSounds().size();
        click(alex, SOUNDS);
        duels.settings().effects().play(alex, "menu-click");
        assertEquals(heard, alex.getHeardSounds().size());
    }

    @Test
    void duelToggleSwitchesANamedOption() {
        TestPlayer alex = join("Alex");
        assertSays(alex, "duel toggle sounds", "Sounds: off");
        assertFalse(PlayerOptions.SOUNDS.isOn(alex));
        assertSays(alex, "duel toggle SOUNDS", "Sounds: on");
        assertTrue(PlayerOptions.SOUNDS.isOn(alex));
    }

    @Test
    void duelToggleRequestsUsesTheRequestMessages() {
        TestPlayer alex = join("Alex");
        assertSays(alex, "duel toggle requests", "You no longer get duel requests");
        assertFalse(PlayerOptions.REQUESTS.isOn(alex));
    }

    @Test
    void duelToggleAloneShowsItsUsageAndChangesNothing() {
        TestPlayer alex = join("Alex");
        assertSays(alex, "duel toggle", "Use /duel toggle <option>. Options: requests, party-invites, sidebar, sounds");
        for (PlayerOptions option : PlayerOptions.values()) {
            assertTrue(option.isOn(alex), option.name());
        }
    }

    @Test
    void duelToggleRefusesUnknownOptions() {
        TestPlayer alex = join("Alex");
        assertSays(alex, "duel toggle nope", "requests, party-invites, sidebar, sounds");
        for (PlayerOptions option : PlayerOptions.values()) {
            assertTrue(option.isOn(alex), option.name());
        }
    }

    @Test
    void duelToggleSuggestsTheOptions() {
        TestPlayer alex = join("Alex");
        assertEquals(List.of("party-invites"),
                server.getCommandMap().getCommand("duel").tabComplete(alex, "duel", new String[]{"toggle", "pa"}));
    }
}
