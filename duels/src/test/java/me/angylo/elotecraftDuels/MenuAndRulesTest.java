package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.menu.ArenaMenu;
import me.angylo.elotecraftDuels.menu.KitMenu;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Menus, gameplay rules and rewards. */
class MenuAndRulesTest extends DuelsTestBase {

    @Test
    void challengingWithoutAKitOpensTheKitMenu() {
        TestPlayer alex = join("Alex");
        join("Steve");
        swordKit();
        readyArena("pit");

        server.dispatchCommand(alex, "duel Steve");

        Inventory top = alex.getOpenInventory().getTopInventory();
        assertEquals("⚔ Choose a kit", Text.plain(alex.getOpenInventory().title()));
        assertEquals(Material.DIAMOND_SWORD, top.getItem(slotNamed(alex, "Sword")).getType());
        assertEquals(Material.BARRIER, top.getItem(top.getSize() - 9 + 4).getType());
        assertTrue(top.getItem(top.getSize() - 9 + 3) == null, "no back button for a challenge");
    }

    private static String title(TestPlayer player) {
        return Text.plain(player.getOpenInventory().title());
    }

    private static List<String> lore(TestPlayer player, int slot) {
        return player.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().lore().stream().map(Text::plain).toList();
    }

    /** A button in the bottom row of the open menu, by its slot in that row. */
    private static int bottom(TestPlayer player, int slot) {
        return player.getOpenInventory().getTopInventory().getSize() - 9 + slot;
    }

    @Test
    void queueMenusSayWhichQueueTheyJoin() {
        TestPlayer alex = join("Alex");
        swordKit();
        readyArena("pit");
        server.dispatchCommand(alex, "duel queue sword");

        server.dispatchCommand(alex, "duel queue");

        assertEquals("Play › Unranked", title(alex));
        List<String> unranked = lore(alex, slotNamed(alex, "Sword"));
        assertTrue(unranked.contains("▪ Queued: 1 · Fighting: 0"), unranked.toString());
        assertTrue(unranked.contains("✔ You're in this queue"), unranked.toString());
        assertTrue(unranked.stream().noneMatch(line -> line.contains("building")), unranked.toString());

        server.dispatchCommand(alex, "duel ranked");

        assertEquals("Play › Ranked", title(alex));
        List<String> ranked = lore(alex, slotNamed(alex, "Sword"));
        assertTrue(ranked.contains("▪ Your rating: 1000 Bronze"), ranked.toString());
        assertTrue(ranked.contains("▪ Queued: 0 · Fighting: 0"), ranked.toString());
        assertTrue(ranked.contains("▶ Click to join the queue"), ranked.toString());
        assertTrue(lore(alex, 4).contains("Overall: 1000 Bronze"), "the rating sits in the middle of the top row");
    }

    @Test
    void theRankedMenuShowsTheRatingInEachKit() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        swordKit();
        readyArena("pit");
        duels.stats().recordResult(alex, steve, "sword", 16);

        server.dispatchCommand(alex, "duel ranked");

