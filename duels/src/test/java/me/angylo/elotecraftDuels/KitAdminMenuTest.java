package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.input.InputListener;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.menu.KitAdminMenu;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code /duels kit} menus. Clicks call the buttons directly: MockBukkit cannot route them through MenuListener. */
class KitAdminMenuTest extends DuelsTestBase {

    /** The only kit in a centered list sits in the middle of the first row. */
    private static final int FIRST_ENTRY = 13;

    private TestPlayer admin;

    private final KitAdminMenu.AnvilAsk realAnvil = KitAdminMenu.anvil;

    @BeforeEach
    void setUpAdmin() {
        server.getPluginManager().registerEvents(new InputListener(), plugin);
        admin = join("Admin");
        admin.setOp(true);
        swordKit();
    }

    private Inventory top() {
        return admin.getOpenInventory().getTopInventory();
    }

    private void click(String name, ClickType type) {
        clickNamed(admin, name, type);
    }

    private boolean loreHas(String button, String line) {
        return loreAt(admin, slotNamed(admin, button)).contains(line);
    }

    private Kit sword() {
        return duels.kits().get("sword").orElseThrow();
    }

    @Test
    void listShowsEveryKitAndOpensItsSettings() {
        server.dispatchCommand(admin, "duels kit");

        assertEquals("Admin › Kits", menuTitle(admin));
        assertEquals(Material.DIAMOND_SWORD, top().getItem(FIRST_ENTRY).getType());
        assertTrue(loreAt(admin, FIRST_ENTRY).contains("▪ Permission: everyone"));
        assertTrue(loreAt(admin, FIRST_ENTRY).contains("▪ Items: 1"));

        clickSlot(admin, FIRST_ENTRY, ClickType.LEFT);

        assertEquals("Kits › Sword", menuTitle(admin));
    }

    @Test
    void everyRuleFitsOnTheFirstPage() {
        server.dispatchCommand(admin, "duels kit sword");
        click("Game rules", ClickType.LEFT);

        assertEquals("Sword › Rules", menuTitle(admin));
        List<String> names = IntStream.range(0, top().getSize()).mapToObj(top()::getItem)
                .filter(item -> item != null && item.getType() != Material.ARROW && item.getType() != Material.BARRIER)
                .map(item -> Text.plain(item.getItemMeta().displayName())).toList();
        assertEquals(Arrays.stream(KitRule.values()).map(KitRule::key).toList(), names);
    }

    @Test
    void buildAndDamageAreOnlyInTheRulesMenu() {
        server.dispatchCommand(admin, "duels kit sword");
        assertFalse(itemNames(admin).contains("Building") || itemNames(admin).contains("Damage"));
        click("Game rules", ClickType.LEFT);

        click(KitRule.BUILD.key(), ClickType.LEFT);
        click(KitRule.DAMAGE.key(), ClickType.LEFT);

        assertTrue(sword().flag(KitRule.BUILD, duels.settings()));
        assertFalse(sword().flag(KitRule.DAMAGE, duels.settings()));
        assertTrue(loreHas(KitRule.BUILD.key(), "▪ set for this kit"));
    }

    /** Answers the menu's anvils from {@code answers}, in order; an empty answer is a closed anvil. */
    private void answerAnvils(String... answers) {
        Deque<String> left = new ArrayDeque<>(List.of(answers));
        KitAdminMenu.anvil = (owner, player, title, initialText) ->
                CompletableFuture.completedFuture(Optional.ofNullable(left.poll()).filter(answer -> !answer.isEmpty()));
    }

    @AfterEach
    void restoreAnvil() {
        KitAdminMenu.anvil = realAnvil;
    }

