package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.event.HostedEvent;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Player-hosted events: gathering players, the settings, starting, the fight and its result. */
class EventTest extends DuelsTestBase {

    private TestPlayer ann;
    private TestPlayer bob;
    private TestPlayer cid;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void setUpPlayers() {
        ann = join("Ann");
        bob = join("Bob");
        cid = join("Cid");
        kit = swordKit();
        arena = readyArena("pit");
    }

    private HostedEvent hostEvent() {
        assertSays(ann, "event host sword", "You're hosting a Sword event");
        return duels.events().hostedBy(ann).orElseThrow();
    }

    private Match fighting(TestPlayer player) {
        Match match = duels.matches().matchOf(player).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    private void click(TestPlayer player, int slot, ClickType type) {
        ((Menu) player.getOpenInventory().getTopInventory().getHolder()).button(slot).orElseThrow().onClick().accept(player, type);
        tick();
    }

    @Test
    void playersJoinAndTheLastTwoStandingWinAFreeForAll() {
        TestPlayer dee = join("Dee");
        TestPlayer outsider = join("Eve");
        List<String> prizes = new ArrayList<>();
        server.getCommandMap().register("test", new Command("eventprize") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String @NotNull [] args) {
                prizes.add(String.join(" ", args));
                return true;
            }
        });
        setConfig("events.reward.commands", List.of("eventprize <winner> <host> <kit> <arena>"));
        hostEvent();
        assertTrue(messages(outsider).stream().anyMatch(line -> line.contains("Ann is hosting a Sword event") && line.contains("[JOIN]")));
        assertSays(bob, "event join Ann", "You joined Ann's event");
        assertSays(cid, "event Ann", "You joined Ann's event");
        assertSays(dee, "event join ann", "You joined Ann's event");
        assertTrue(messages(ann).stream().anyMatch(line -> line.contains("Dee joined the event (4/16)")));

        duels.events().changeWinners(ann, 1);
        assertSays(ann, "event start", "Ann's event begins: 4 players");
        Match match = fighting(ann);
        assertEquals(Match.Type.EVENT, match.type());
        assertEquals(4, match.teams().size());

        bob.simulateDamage(100, ann);
        assertEquals(Match.State.FIGHTING, match.state());
        cid.simulateDamage(100, dee);

