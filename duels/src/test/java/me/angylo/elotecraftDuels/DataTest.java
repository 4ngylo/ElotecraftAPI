package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.state.PlayerSnapshot;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings, arenas, kits, snapshots and stats on their own. */
class DataTest extends DuelsTestBase {

    @Test
    void bundledConfigLoadsAndBadValuesFallBack() throws InvalidConfigurationException {
        assertEquals(5, duels.settings().countdownSeconds());
        assertEquals(Duration.ofMinutes(5), duels.settings().maxDuration());
        assertTrue(duels.settings().allowedCommands().contains("msg"));

        YamlConfiguration bad = new YamlConfiguration();
        bad.loadFromString("""
                match: {countdown-seconds: 99, max-duration: soon, boss-bar-color: CHARTREUSE}
                rules: {allowed-commands: ["/MSG"]}
                rewards: {win: {money: -5}}
                ranked: {k-factor: 0, range-max: 9999}
                effects:
                  broken: {sound: "Not A Key", particle: DUST}
                  good: {sound: ui.button.click}
                """);
        Settings settings = Settings.load(bad, Logger.getLogger("test"));

        assertEquals(5, settings.countdownSeconds());
        assertEquals(Duration.ofMinutes(5), settings.maxDuration());
        assertEquals(BossBar.Color.RED, settings.bossBarColor());
        assertEquals(Set.of("msg"), settings.allowedCommands());
        assertEquals(0, settings.winReward().money());
        assertEquals(32, settings.ranked().kFactor());
        assertEquals(1000, settings.ranked().rangeMax());
        assertEquals(500, settings.ranked().range(40));
        assertEquals(1000, settings.ranked().range(500));
    }

    @Test
    void arenasNeedSpawnsAndCornersAndSurviveAReload() {
        Location origin = new Location(arenaWorld, 0, 64, 0);
        await(duels.arenas().create("box", origin));
        Arena empty = duels.arenas().get("box").orElseThrow();
        assertEquals(List.of(Arena.Problem.MISSING_SPAWN_1, Arena.Problem.MISSING_SPAWN_2, Arena.Problem.MISSING_CORNERS),
                empty.problems());

        Arena ready = readyArena("pit");
        Arena outside = ready.withSpawn(1, new Arena.Position(99, 64, 99, 0, 0));
        assertEquals(List.of(Arena.Problem.SPAWN_OUTSIDE), outside.problems());
        assertEquals(List.of(Arena.Problem.DISABLED), ready.withEnabled(false).problems());
        assertTrue(ready.contains(new Location(arenaWorld, 20.9, 80.9, 0)));
        assertFalse(ready.contains(new Location(arenaWorld, 21, 64, 0)));
        assertFalse(ready.contains(new Location(world, 5, 64, 5)));

        server.getScheduler().waitAsyncTasksFinished();
        assertTrue(duels.arenas().reload());
        assertTrue(duels.arenas().get("pit").orElseThrow().isReady());
        assertFalse(ArenaRegistry.validName("Bad.Name"));
    }

    @Test
    void kitItemsSurviveAReload() {
        Kit kit = new Kit("tank", "<gray>Tank", Material.SHIELD, "duels.kit.tank",
                List.of(ItemStack.of(Material.IRON_SWORD), ItemStack.empty(), ItemStack.of(Material.GOLDEN_APPLE, 3)), true, Set.of("bridge"));
        await(duels.kits().update(kit));
        server.getScheduler().waitAsyncTasksFinished();

        assertTrue(duels.kits().reload());
        Kit loaded = duels.kits().get("tank").orElseThrow();
        assertEquals(Material.GOLDEN_APPLE, loaded.items().get(2).getType());
        assertEquals(3, loaded.items().get(2).getAmount());
        assertEquals("duels.kit.tank", loaded.permission());
        assertTrue(loaded.build());
        assertEquals(Set.of("bridge"), loaded.arenaCategories());
        assertTrue(KitRegistry.validPermission("duels.kit.tank"));
        assertFalse(KitRegistry.validPermission("bad permission"));
    }

    @Test
    void snapshotsRoundTripThroughText() {
        TestPlayer alex = join("Alex");
        alex.getInventory().addItem(ItemStack.of(Material.EMERALD, 9));
        alex.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));
        alex.setLevel(12);
        alex.setGameMode(GameMode.CREATIVE);

        PlayerSnapshot snapshot = PlayerSnapshot.fromText(PlayerSnapshot.capture(alex).toText());
        PlayerSnapshot.resetForDuel(alex);
        alex.getInventory().clear();
        snapshot.applyState(alex);

        assertTrue(alex.getInventory().contains(Material.EMERALD, 9));
        assertTrue(alex.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(12, alex.getLevel());
        assertEquals(GameMode.CREATIVE, alex.getGameMode());
        assertThrows(IllegalArgumentException.class, () -> PlayerSnapshot.fromText("not: [a snapshot"));
    }

    @Test
    void streaksCountWinsInARow() {
        PlayerStats stats = PlayerStats.empty("Alex");
        assertEquals(0, stats.winRate());

        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        duels.stats().recordResult(alex, steve);
        duels.stats().recordResult(alex, steve);
        duels.stats().recordResult(steve, alex);
        duels.stats().recordResult(alex, steve);

        PlayerStats cached = duels.stats().cached(alex.getUniqueId()).orElseThrow();
        assertEquals(3, cached.wins());
        assertEquals(1, cached.winStreak());
        assertEquals(2, cached.bestWinStreak());
        assertEquals(75, cached.winRate());

        tickUntil(() -> await(duels.stats().top(1)).getFirst().wins() == 3);
        PlayerStats stored = await(duels.stats().top(1)).getFirst();
        assertEquals(2, stored.bestWinStreak());
        assertEquals(1, stored.winStreak());
        assertEquals(1, await(duels.stats().find("steve")).orElseThrow().wins());
    }
}
