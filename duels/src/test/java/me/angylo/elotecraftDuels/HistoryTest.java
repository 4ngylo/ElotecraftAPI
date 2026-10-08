package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.menu.HistoryMenu;
import me.angylo.elotecraftDuels.stats.MatchHistory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inventories after a fight ({@code /duel inventory}) and the duel history ({@code /duel history}). */
class HistoryTest extends DuelsTestBase {

    private static final int BOTTOM_ROW = 45;

    private TestPlayer alex;
    private TestPlayer steve;

    @BeforeEach
    void players() {
        alex = join("Alex");
        steve = join("Steve");
    }

    private Match fight(Kit kit) {
        await(duels.kits().update(kit));
        assertTrue(duels.matches().start(alex, steve, kit, readyArena("pit")));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        return match;
    }

    @Test
    void fightersSeeEachOthersInventoryAndCountsAfterwards() {
        fight(swordKit().withRule(KitRule.DEATH_DROPS, true));

        steve.simulateDamage(1, alex);
        steve.simulateDamage(1, alex);
        alex.simulateDamage(1, steve);
        steve.simulateDamage(1, alex);
        steve.simulateDamage(100, alex);
        tickUntil(() -> duels.matches().matchOf(alex).isEmpty());

        String link = inventoryCommand(alex, "Steve");
        server.dispatchCommand(alex, link.substring(1));
        Inventory steveInventory = alex.getOpenInventory().getTopInventory();
        assertEquals("Steve's inventory", Text.plain(alex.getOpenInventory().title()));
        // Death drops emptied Steve's inventory, but it was kept before that.
        assertEquals(Material.DIAMOND_SWORD, steveInventory.getItem(27).getType());

        server.dispatchCommand(alex, link.substring(1, link.lastIndexOf(' ')) + " Alex");
        List<String> hits = lore(alex.getOpenInventory().getTopInventory().getItem(BOTTOM_ROW + 7));
        assertTrue(hits.contains("Landed: 4"), hits.toString());
        assertTrue(hits.contains("Longest combo: 2"), hits.toString());
    }

    @Test
    void theNamesInADuelsResultOpenInventoriesOnceTheFightIsOver() {
        Match match = fight(swordKit());
        steve.simulateDamage(100, alex);
        assertEquals(Match.State.ENDING, match.state());

        String link = inventoryCommand(alex, "Steve");
        assertSays(alex, link.substring(1), "You can see inventories once the fight is over.");

        tickUntil(() -> duels.matches().matchOf(alex).isEmpty());
        assertTrue(messages(alex).stream().noneMatch(line -> line.contains("Inventories:")));
        server.dispatchCommand(alex, link.substring(1));
        assertEquals("Steve's inventory", Text.plain(alex.getOpenInventory().title()));
    }

    @Test
    void healthPotionsThatMissTheThrowerCountAsMissed() {
        fight(swordKit());
        splash(1.0);
        splash(0.2);
        steve.simulateDamage(100, alex);
        tickUntil(() -> duels.matches().matchOf(alex).isEmpty());

        server.dispatchCommand(alex, inventoryCommand(alex, "Alex").substring(1));

        List<String> potions = lore(alex.getOpenInventory().getTopInventory().getItem(BOTTOM_ROW + 8));
        assertEquals(List.of("Thrown: 2", "Missed: 1", "Accuracy: 50%"), potions);
    }

    /** Alex throws a splash potion of healing that reaches them with {@code intensity}. */
    private void splash(double intensity) {
        ItemStack item = ItemStack.of(Material.SPLASH_POTION);
        item.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(PotionType.HEALING));
        ThrownPotion potion = arenaWorld.spawn(alex.getLocation(), ThrownPotion.class);
        potion.setItem(item);
        potion.setShooter(alex);
        server.getPluginManager().callEvent(new PotionSplashEvent(potion, null, null, null, Map.of(alex, intensity)));
    }

    @Test
    void unknownFightsAreNoLongerKept() {
        assertSays(alex, "duel inventory not-an-id Steve", "no longer kept");
        assertSays(alex, "duel inventory 6f1c8e0a-0000-4000-8000-000000000000 Steve", "no longer kept");
    }

    @Test
    void finishedDuelsGoToBothPlayersHistory() {
        fight(swordKit());
        steve.simulateDamage(100, alex);

        tickUntil(() -> !await(duels.history().of(alex.getUniqueId())).isEmpty());
        MatchHistory.Entry won = await(duels.history().of(alex.getUniqueId())).getFirst();
        MatchHistory.Entry lost = await(duels.history().of("steve")).getFirst();
        assertTrue(won.won());
        assertEquals("Steve", won.opponent());
        assertEquals("sword", won.kit());
        assertEquals("eliminated", won.reason());
        assertFalse(lost.won());
        assertEquals("Alex", lost.opponent());

        server.dispatchCommand(alex, "duel history");
        tickUntil(() -> Text.plain(alex.getOpenInventory().title()).equals("Alex's duels"));
        assertEquals("Won vs Steve", Text.plain(alex.getOpenInventory().getTopInventory().getItem(0).getItemMeta().displayName()));
    }

    @Test
    void playersWithoutDuelsHaveNoHistory() {
        assertSays(alex, "duel history Steve", "Steve has no finished duels yet");
    }

    @Test
    void historyAgesShowOnlyTheirLargestUnit() {
        assertEquals(Duration.ofDays(3), HistoryMenu.roughly(Duration.ofDays(3).plusHours(4).plusMinutes(12)));
        assertEquals(Duration.ofMinutes(5), HistoryMenu.roughly(Duration.ofSeconds(330)));
        assertEquals(Duration.ofSeconds(42), HistoryMenu.roughly(Duration.ofSeconds(42)));
    }

    /** The command behind {@code name}'s link in the inventories message. */
    private static String inventoryCommand(TestPlayer player, String name) {
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            Optional<String> found = clickCommand(line, name);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new AssertionError("No inventory link for " + name);
    }

    private static Optional<String> clickCommand(Component component, String name) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.value().startsWith("/duel inventory ") && click.value().endsWith(" " + name)) {
            return Optional.of(click.value());
        }
        return component.children().stream().map(child -> clickCommand(child, name)).flatMap(Optional::stream).findFirst();
    }

    private static List<String> lore(ItemStack item) {
        List<Component> lines = item.getItemMeta().lore();
        return lines == null ? List.of() : lines.stream().map(Text::plain).toList();
    }
}