        List<String> lore = lore(alex, slotNamed(alex, "Sword"));
        assertTrue(lore.contains("▪ Your rating: 1016 Bronze"), lore.toString());
        assertTrue(lore.contains("▪ Ranked record: 1W 0L"), lore.toString());
    }

    @Test
    void theSwitchButtonOpensTheOtherQueueForThoseWhoMayJoinIt() {
        TestPlayer alex = join("Alex");
        swordKit();
        readyArena("pit");
        server.dispatchCommand(alex, "duel queue");

        clickNamed(alex, "Ranked »");
        tickUntil(() -> title(alex).equals("Play › Ranked"));
        clickNamed(alex, "Unranked »");
        tickUntil(() -> title(alex).equals("Play › Unranked"));

        alex.addAttachment(plugin, "duels.queue.ranked", false);
        server.dispatchCommand(alex, "duel queue");
        assertNull(alex.getOpenInventory().getTopInventory().getItem(bottom(alex, 5)));
    }

    @Test
    void challengesKeepTheKitMenu() {
        TestPlayer alex = join("Alex");
        swordKit();
        server.dispatchCommand(alex, "duel editkit");

        assertEquals("⚔ Choose a kit", title(alex));
    }

    @Test
    void noKitsMeansNoMenu() {
        TestPlayer alex = join("Alex");
        join("Steve");

        server.dispatchCommand(alex, "duel Steve");

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("No kits have been set up yet.")));
    }

    @Test
    void arenaMenuShowsBusyArenasAndRandom() {
        TestPlayer alex = join("Alex");
        Arena pit = readyArena("pit");
        readyArena("yard");
        TestPlayer first = join("First");
        TestPlayer second = join("Second");
        Kit sword = swordKit();
        duels.matches().start(first, second, sword, pit);
        ArenaMenu menu = new ArenaMenu(plugin, duels.messages(), new ConfigFile(plugin, "menus.yml"), duels::settings,
                duels.arenas(), duels.matches());

        menu.open(alex, sword, chosen -> { });

        Inventory top = alex.getOpenInventory().getTopInventory();
        List<String> pitLore = lore(alex, slotNamed(alex, "pit"));
        assertTrue(pitLore.contains("▪ In use"));
        assertEquals(Material.ENDER_EYE, top.getItem(slotNamed(alex, "Random arena")).getType());
    }

    @Test
    void fightersCannotUseItemsDuringTheCountdown() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        duels.matches().start(alex, steve, swordKit(), readyArena("pit"));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);

        PlayerInteractEvent interact = new PlayerInteractEvent(alex, Action.RIGHT_CLICK_AIR, ItemStack.of(Material.ENDER_PEARL),
                null, BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(interact);
        alex.openInventory(server.createInventory(null, 27));

        assertTrue(interact.isCancelled());
        Inventory open = alex.getOpenInventory().getTopInventory();
        assertTrue(open == null || open.getSize() != 27);
    }

    @Test
    void hungerAndRegenerationFollowTheRules() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        duels.matches().start(alex, steve, swordKit(), readyArena("pit"));
        tickUntil(() -> duels.matches().matchOf(alex).map(match -> match.state() == Match.State.COUNTDOWN).orElse(false));

        FoodLevelChangeEvent hunger = new FoodLevelChangeEvent(alex, 10);
        server.getPluginManager().callEvent(hunger);
        EntityRegainHealthEvent regen = new EntityRegainHealthEvent(alex, 1, EntityRegainHealthEvent.RegainReason.SATIATED);
        server.getPluginManager().callEvent(regen);

        assertTrue(hunger.isCancelled());
        assertFalse(regen.isCancelled());
    }

    @Test
    void winnersGetCommandRewards() throws IOException {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("rewards.win.money", 25);
        config.set("rewards.win.commands", List.of("say <winner> beat <loser> with <kit> in <arena>"));
        config.save(file);
        assertTrue(duels.reload());
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        duels.matches().start(alex, steve, swordKit(), readyArena("pit"));
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());

        steve.simulateDamage(100, alex);

        assertEquals(Match.State.ENDING, match.state());
        assertEquals(Optional.of(1), duels.stats().cached(alex.getUniqueId()).map(stats -> stats.wins()));
    }

    @Test
    void kitRewardCommandsRunAfterTheGlobalOnesOnlyForALethalHit() {
        List<String> prizes = new ArrayList<>();
        server.getCommandMap().register("test", new Command("prize") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String @NotNull [] args) {
                prizes.add(String.join(" ", args));
                return true;
            }
        });
        setConfig("rewards.win.commands", List.of("prize global <winner>"));
        assertTrue(duels.reload());
        Kit kit = swordKit().withRewards(new Kit.Rewards(new Reward(0, List.of("prize kit <winner> <kit> <arena>")),
                new Reward(0, List.of("prize consolation <loser>"))));
        await(duels.kits().update(kit));
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        Arena pit = readyArena("pit");

        fight(alex, steve, kit, pit);
        assertTrue(duels.matches().leave(steve));
        assertEquals(List.of(), prizes);
        ticks(20 * (duels.settings().endDelaySeconds() + 1));

        fight(alex, steve, kit, pit);
        steve.simulateDamage(100, alex);
        assertEquals(List.of("global Alex", "kit Alex sword pit", "consolation Steve"), prizes);
    }

    private void fight(TestPlayer first, TestPlayer second, Kit kit, Arena arena) {
        duels.matches().start(first, second, kit, arena);
        Match match = duels.matches().matchOf(first).orElseThrow();
        tickUntil(() -> match.state() == Match.State.FIGHTING);
    }

    @Test
    void rightClickingAKitPreviewsItsItems() {
        TestPlayer alex = join("Alex");
        join("Steve");
        swordKit();
        readyArena("pit");
        server.dispatchCommand(alex, "duel Steve");

        clickNamed(alex, "Sword", ClickType.RIGHT);
        tick();

        assertEquals("Preview › Sword", Text.plain(alex.getOpenInventory().title()));
        assertEquals(Material.DIAMOND_SWORD, alex.getOpenInventory().getTopInventory().getItem(KitMenu.previewSlot(0)).getType());
    }

    @Test
    void previewShowsArmorHelmetFirstAndTheOffHand() {
        assertEquals(27, KitMenu.previewSlot(0));
        assertEquals(0, KitMenu.previewSlot(9));
        assertEquals(36, KitMenu.previewSlot(39));
        assertEquals(39, KitMenu.previewSlot(36));
        assertEquals(41, KitMenu.previewSlot(40));
    }

    @Test
    void playersCanTurnDuelRequestsOff() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        swordKit();
        readyArena("pit");

        assertSays(steve, "duel toggle requests", "You no longer get duel requests");
        assertSays(alex, "duel Steve sword", "Steve doesn't take duel requests");
        assertTrue(duels.requests().sendersOf(steve).isEmpty());

        assertSays(steve, "duel toggle requests", "You get duel requests again");
        assertSays(alex, "duel Steve sword", "Challenged Steve");
        assertEquals(List.of("Alex"), duels.requests().sendersOf(steve));
    }
}
