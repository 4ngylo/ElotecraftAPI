package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitPalette;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.menu.KitAdminMenu;
import me.angylo.elotecraftDuels.menu.KitEditorMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Pig;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit editor menu: layouts of admin kits, custom kits and admin kits, saved when it closes. */
class KitEditorTest extends DuelsTestBase {

    /** Editor slots: the first two hotbar slots and the helmet. */
    private static final int HOTBAR_0 = KitEditorMenu.slotOf(0);
    private static final int HOTBAR_1 = KitEditorMenu.slotOf(1);
    private static final int HELMET = KitEditorMenu.slotOf(39);
    /** Category buttons and item positions in the default menus.yml. */
    private static final int WEAPONS = 20;
    private static final int BOWS = 21;
    private static final int DIAMOND_SWORD = 5;
    private static final int ARROW = 2;
    private static final int DIAMOND_HELMET = 19 + 5;

    private final KitAdminMenu.AnvilAsk realAnvil = KitAdminMenu.anvil;
    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;
    private Arena arena;

    @BeforeEach
    void setUp() {
        alex = join("Alex");
        steve = join("Steve");
        alex.getInventory().addItem(ItemStack.of(Material.DIRT, 5));
        kit = new Kit("pvp", "<aqua>PvP", Material.DIAMOND_SWORD, null,
                List.of(ItemStack.of(Material.DIAMOND_SWORD), ItemStack.of(Material.GOLDEN_APPLE, 8)), Set.of());
        await(duels.kits().update(kit));
        arena = readyArena("pit");
        await(duels.kits().update(new Kit("base", "<gold>Base", Material.CHEST, null, List.of(), Set.of())));
        setConfig("custom-kits.base-kit", "base");
    }

    @AfterEach
    void restoreAnvil() {
        KitAdminMenu.anvil = realAnvil;
    }

    private void answerAnvils(String... answers) {
        Deque<String> left = new ArrayDeque<>(List.of(answers));
        KitAdminMenu.anvil = (owner, player, title, initialText) -> CompletableFuture.completedFuture(
                Optional.ofNullable(left.poll()).map(answer -> initialText + answer));
    }

    private ItemStack shown(TestPlayer player, int slot) {
        ItemStack item = player.getOpenInventory().getTopInventory().getItem(slot);
        return item == null ? ItemStack.empty() : item;
    }

    /** Closes the editor and lets the next tick end the session. */
    private void close(TestPlayer player) {
        messages(player);
        player.closeInventory();
        ticks(2);
    }

    /** The item in inventory slot {@code slot} once a duel with {@code duelKit} counts down. */
    private ItemStack inDuel(Kit duelKit, int slot) {
        assertTrue(duels.matches().start(alex, steve, duelKit, arena));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        return alex.getInventory().getItem(slot);
    }