        assertEquals(Match.State.ENDING, match.state());
        assertTrue(messages(outsider).stream().anyMatch(line -> line.contains("EVENT Ann, Dee won Ann's event")));
        assertEquals(List.of("Ann Ann sword pit", "Dee Ann sword pit"), prizes);
        assertEquals(0, duels.stats().cached(ann.getUniqueId()).orElseThrow().wins());
        ticks(20 * duels.settings().endDelaySeconds() + 1);
        assertFalse(duels.matches().isBusy(ann));
    }

    @Test
    void waitingPlayersAreBusyAndTheHostLeavingCancels() {
        hostEvent();
        assertSays(bob, "event join Ann", "You joined");

        assertSays(bob, "duel queue sword", "already in a duel, a queue, an event");
        assertSays(cid, "duel Bob sword pit", "Bob is busy");
        assertSays(bob, "event host sword", "already in an event");

        server.dispatchCommand(ann, "event leave");
        assertTrue(messages(bob).stream().anyMatch(line -> line.contains("Ann cancelled their event.")));
        assertFalse(duels.events().isWaiting(bob));
        assertFalse(duels.matches().isBusy(bob));
    }

    @Test
    void theHostQuittingCancelsAndAPlayerLeavingIsDropped() {
        HostedEvent event = hostEvent();
        assertSays(bob, "event join Ann", "You joined");
        assertSays(cid, "event join Ann", "You joined");

        assertSays(cid, "duel leave", "You left the event.");
        assertEquals(2, event.size());
        ann.disconnect();

        assertTrue(messages(bob).stream().anyMatch(line -> line.contains("Ann left, so their event was cancelled.")));
        assertTrue(duels.events().openEvents().isEmpty());
    }

    @Test
    void itStartsOnItsOwnWhenTheWaitEndsOrIsCancelledWithTooFewPlayers() {
        setConfig("events.wait-time", "10s");
        hostEvent();
        ticks(20 * 11);
        assertTrue(messages(ann).stream().anyMatch(line -> line.contains("Fewer than 2 players joined Ann's event")));
        assertFalse(duels.events().isWaiting(ann));

        setConfig("events.host-cooldown", "0s");
        hostEvent();
        assertSays(bob, "event join Ann", "You joined");
        ticks(20 * 11);
        assertTrue(duels.matches().matchOf(bob).isPresent());
        fighting(bob);
    }

    @Test
    void aFullEventStartsAtOnceAndHostingHasACooldown() {
        setConfig("events.max-players", 2);
        // In game time: waiting for the database can tick past a few minutes.
        setConfig("events.host-cooldown", "1d");
        hostEvent();
        assertSays(bob, "event join Ann", "You joined");
        assertTrue(duels.matches().matchOf(ann).isPresent());
        assertSays(cid, "event join Ann", "isn't hosting an event");

        tickUntil(() -> duels.matches().matchOf(ann).isEmpty() || duels.matches().matchOf(ann).get().state() == Match.State.FIGHTING);
        server.dispatchCommand(bob, "duel leave");
        ticks(20 * duels.settings().endDelaySeconds() + 1);
        assertSays(ann, "event host sword", "Wait");
    }

    @Test
    void privateEventsNeedAnInvite() {
        hostEvent();
        duels.events().toggleOpen(ann);
        assertTrue(duels.events().openEvents().isEmpty());

        assertSays(bob, "event join Ann", "Ann's event is private");
        assertSays(ann, "event invite Bob", "Invited Bob");
        assertTrue(messages(bob).stream().anyMatch(line -> line.contains("Ann invited you to their Sword event")));
        assertSays(bob, "event join Ann", "You joined");
    }

    @Test
    void teamEventsUseThePickedTeamsAndCanForbidSpectators() {
        HostedEvent event = hostEvent();
        assertSays(bob, "event join Ann", "You joined");
        assertSays(cid, "event join Ann", "You joined");
        duels.events().toggleMode(ann);
        duels.events().toggleSpectating(ann);
        duels.events().changeWinners(ann, 3);
        assertEquals(1, event.winners());

        duels.events().start(ann, List.of(ann.getUniqueId(), bob.getUniqueId()), List.of(cid.getUniqueId()));
        Match match = fighting(ann);
        assertEquals(List.of(List.of(ann, bob), List.of(cid)), match.teams());
        TestPlayer watcher = join("Watcher");
        assertSays(watcher, "duel spectate Ann", "can't be watched");

        cid.simulateDamage(100, ann);
        assertEquals(Match.State.ENDING, match.state());
    }

    @Test
    void eventRulesChangeOnlyThisEventsKit() {
        hostEvent();
        assertSays(bob, "event join Ann", "You joined");
        duels.events().rule(ann, KitRule.POTIONS, false);
        duels.events().start(ann, null, null);
        Match match = fighting(ann);

        assertFalse(match.kit().flag(KitRule.POTIONS, duels.settings()));
        assertTrue(duels.kits().get("sword").orElseThrow().flag(KitRule.POTIONS, duels.settings()));
        PlayerItemConsumeEvent drink = new PlayerItemConsumeEvent(bob, ItemStack.of(Material.POTION), EquipmentSlot.HAND);
        server.getPluginManager().callEvent(drink);
        assertTrue(drink.isCancelled());
    }

    @Test
    void theSettingsMenuSwitchesAndShowsTheEvent() {
        HostedEvent event = hostEvent();
        assertEquals("Event Settings", Text.plain(ann.getOpenInventory().title()));

        click(ann, 28, ClickType.LEFT);
        assertFalse(event.isOpen());
        click(ann, 20, ClickType.LEFT);
        assertEquals(HostedEvent.Mode.TEAMS, event.mode());
        click(ann, 24, ClickType.LEFT);
        assertTrue(event.hasBorder());
        assertEquals(Material.STRUCTURE_VOID, ann.getOpenInventory().getTopInventory().getItem(24).getType());

        click(ann, 34, ClickType.LEFT);
        assertTrue(duels.events().hostedBy(ann).isEmpty());
    }

    @Test
    void theListShowsOpenEventsToJoin() {
        hostEvent();
        server.dispatchCommand(bob, "event");
        assertEquals("⚑ Events", Text.plain(bob.getOpenInventory().title()));
        assertEquals(Material.PLAYER_HEAD, bob.getOpenInventory().getTopInventory().getItem(0).getType());

        click(bob, 0, ClickType.LEFT);

        assertTrue(duels.events().isWaiting(bob));
    }

    @Test
    void theRulesMenuChangesARuleForThisEventOnly() {
        HostedEvent event = hostEvent();
        click(ann, 14, ClickType.LEFT);
        assertEquals("Event Rules", Text.plain(ann.getOpenInventory().title()));
        int potions = (int) Arrays.stream(KitRule.values()).filter(KitRule::isFlag).takeWhile(rule -> rule != KitRule.POTIONS).count();

        click(ann, potions, ClickType.LEFT);
        assertTrue(event.changed(KitRule.POTIONS));
        assertEquals(Material.GRAY_DYE, ann.getOpenInventory().getTopInventory().getItem(potions).getType());
        click(ann, potions, ClickType.RIGHT);
        assertFalse(event.changed(KitRule.POTIONS));
    }

    @Test
    void theListOffersEventFightsToWatch() {
        hostEvent();
        assertSays(bob, "event join Ann", "You joined");
        duels.events().start(ann, null, null);
        fighting(ann);

        server.dispatchCommand(cid, "event list");
        assertEquals(Material.DIAMOND_SWORD, cid.getOpenInventory().getTopInventory().getItem(0).getType());
        click(cid, 0, ClickType.LEFT);

        tickUntil(() -> cid.getGameMode() == GameMode.SPECTATOR);
        assertTrue(duels.matches().matchOf(cid).orElseThrow().isSpectator(cid));
    }

    @Test
    void anOlderMenusFileWithoutTheEventMenusUsesTheBundledOnes() throws IOException {
        Files.writeString(plugin.getDataFolder().toPath().resolve("menus.yml"), "# from a version without events\n");
        assertTrue(duels.reload());

        hostEvent();

        assertEquals("Event Settings", Text.plain(ann.getOpenInventory().title()));
        assertEquals(Material.FIREWORK_ROCKET, ann.getOpenInventory().getTopInventory().getItem(16).getType());
    }

    @Test
    void anOlderMenuWithoutANewButtonGetsTheBundledButton() throws IOException {
        Files.writeString(plugin.getDataFolder().toPath().resolve("menus.yml"),
                "event-settings:\n  title: Old Settings\n  kit:\n    slot: 10\n    material: DIAMOND_SWORD\n");
        assertTrue(duels.reload());

        hostEvent();

        assertEquals("Old Settings", Text.plain(ann.getOpenInventory().title()));
        assertEquals(Material.FIREWORK_ROCKET, ann.getOpenInventory().getTopInventory().getItem(16).getType());
    }
}
