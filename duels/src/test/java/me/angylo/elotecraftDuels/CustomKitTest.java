package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.menu.KitEditorMenu;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custom kits: built in the kit editor from the offered items, used to challenge. */
class CustomKitTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer bob;

    @BeforeEach
    void setUpKits() {
        alex = join("Alex");
        bob = join("Bob");
        readyArena("pit");
        await(duels.kits().update(new Kit("pool", "<gold>Pool", Material.CHEST, null, List.of(), Set.of())));
        setConfig("custom-kits.base-kit", "pool");
    }

    private long count(TestPlayer player, Material material) {
        return Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull)
                .filter(item -> item.getType() == material).count();
    }

    /** Alex builds custom kit 1: a diamond sword (weapons, 6th item) and arrows (bows, 3rd item). */
    private void buildSwordAndArrows() {
        alex.performCommand("duel customkit 1");
        clickSlot(alex, KitEditorMenu.slotOf(0), ClickType.LEFT);
        clickNamed(alex, "Weapons");
        clickSlot(alex, 5, ClickType.LEFT);
        clickSlot(alex, KitEditorMenu.slotOf(1), ClickType.LEFT);
        clickNamed(alex, "Bows");
        clickSlot(alex, 2, ClickType.LEFT);
        alex.closeInventory();
        ticks(2);
        assertFalse(duels.editor().isEditing(alex));
    }

    @Test
    void aBuiltKitIsUsedByBothFighters() {
        buildSwordAndArrows();

        assertSays(alex, "duel Bob custom", "Bob");
        bob.performCommand("duel accept Alex");
        Match match = duels.matches().matchOf(bob).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN || match.state() == Match.State.FIGHTING);

        assertTrue(match.kit().isCustom());
        assertEquals("Alex's custom kit 1", Text.plain(Text.mm(match.kit().displayName())));
        assertEquals(1, count(bob, Material.DIAMOND_SWORD));
        assertEquals(1, count(bob, Material.ARROW));
        assertEquals(0, count(bob, Material.BOW));
    }

    @Test
    void customKitsCannotBeBetOnNorUsedWithoutTheirBaseKit() {
        buildSwordAndArrows();
        assertSays(alex, "duel Bob custom bet 100", "Bets are not allowed on custom kits");

        await(duels.kits().delete("pool"));

        assertTrue(duels.customKits().of(alex).isEmpty());
        assertSays(alex, "duel Bob custom", "There is no kit called 'custom'");
    }

    @Test
    void withoutABaseKitCustomKitsAreOff() {
        setConfig("custom-kits.base-kit", "");

        assertSays(alex, "duel customkit", "Custom kits are off");
        assertSays(alex, "duel customkit 1", "Custom kits are off");
    }

    @Test
    void adminKitsCannotBeCalledCustom() {
        assertThrows(IllegalArgumentException.class, () -> duels.kits().create(Kit.CUSTOM, Material.STONE, alex.getInventory()));
        assertFalse(duels.kits().get(Kit.CUSTOM).isPresent());
    }

    @Test
    void aPermissionGivesMoreCustomKitSlotsUpToNine() {
        assertSays(alex, "duel customkit 4", "Pick a custom kit from 1 to 3.");

        alex.addAttachment(plugin, "duels.kit.custom.slots.5", true);
        assertEquals(5, duels.customKits().slots(alex));
        assertSays(alex, "duel customkit 6", "Pick a custom kit from 1 to 5.");

        alex.addAttachment(plugin, "duels.kit.custom.slots.99", true);
        assertEquals(9, duels.customKits().slots(alex));
    }
}