    private int slotWithLore(TestPlayer player, String text) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return IntStream.range(0, top.getSize()).filter(slot -> {
            ItemStack item = top.getItem(slot);
            List<Component> lore = item == null ? null : item.lore();
            return lore != null && lore.stream().anyMatch(line -> Text.plain(line).contains(text));
        }).findFirst().orElseThrow(() -> new AssertionError("No item with '" + text + "' in " + menuTitle(player)));
    }

    /** Opens custom kit 1 and puts a diamond sword in the first hotbar slot. */
    private void customSword() {
        server.dispatchCommand(alex, "duel customkit 1");
        assertEquals("Custom kit 1", menuTitle(alex));
        clickSlot(alex, HOTBAR_0, ClickType.LEFT);
        assertEquals("Item categories", menuTitle(alex));
        clickSlot(alex, WEAPONS, ClickType.LEFT);
        assertEquals("Weapons and Tools (1/2)", menuTitle(alex));
        clickSlot(alex, DIAMOND_SWORD, ClickType.LEFT);
        assertEquals(Material.DIAMOND_SWORD, shown(alex, HOTBAR_0).getType());
    }

    @Test
    void aLayoutMovesTheKitsItemsWithoutTouchingTheInventory() {
        server.dispatchCommand(alex, "duel editkit pvp");
        assertEquals("PvP", menuTitle(alex));
        assertEquals(Material.DIAMOND_SWORD, shown(alex, HOTBAR_0).getType());

        clickSlot(alex, HOTBAR_0, ClickType.LEFT);
        clickSlot(alex, HELMET, ClickType.LEFT);
        assertEquals(Material.DIAMOND_SWORD, shown(alex, HOTBAR_0).getType(), "the armor slots stay as they are");
        clickSlot(alex, HOTBAR_1, ClickType.LEFT);
        assertEquals(Material.GOLDEN_APPLE, shown(alex, HOTBAR_0).getType());
        assertTrue(alex.getInventory().contains(Material.DIRT, 5));

        close(alex);

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Saved your layout of PvP")));
        assertFalse(duels.editor().isEditing(alex));
        assertEquals(Material.GOLDEN_APPLE, inDuel(kit, 0).getType());
    }

    @Test
    void movingBetweenEditorMenusKeepsTheSession() {
        customSword();
        clickSlot(alex, HOTBAR_1, ClickType.LEFT);

        assertEquals("Item categories", menuTitle(alex));
        assertTrue(duels.editor().isEditing(alex));
        clickNamed(alex, "Back");
        assertEquals("Custom kit 1", menuTitle(alex));
        assertTrue(duels.editor().isEditing(alex));
    }

    @Test
    void aCustomKitIsPickedEnchantedCountedAndUsedToChallenge() {
        customSword();
        clickSlot(alex, HOTBAR_0, ClickType.RIGHT);
        assertEquals("Add enchantments", menuTitle(alex));
        clickSlot(alex, slotWithLore(alex, "Sharpness V"), ClickType.LEFT);
        clickSlot(alex, slotWithLore(alex, "Smite II"), ClickType.LEFT);
        clickNamed(alex, "Back");

        clickSlot(alex, HOTBAR_1, ClickType.LEFT);
        clickSlot(alex, BOWS, ClickType.LEFT);
        clickSlot(alex, ARROW, ClickType.LEFT);
        clickSlot(alex, HOTBAR_1, ClickType.RIGHT);
        assertEquals("Change item count", menuTitle(alex));
        clickSlot(alex, 21, ClickType.LEFT);
        assertEquals(16, shown(alex, HOTBAR_1).getAmount());

        clickSlot(alex, HELMET, ClickType.LEFT);
        assertEquals("Armor", menuTitle(alex));
        clickSlot(alex, DIAMOND_HELMET, ClickType.LEFT);
        close(alex);

        Kit custom = duels.customKits().get(alex, 1).orElseThrow();
        ItemStack sword = custom.items().get(0);
        assertEquals(2, sword.getEnchantmentLevel(Enchantment.SMITE), "smite replaced sharpness");
        assertEquals(0, sword.getEnchantmentLevel(Enchantment.SHARPNESS));
        assertEquals(16, custom.items().get(1).getAmount());
        assertEquals(Material.DIAMOND_HELMET, custom.items().get(39).getType());

        assertSays(alex, "duel Steve custom", "Steve");
        steve.performCommand("duel accept Alex");
        Match match = duels.matches().matchOf(steve).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN || match.state() == Match.State.FIGHTING);
        assertEquals(2, steve.getInventory().getItem(0).getEnchantmentLevel(Enchantment.SMITE));
    }

    @Test
    void shiftClicksRemoveAndCopyTheLastItem() {
        customSword();
        clickSlot(alex, HOTBAR_1, ClickType.SHIFT_RIGHT);
        assertEquals(Material.DIAMOND_SWORD, shown(alex, HOTBAR_1).getType());

        clickSlot(alex, HOTBAR_0, ClickType.SHIFT_LEFT);
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, shown(alex, HOTBAR_0).getType());
        assertTrue(Text.plain(shown(alex, slotNamed(alex, "Info")).lore().getLast()).contains("2 items changed"));
    }

    @Test
    void aCustomKitKeepsItsNameMapAndRules() {
        Arena other = readyArena("other");
        customSword();
        answerAnvils("Archer");
        clickNamed(alex, "Rename Kit");
        ticks(2);
        assertEquals("Archer", menuTitle(alex));
        clickNamed(alex, "Change Map");
        clickNamed(alex, "pit");
        clickNamed(alex, "Kit Rules");
        assertEquals("Archer › Rules", menuTitle(alex));
        clickNamed(alex, "hunger");
        clickNamed(alex, "Back");
        close(alex);

        Kit custom = duels.customKits().get(alex, 1).orElseThrow();
        assertEquals("Archer", Text.plain(Text.mm(custom.displayName())));
        assertEquals("pit", custom.arena());
        assertTrue(custom.accepts(arena));
        assertFalse(custom.accepts(other));
        assertTrue(custom.flag(KitRule.HUNGER, duels.settings()));
        assertSays(alex, "duel Steve custom", "Archer duel (pit)");
    }

    @Test
    void theEditorOnlyGivesOfferedItems() {
        ItemStack sharp = ItemStack.of(Material.DIAMOND_SWORD);
        sharp.addEnchantment(Enchantment.SHARPNESS, 5);
        ItemStack tooSharp = ItemStack.of(Material.DIAMOND_SWORD);
        tooSharp.addUnsafeEnchantment(Enchantment.SHARPNESS, 10);
        ItemStack both = sharp.clone();
        both.addUnsafeEnchantment(Enchantment.SMITE, 1);
        ItemStack knockbackStick = ItemStack.of(Material.STICK);
        knockbackStick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 5);
        ItemStack strongerStick = ItemStack.of(Material.STICK);
        strongerStick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 6);

        assertTrue(duels.customKits().fits(List.of(sharp, knockbackStick, ItemStack.of(Material.ARROW, 64))));
        assertFalse(duels.customKits().fits(List.of(tooSharp)));
        assertFalse(duels.customKits().fits(List.of(both)), "conflicting enchantments");
        assertFalse(duels.customKits().fits(List.of(strongerStick)));
        assertFalse(duels.customKits().fits(List.of(ItemStack.of(Material.BEDROCK))));
    }

    @Test
    void adminsEditTheKitItself() {
        alex.setOp(true);
        server.dispatchCommand(alex, "duels kit edit pvp");
        assertEquals("PvP", menuTitle(alex));
        clickSlot(alex, HOTBAR_1, ClickType.SHIFT_LEFT);
        clickSlot(alex, HELMET, ClickType.LEFT);
        clickSlot(alex, DIAMOND_HELMET, ClickType.LEFT);
        close(alex);

        Kit edited = duels.kits().get("pvp").orElseThrow();
        assertEquals(Material.DIAMOND_SWORD, edited.items().get(0).getType());
        assertTrue(edited.items().get(1).isEmpty());
        assertEquals(Material.DIAMOND_HELMET, edited.items().get(39).getType());
    }

    @Test
    void resetPutsTheItemsBack() {
        customSword();
        clickNamed(alex, "Reset Kit");
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, shown(alex, HOTBAR_0).getType());
        close(alex);

        assertTrue(duels.customKits().get(alex, 1).isEmpty());
    }

    @Test
    void quittingOrShuttingDownSaves() {
        customSword();
        alex.disconnect();
        tick();
        assertFalse(duels.editor().isEditing(alex));

        TestPlayer bob = join("Bob");
        server.dispatchCommand(steve, "duel editkit pvp");
        clickSlot(steve, HOTBAR_0, ClickType.LEFT);
        clickSlot(steve, HOTBAR_1, ClickType.LEFT);
        duels.shutdown();
        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());
        tickUntil(() -> {
            server.getScheduler().waitAsyncTasksFinished();
            return true;
        });
        ticks(5);

        assertTrue(duels.matches().start(steve, bob, duels.kits().get("pvp").orElseThrow(), arena));
        Match match = duels.matches().matchOf(steve).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        assertEquals(Material.GOLDEN_APPLE, steve.getInventory().getItem(0).getType());
    }

    @Test
    void aLayoutIsDroppedWhenTheKitChangesAndCanBeReset() {
        server.dispatchCommand(alex, "duel editkit pvp");
        clickSlot(alex, HOTBAR_0, ClickType.LEFT);
        clickSlot(alex, HOTBAR_1, ClickType.LEFT);
        close(alex);
        await(duels.kits().update(new Kit("pvp", "<aqua>PvP", Material.DIAMOND_SWORD, null,
                List.of(ItemStack.of(Material.DIAMOND_SWORD), ItemStack.of(Material.GOLDEN_APPLE, 16)), Set.of())));

        assertEquals(Material.DIAMOND_SWORD, inDuel(duels.kits().get("pvp").orElseThrow(), 0).getType());
        duels.matches().stop(alex);
        tickUntil(() -> !duels.matches().isBusy(alex));

        assertSays(alex, "duel editkit reset pvp", "Your layout of PvP is gone");
    }

    @Test
    void anOldLayoutsTableGetsTheNewColumnsAndKeepsItsLayouts() throws SQLException {
        duels.shutdown();
        File file = new File(plugin.getDataFolder(), "duels.db");
        String swapped = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(
                List.of(ItemStack.of(Material.GOLDEN_APPLE, 8), ItemStack.of(Material.DIAMOND_SWORD))));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE duels_kit_layouts");
            statement.executeUpdate("""
                    CREATE TABLE duels_kit_layouts (uuid VARCHAR(36) NOT NULL, kit VARCHAR(32) NOT NULL, items TEXT NOT NULL,
                        PRIMARY KEY (uuid, kit))""");
            statement.executeUpdate("INSERT INTO duels_kit_layouts VALUES ('" + alex.getUniqueId() + "', 'pvp', '" + swapped + "')");
        }

        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());
        tickUntil(() -> duels.layouts().layout(alex.getUniqueId(), kit).isPresent());

        answerAnvils("Mine");
        // Commands may still reach the stopped instance: open the new one's editor directly.
        duels.editorMenu().openLayout(alex, kit);
        assertEquals(Material.GOLDEN_APPLE, shown(alex, HOTBAR_0).getType());
        clickNamed(alex, "Rename Kit");
        ticks(2);
        assertEquals("Mine", menuTitle(alex));
        close(alex);
        server.getScheduler().waitAsyncTasksFinished();

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Saved your layout of PvP")));
        assertEquals(Material.GOLDEN_APPLE, inDuel(kit, 0).getType());
    }

    @Test
    void mobsFromSpawnEggsGoWithTheArena() {
        inDuel(kit, 0);
        Pig hatched = alex.getWorld().spawn(alex.getLocation(), Pig.class, null, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);
        Pig placed = alex.getWorld().spawn(alex.getLocation(), Pig.class, null, CreatureSpawnEvent.SpawnReason.CUSTOM);

        duels.matches().stop(alex);
        tickUntil(() -> !duels.matches().isBusy(alex));
        ticks(2);

        assertFalse(hatched.isValid());
        assertTrue(placed.isValid(), "only mobs from eggs are the fight's");
    }

    @Test
    void editingPlayersCannotQueueAndDuelLeaveStopsEditing() {
        server.dispatchCommand(alex, "duel editkit pvp");
        assertSays(alex, "duel queue pvp", "You're already in a duel");

        server.dispatchCommand(alex, "duel leave");
        tick();

        assertFalse(duels.editor().isEditing(alex));
    }

    @Test
    void potionsAndTippedArrowsAreGroupedByTypeBaseThenLongThenStrong() {
        KitPalette palette = new KitPalette(plugin.getLogger(),
                () -> YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "menus.yml")));
        List<String> expectedStart = List.of("night_vision", "long_night_vision", "invisibility", "long_invisibility",
                "leaping", "long_leaping", "strong_leaping", "fire_resistance", "long_fire_resistance",
                "swiftness", "long_swiftness", "strong_swiftness");

        for (String key : List.of("potions", "bows")) {
            KitPalette.Category category = palette.categories().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
            List<String> potions = palette.items(category, KitPalette.PotionForm.SPLASH).stream()
                    .filter(item -> item.getItemMeta() instanceof PotionMeta)
                    .map(item -> ((PotionMeta) item.getItemMeta()).getBasePotionType().getKey().getKey()).toList();

            assertEquals(expectedStart, potions.subList(0, expectedStart.size()), key);
        }
    }
}
