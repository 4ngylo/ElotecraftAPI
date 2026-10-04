package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.menu.ArenaMenu;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(Material.DIAMOND_SWORD, top.getItem(0).getType());
        assertEquals("Sword", Text.plain(top.getItem(0).getItemMeta().displayName()));
        assertEquals(Material.BARRIER, top.getItem(top.getSize() - 9 + 4).getType());
    }

    @Test
    void queueMenuMarksYourQueue() {
        TestPlayer alex = join("Alex");
        swordKit();
        server.dispatchCommand(alex, "duel queue sword");

        server.dispatchCommand(alex, "duel queue");

        List<String> lore = alex.getOpenInventory().getTopInventory().getItem(0).getItemMeta().lore().stream().map(Text::plain).toList();
        assertTrue(lore.contains("✔ You're in this queue"));
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
        duels.matches().start(first, second, swordKit(), pit);
        ArenaMenu menu = new ArenaMenu(plugin, duels.messages(), new ConfigFile(plugin, "menus.yml"), duels::settings,
                duels.arenas(), duels.matches());

        menu.open(alex, chosen -> { });

        Inventory top = alex.getOpenInventory().getTopInventory();
        List<String> pitLore = top.getItem(0).getItemMeta().lore().stream().map(Text::plain).toList();
        assertTrue(pitLore.contains("▪ In use"));
        assertEquals(Material.ENDER_EYE, top.getItem(top.getSize() - 9 + 3).getType());
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
}
