package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.Settings.Reward;
import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.arena.ArenaRegistry;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitRegistry;
import me.angylo.elotecraftDuels.kit.KitRule;
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

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings, arenas, kits, snapshots and stats on their own. */
class DataTest extends DuelsTestBase {

    /** An older config.yml lacks newer keys: the bundled defaults fill in, without a warning. */
    @Test
    void keysMissingFromAnOlderConfigUseTheBundledDefaults() throws InvalidConfigurationException {
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.loadFromString("kit-editor: {timeout: 2m}\nparties: {invite-expiry: 45s}\n");
        YamlConfiguration old = new YamlConfiguration();
        old.loadFromString("match: {countdown-seconds: 3}\n");
        old.setDefaults(bundled);
        List<String> warnings = new java.util.ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        Settings settings = Settings.load(old, logger);

        assertEquals(Duration.ofMinutes(2), settings.kitEditorTimeout());
        assertEquals(Duration.ofSeconds(45), settings.partyInviteExpiry());
        assertTrue(warnings.stream().noneMatch(line -> line.contains("kit-editor") || line.contains("invite-expiry")), warnings.toString());
    }

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
                List.of(ItemStack.of(Material.IRON_SWORD), ItemStack.empty(), ItemStack.of(Material.GOLDEN_APPLE, 3)), true, Set.of("bridge"), true);
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
    void kitRulesSurviveAReloadAndBadOnesAreSkipped() throws IOException {
        Kit kit = new Kit("uhc", "UHC", Material.WATER_BUCKET, null, List.of(ItemStack.of(Material.IRON_SWORD)), false, Set.of(), true)
                .withRule(KitRule.NATURAL_REGENERATION, false).withRule(KitRule.PEARL_COOLDOWN, 15);
        await(duels.kits().update(kit));
        server.getScheduler().waitAsyncTasksFinished();
        File file = new File(plugin.getDataFolder(), "kits.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("kits.uhc.rules.hunger", "yes");
        yaml.set("kits.uhc.rules.no-such-rule", true);
        yaml.save(file);

        assertTrue(duels.kits().reload());
        Kit loaded = duels.kits().get("uhc").orElseThrow();
        assertEquals(Map.of(KitRule.NATURAL_REGENERATION, false, KitRule.PEARL_COOLDOWN, 15), loaded.rules());
    }

    @Test
    void kitRewardsSurviveAReloadAndNegativeMoneyBecomesZero() throws IOException {
        Kit plain = swordKit();
        Kit kit = new Kit("uhc", "UHC", Material.WATER_BUCKET, null, List.of(ItemStack.of(Material.IRON_SWORD)), false, Set.of(), true)
                .withRewards(new Kit.Rewards(new Reward(50, List.of("give <winner> diamond 1")), new Reward(0, List.of("say <loser> lost"))));
        await(duels.kits().update(kit));
        server.getScheduler().waitAsyncTasksFinished();

        assertTrue(duels.kits().reload());
        assertEquals(kit.rewards(), duels.kits().get("uhc").orElseThrow().rewards());
        assertEquals(Kit.Rewards.NONE, duels.kits().get(plain.name()).orElseThrow().rewards());

        File file = new File(plugin.getDataFolder(), "kits.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("kits.uhc.rewards.win.money", -5);
        yaml.save(file);
        assertTrue(duels.kits().reload());
        assertEquals(0, duels.kits().get("uhc").orElseThrow().rewards().win().money());
    }

    @Test
    void rewardsAddUp() {
        Reward sum = new Reward(10, List.of("a")).plus(new Reward(2.5, List.of("b")));

        assertEquals(12.5, sum.money());
        assertEquals(List.of("a", "b"), sum.commands());
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
