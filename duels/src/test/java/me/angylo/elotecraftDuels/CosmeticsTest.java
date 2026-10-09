package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftDuels.Cosmetics.Cosmetic;
import me.angylo.elotecraftDuels.Cosmetics.Kind;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticsTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void setUpPlayers() {
        alex = join("Alex");
        steve = join("Steve");
        kit = swordKit();
        arena = readyArena("pit");
    }

    private Match fight() {
        return fight(kit);
    }

    private Match fight(Kit with) {
        assertTrue(duels.matches().start(alex, steve, with, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        messages(alex);
        messages(steve);
        return match;
    }

    private Cosmetic cosmetic(Kind kind, String id) {
        return duels.settings().cosmetics().all(kind).stream().filter(found -> found.id().equals(id)).findFirst().orElseThrow();
    }

    private void click(TestPlayer player, int slot) {
        ((Menu) player.getOpenInventory().getTopInventory().getHolder()).button(slot).orElseThrow()
                .onClick().accept(player, ClickType.LEFT);
        tick();
    }

    private static boolean said(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void theKillersKillMessageGoesToEveryoneInTheFight() {
        Cosmetics.choose(alex, Kind.KILL_MESSAGE, cosmetic(Kind.KILL_MESSAGE, "slain"));
        fight();

        steve.simulateDamage(100, alex);

        assertTrue(said(messages(steve), "Steve was slain by Alex."));
        assertTrue(said(messages(alex), "Steve was slain by Alex."));
    }

    @Test
    void theLastOpponentToHitGetsTheKillOfAFall() {
        Cosmetics.choose(alex, Kind.KILL_MESSAGE, cosmetic(Kind.KILL_MESSAGE, "slain"));
        fight();
        steve.simulateDamage(1, alex);

        duels.matches().eliminate(steve);

        assertTrue(said(messages(steve), "Steve was slain by Alex."));
    }

    @Test
    void noKillWithoutAHitOrOnAForfeit() {
        Cosmetics.choose(alex, Kind.KILL_MESSAGE, cosmetic(Kind.KILL_MESSAGE, "slain"));
        fight();
        duels.matches().eliminate(steve);
        assertTrue(messages(steve).stream().noneMatch(line -> line.contains("slain")));

        ticks(20 * duels.settings().endDelaySeconds() + 1);
        fight();
        steve.simulateDamage(1, alex);
        steve.performCommand("duel leave");
        assertTrue(messages(steve).stream().noneMatch(line -> line.contains("slain")));
    }

    @Test
    void theKillEffectSoundIsHeardInTheFight() {
        Cosmetics.choose(alex, Kind.KILL_EFFECT, cosmetic(Kind.KILL_EFFECT, "hearts"));
        fight();

        steve.simulateDamage(100, alex);

        steve.assertSoundHeard("minecraft:entity.player.levelup");
    }

    @Test
    void aPickWhosePermissionIsMissingCountsAsNone() {
        setConfig("cosmetics.kill-messages.royal.permission", "duels.cosmetic.royal");
        Cosmetics.choose(alex, Kind.KILL_MESSAGE, cosmetic(Kind.KILL_MESSAGE, "royal"));
        assertEquals(Optional.empty(), duels.settings().cosmetics().chosen(alex, Kind.KILL_MESSAGE));

        alex.addAttachment(plugin, "duels.cosmetic.royal", true);
        assertEquals("royal", duels.settings().cosmetics().chosen(alex, Kind.KILL_MESSAGE).orElseThrow().id());
    }

    @Test
    void theCosmeticsMenuLeadsToEachKind() {
        alex.performCommand("duel cosmetics");
        tick();
        assertEquals("Duels › Cosmetics", menuTitle(alex));

        clickNamed(alex, "Kill messages");

        assertEquals("Cosmetics › Kill messages", menuTitle(alex));
        clickNamed(alex, "Back");
        assertEquals("Duels › Cosmetics", menuTitle(alex));
    }

    @Test
    void clickingAnEntryPicksItAndClickingAgainDropsIt() {
        alex.performCommand("duel cosmetics kill-effect");
        tick();
        click(alex, firstItemSlot(alex));
        assertEquals("lightning", duels.settings().cosmetics().chosen(alex, Kind.KILL_EFFECT).orElseThrow().id());

        click(alex, firstItemSlot(alex));
        assertEquals(Optional.empty(), duels.settings().cosmetics().chosen(alex, Kind.KILL_EFFECT));
    }

    @Test
    void lockedEntriesCannotBePicked() {
        setConfig("cosmetics.kill-messages.slain.permission", "duels.cosmetic.slain");
        alex.performCommand("duel cosmetics kill-message");
        tick();
        messages(alex);

        click(alex, firstItemSlot(alex));

        assertTrue(said(messages(alex), "is locked"));
        assertEquals(Optional.empty(), duels.settings().cosmetics().chosen(alex, Kind.KILL_MESSAGE));
    }

    @Test
    void aTeammateNeverGetsTheKill() {
        setConfig("rules.kit-defaults.friendly-fire", true);
        TestPlayer ann = join("Ann");
        Cosmetics.choose(alex, Kind.KILL_MESSAGE, cosmetic(Kind.KILL_MESSAGE, "slain"));
        assertTrue(duels.matches().start(List.of(List.<Player>of(alex, ann), List.<Player>of(steve)), kit, arena,
                Match.Type.PARTY, false));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        ann.simulateDamage(1, alex);
        messages(ann);

        duels.matches().eliminate(ann);

        List<String> lines = messages(ann);
        assertTrue(said(lines, "Ann is out."));
        assertTrue(lines.stream().noneMatch(line -> line.contains("slain")));
    }

    @Test
    void killCreditEndsWithTheRound() {
        Cosmetics.choose(alex, Kind.KILL_MESSAGE, cosmetic(Kind.KILL_MESSAGE, "slain"));
        Match match = fight(kit.withRule(KitRule.ROUNDS_TO_WIN, 2));
        steve.simulateDamage(1, alex);
        alex.simulateDamage(100, steve);
        assertEquals(Match.State.ROUND_OVER, match.state());
        tickUntil(() -> match.state() == Match.State.FIGHTING);
        messages(steve);

        duels.matches().eliminate(steve);

        assertTrue(messages(steve).stream().noneMatch(line -> line.contains("slain")));
    }

    @Test
    void aKillMessageWithoutATextIsNotOfferedOrSent() {
        setConfig("cosmetics.kill-messages", Map.of("custom", Map.of("icon", "STONE")));
        Cosmetics.choose(alex, Kind.KILL_MESSAGE, cosmetic(Kind.KILL_MESSAGE, "custom"));
        alex.performCommand("duel cosmetics kill-message");
        tick();
        assertFalse(alex.getOpenInventory().getTopInventory().contains(Material.STONE));
        alex.closeInventory();
        fight();

        steve.simulateDamage(100, alex);

        assertTrue(messages(steve).stream().noneMatch(line -> line.contains("kill-messages")));
    }

    @Test
    void anUnknownMenuShowsTheUsage() {
        assertSays(alex, "duel cosmetics hats", "Use /duel cosmetics");
    }
}
