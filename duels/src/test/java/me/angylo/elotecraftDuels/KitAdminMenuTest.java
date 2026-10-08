package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.input.InputListener;
import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRule;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code /duels kit} menus. Clicks call the buttons directly: MockBukkit cannot route them through MenuListener. */
class KitAdminMenuTest extends DuelsTestBase {

    private static final int BUILD = 0;
    private static final int DAMAGE = 1;
    private static final int DELETE = 8;
    private static final int MODE = 9;
    /** The first rule button. */
    private static final int RULES = 10;
    private static final int HUNGER = RULES + KitRule.HUNGER.ordinal();
    private static final int PEARL_COOLDOWN = RULES + KitRule.PEARL_COOLDOWN.ordinal();

    private TestPlayer admin;

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

    private String title() {
        return Text.plain(admin.getOpenInventory().title());
    }

    private void click(int slot, ClickType type) {
        ((Menu) top().getHolder()).button(slot).orElseThrow().onClick().accept(admin, type);
        tick();
    }

    private List<String> lore(int slot) {
        return top().getItem(slot).getItemMeta().lore().stream().map(Text::plain).toList();
    }

    private Kit sword() {
        return duels.kits().get("sword").orElseThrow();
    }

    @Test
    void listShowsEveryKitAndOpensItsSettings() {
        server.dispatchCommand(admin, "duels kit");

        assertEquals("⚙ Kits", title());
        assertEquals(Material.DIAMOND_SWORD, top().getItem(0).getType());
        assertTrue(lore(0).contains("▪ Permission: everyone"));
        assertTrue(lore(0).contains("▪ Items: 1"));

        click(0, ClickType.LEFT);

        assertEquals("⚙ Sword", title());
    }

    @Test
    void everyRuleFitsOnTheFirstPage() {
        server.dispatchCommand(admin, "duels kit sword");

        KitRule last = KitRule.values()[KitRule.values().length - 1];
        assertEquals(last.key(), Text.plain(top().getItem(RULES + last.ordinal()).getItemMeta().displayName()));
    }

    @Test
    void togglesRedrawInPlace() {
        server.dispatchCommand(admin, "duels kit sword");
        assertTrue(lore(BUILD).contains("Fighters may place blocks: Off"));

        click(BUILD, ClickType.LEFT);
        click(DAMAGE, ClickType.LEFT);

        assertTrue(sword().build());
        assertFalse(sword().damage());
        assertTrue(lore(BUILD).contains("Fighters may place blocks: On"));
    }

    @Test
    void theModeButtonCyclesTheModeAndMakesItABuildKit() {
        server.dispatchCommand(admin, "duels kit sword");

        click(MODE, ClickType.LEFT);

        assertEquals(Kit.Mode.BRIDGE, sword().mode());
        assertTrue(sword().build());
        assertTrue(lore(MODE).contains("Now: bridge"));
    }

    @Test
    void flagRulesFlipAndReset() {
        server.dispatchCommand(admin, "duels kit sword");
        boolean hunger = sword().flag(KitRule.HUNGER, duels.settings());

        click(HUNGER, ClickType.LEFT);

        assertEquals(!hunger, sword().rules().get(KitRule.HUNGER));
        assertTrue(lore(HUNGER).contains("▪ set for this kit"));

        click(HUNGER, ClickType.RIGHT);

        assertFalse(sword().rules().containsKey(KitRule.HUNGER));
    }

    @Test
    void secondsAreTypedInChat() {
        server.dispatchCommand(admin, "duels kit sword");
        messages(admin);

        click(PEARL_COOLDOWN, ClickType.LEFT);

        assertTrue(messages(admin).stream().anyMatch(line -> line.contains("Type pearl-cooldown for Sword in seconds")));
        admin.chat("15");
        tickUntil(() -> sword().number(KitRule.PEARL_COOLDOWN).equals(OptionalInt.of(15)));
        tick();
        assertEquals("⚙ Sword", title());
    }

    @Test
    void deleteNeedsShiftRightClick() {
        server.dispatchCommand(admin, "duels kit sword");

        click(DELETE, ClickType.LEFT);
        assertTrue(duels.kits().get("sword").isPresent());

        click(DELETE, ClickType.SHIFT_RIGHT);

        assertTrue(duels.kits().get("sword").isEmpty());
        assertEquals("⚙ Kits", title());
    }

    @Test
    void saveTakesYourInventory() {
        server.dispatchCommand(admin, "duels kit sword");
        admin.getInventory().addItem(ItemStack.of(Material.BOW));

        click(6, ClickType.LEFT);

        assertTrue(sword().items().stream().anyMatch(item -> item.getType() == Material.BOW));
    }
}