    @Test
    void effectsAreAddedWithTwoAnvilsAndRemovedWithARightClick() {
        server.dispatchCommand(admin, "duels kit sword");
        click("Potion effects", ClickType.LEFT);
        assertEquals("Sword › Effects", menuTitle(admin));
        answerAnvils("1", "30");

        click("absorption", ClickType.LEFT);
        ticks(2);

        assertEquals(List.of(Kit.effect(PotionEffectType.ABSORPTION, 1, 30)), sword().effects());
        assertEquals("Sword › Effects", menuTitle(admin));
        // Slot 10 is the first of a full centered row: the kit's own effects come first.
        assertEquals("absorption", itemNames(admin).get(10));
        assertTrue(loreHas("absorption", "▪ Duration: 30s"));

        click("absorption", ClickType.RIGHT);

        assertTrue(sword().effects().isEmpty());
    }

    @Test
    void aBadAmplifierOrAClosedAnvilChangesNothing() {
        server.dispatchCommand(admin, "duels kit sword");
        click("Potion effects", ClickType.LEFT);
        answerAnvils("5");
        messages(admin);

        click("absorption", ClickType.LEFT);
        ticks(2);

        assertTrue(messages(admin).stream().anyMatch(line -> line.contains("Use /duels kit effect")));
        answerAnvils("1", "");
        click("absorption", ClickType.LEFT);
        ticks(2);

        assertTrue(sword().effects().isEmpty());
        assertEquals("Sword › Effects", menuTitle(admin));
    }

    @Test
    void theModeButtonCyclesTheModeAndMakesItABuildKit() {
        server.dispatchCommand(admin, "duels kit sword");

        click("Mode", ClickType.LEFT);

        assertEquals(Kit.Mode.BRIDGE, sword().mode());
        assertTrue(sword().flag(KitRule.BUILD, duels.settings()));
        assertTrue(loreHas("Mode", "Now: bridge"));
    }

    @Test
    void flagRulesFlipAndReset() {
        server.dispatchCommand(admin, "duels kit sword");
        click("Game rules", ClickType.LEFT);
        boolean hunger = sword().flag(KitRule.HUNGER, duels.settings());

        click(KitRule.HUNGER.key(), ClickType.LEFT);

        assertEquals(!hunger, sword().rules().get(KitRule.HUNGER));
        assertTrue(loreHas(KitRule.HUNGER.key(), "▪ set for this kit"));

        click(KitRule.HUNGER.key(), ClickType.RIGHT);

        assertFalse(sword().rules().containsKey(KitRule.HUNGER));
    }

    @Test
    void secondsAreTypedInChatAndTheRulesMenuComesBack() {
        server.dispatchCommand(admin, "duels kit sword");
        click("Game rules", ClickType.LEFT);
        messages(admin);

        click(KitRule.PEARL_COOLDOWN.key(), ClickType.LEFT);

        assertTrue(messages(admin).stream().anyMatch(line -> line.contains("Type pearl-cooldown for Sword in seconds")));
        admin.chat("15");
        tickUntil(() -> sword().number(KitRule.PEARL_COOLDOWN, duels.settings()).equals(OptionalInt.of(15)));
        tick();
        assertEquals("Sword › Rules", menuTitle(admin));
    }

    @Test
    void deleteAsksToConfirm() {
        server.dispatchCommand(admin, "duels kit sword");

        click("Delete Sword", ClickType.LEFT);
        assertEquals("Are you sure?", menuTitle(admin));
        click("Cancel", ClickType.LEFT);
        assertTrue(duels.kits().get("sword").isPresent());
        assertEquals("Kits › Sword", menuTitle(admin));

        click("Delete Sword", ClickType.LEFT);
        click("Confirm", ClickType.LEFT);

        assertTrue(duels.kits().get("sword").isEmpty());
        assertEquals("Admin › Kits", menuTitle(admin));
    }

    @Test
    void saveTakesYourInventory() {
        server.dispatchCommand(admin, "duels kit sword");
        admin.getInventory().addItem(ItemStack.of(Material.BOW));

        click("Save items", ClickType.LEFT);

        assertTrue(sword().items().stream().anyMatch(item -> item.getType() == Material.BOW));
    }
}
