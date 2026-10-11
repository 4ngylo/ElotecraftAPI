package me.angylo.elotecraftDuels;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.api.MatchEndEvent;
import me.angylo.elotecraftDuels.api.MatchStartEvent;
import me.angylo.elotecraftDuels.api.QueueJoinEvent;
import me.angylo.elotecraftDuels.kit.GoldenHeads;
import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.kit.KitPalette;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import me.angylo.elotecraftDuels.match.QueueManager;
import me.angylo.elotecraftDuels.stats.PlayerStats;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Practice server features: API events, the ranked win requirement, playing a queue again, the rematch and play again
 * lobby items, the pearl cooldown bar, the leaderboard menu and golden heads.
 */
class PracticeFeaturesTest extends DuelsTestBase {

    private TestPlayer alex;
    private TestPlayer steve;
    private Kit kit;

    @BeforeEach
    void setUp() {
        alex = join("Alex");
        steve = join("Steve");
        kit = swordKit();
        readyArena("pit");
    }

    private Match fightStarted(TestPlayer player) {
        Match match = duels.matches().matchOf(player).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);
        ticks(20 * duels.settings().countdownSeconds());
        assertEquals(Match.State.FIGHTING, match.state());
        return match;
    }

    /** Alex beats Steve in a duel of the unranked queue, and both are back in the lobby. */
    private void queueDuelWonByAlex() {
        duels.queues().toggle(alex, kit, false);
        duels.queues().toggle(steve, kit, false);
        fightStarted(alex);
        steve.simulateDamage(100, alex);
        tickUntil(() -> !duels.matches().isBusy(alex) && !duels.matches().isBusy(steve));
    }

    private <E extends Event> List<E> recorded(Class<E> type) {
        List<E> seen = new ArrayList<>();
        server.getPluginManager().registerEvent(type, new Listener() { }, EventPriority.MONITOR,
                (listener, event) -> {
                    if (type.isInstance(event)) {
                        seen.add(type.cast(event));
                    }
                }, plugin);
        return seen;
    }

    @Test
    void matchEventsTellTheStartAndTheResult() {
        List<MatchStartEvent> starts = recorded(MatchStartEvent.class);
        List<MatchEndEvent> ends = recorded(MatchEndEvent.class);

        queueDuelWonByAlex();

        assertEquals(1, starts.size());
        assertEquals("sword", starts.getFirst().kit());
        assertEquals("pit", starts.getFirst().arena());
        assertEquals(List.of(List.of(alex), List.of(steve)), starts.getFirst().teams());
        assertEquals(1, ends.size());
        assertEquals(List.of(alex), ends.getFirst().winners());
        assertEquals(Match.EndReason.ELIMINATED, ends.getFirst().reason());
        assertFalse(ends.getFirst().draw());
    }

    @Test
    void aCancelledQueueJoinKeepsThePlayerOut() {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(QueueJoinEvent event) {
                event.setCancelled(event.ranked());
            }
        }, plugin);

        duels.queues().toggle(alex, kit, true);
        assertTrue(duels.queues().queued(alex.getUniqueId()).isEmpty());

        duels.queues().toggle(alex, kit, false);
        assertEquals(Optional.of(new QueueManager.QueueId("sword", false)), duels.queues().queued(alex.getUniqueId()));
    }

    @Test
    void rankedQueuesNeedTheConfiguredWins() {
        setConfig("ranked.required-wins", 2);

        duels.queues().toggle(alex, kit, true);
        assertTrue(duels.queues().queued(alex.getUniqueId()).isEmpty());
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("Win 2 duels to unlock ranked queues. (0/2)")));

        duels.stats().cache(alex.getUniqueId(), new PlayerStats("Alex", 2, 0, 0, 0, PlayerStats.START_ELO, Map.of()));
        duels.queues().toggle(alex, kit, true);
        assertTrue(duels.queues().queued(alex.getUniqueId()).isPresent());
    }

    @Test
    void playAgainJoinsTheLastQueueWhileTheWindowIsOpen() {
        queueDuelWonByAlex();
        assertEquals(Optional.of(new QueueManager.QueueId("sword", false)), duels.queues().lastQueue(alex));

        server.dispatchCommand(alex, "duel playagain");
        assertEquals(Optional.of(new QueueManager.QueueId("sword", false)), duels.queues().queued(alex.getUniqueId()));
        // Again leaves nothing: the player stays queued.
        server.dispatchCommand(alex, "duel playagain");
        assertTrue(duels.queues().queued(alex.getUniqueId()).isPresent());

        ticks((int) (duels.settings().rematchWindow().toSeconds() * 20));
        assertTrue(duels.queues().lastQueue(alex).isEmpty());
    }

    @Test
    void aChallengeForgetsTheLastQueue() {
        queueDuelWonByAlex();
        assertTrue(duels.matches().start(alex, steve, kit, duels.arenas().get("pit").orElseThrow()));
        fightStarted(alex);
        steve.simulateDamage(100, alex);
        tickUntil(() -> !duels.matches().isBusy(alex));

        assertTrue(duels.queues().lastQueue(alex).isEmpty());
        server.dispatchCommand(alex, "duel playagain");
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("no queue duel to play again")));
    }

    @Test
    void lobbyItemsOfferPlayAgainAndRematchAfterAQueueDuel() {
        setConfig("lobby-items.enabled", true);
        queueDuelWonByAlex();
        ticks(20);

        assertEquals("Play again (right-click)", itemName(alex, 0));
        assertEquals("Rematch (right-click)", itemName(alex, 5));

        alex.getInventory().setHeldItemSlot(0);
        server.getPluginManager().callEvent(new PlayerInteractEvent(alex, Action.RIGHT_CLICK_AIR, alex.getInventory().getItem(0),
                null, BlockFace.SELF, EquipmentSlot.HAND));
        assertTrue(duels.queues().queued(alex.getUniqueId()).isPresent());
        ticks(20);
        assertEquals("Leave the queue (right-click)", itemName(alex, 0));
    }

    private static String itemName(TestPlayer player, int slot) {
        ItemStack item = player.getInventory().getItem(slot);
        return item == null || item.isEmpty() ? "" : Text.plain(item.getItemMeta().displayName());
    }

    @Test
    void fightersStartWithoutExperienceAndTheBarShowsThePearlCooldown() {
        alex.setLevel(30);
        Kit pearls = kit.withRule(KitRule.PEARL_COOLDOWN, 15);
        await(duels.kits().update(pearls));
        assertTrue(duels.matches().start(alex, steve, pearls, duels.arenas().get("pit").orElseThrow()));
        fightStarted(alex);
        assertEquals(0, alex.getLevel());

        EnderPearl pearl = alex.getWorld().spawn(alex.getLocation(), EnderPearl.class);
        server.getPluginManager().callEvent(new PlayerLaunchProjectileEvent(alex, ItemStack.of(Material.ENDER_PEARL), pearl));
        ticks(3);
        assertEquals(15, alex.getLevel());
        assertTrue(alex.getExp() > 0.9f);

        assertTrue(duels.matches().leave(alex));
        tickUntil(() -> !duels.matches().isBusy(alex));
        ticks(2);
        assertEquals(30, alex.getLevel());
    }

    private void fightWith(Kit rules) {
        await(duels.kits().update(rules));
        assertTrue(duels.matches().start(alex, steve, rules, duels.arenas().get("pit").orElseThrow()));
        fightStarted(alex);
    }

    @Test
    void arrowAndGappleCooldownsGoOnTheirItemsAndTheLongestShowsOnTheBar() {
        fightWith(kit.withRule(KitRule.ARROW_COOLDOWN, 3).withRule(KitRule.GAPPLE_COOLDOWN, 10));

        Arrow arrow = alex.getWorld().spawn(alex.getLocation(), Arrow.class);
        server.getPluginManager().callEvent(new EntityShootBowEvent(alex, ItemStack.of(Material.BOW), ItemStack.of(Material.ARROW),
                arrow, EquipmentSlot.HAND, 1, true));
        ticks(2);
        assertEquals(3 * 20 - 1, alex.getCooldown(Material.BOW), 1);
        assertEquals(3, alex.getLevel());

        server.getPluginManager().callEvent(new PlayerItemConsumeEvent(alex, ItemStack.of(Material.GOLDEN_APPLE), EquipmentSlot.HAND));
        ticks(3);
        assertTrue(alex.getCooldown(Material.GOLDEN_APPLE) > 9 * 20);
        assertEquals(10, alex.getLevel());

        assertTrue(duels.matches().leave(alex));
        tickUntil(() -> !duels.matches().isBusy(alex) && alex.getCooldown(Material.GOLDEN_APPLE) == 0);
        assertEquals(0, alex.getCooldown(Material.BOW));
    }

    @Test
    void theCooldownBarRuleTurnsTheCountdownOff() {
        fightWith(kit.withRule(KitRule.PEARL_COOLDOWN, 15).withRule(KitRule.COOLDOWN_BAR, false));

        EnderPearl pearl = alex.getWorld().spawn(alex.getLocation(), EnderPearl.class);
        server.getPluginManager().callEvent(new PlayerLaunchProjectileEvent(alex, ItemStack.of(Material.ENDER_PEARL), pearl));
        ticks(3);

        assertEquals(15 * 20, alex.getCooldown(Material.ENDER_PEARL), 3);
        assertEquals(0, alex.getLevel());
    }

    @Test
    void goldenHeadsWearTheirSkinAndTheEditorOffersThem() {
        ItemStack head = GoldenHeads.create(1);
        PlayerProfile profile = ((SkullMeta) head.getItemMeta()).getPlayerProfile();
        assertTrue(profile != null && profile.getProperties().stream().anyMatch(property -> property.getName().equals("textures")));

        KitPalette palette = new KitPalette(plugin.getLogger(),
                () -> YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "menus.yml")));
        assertTrue(palette.categories().stream().flatMap(category -> category.items().stream()).anyMatch(GoldenHeads::is));
        assertTrue(palette.allows(GoldenHeads.create(16)));
    }

    @Test
    void theLeaderboardMenuListsBoardsAndOpensOne() {
        queueDuelWonByAlex();

        server.dispatchCommand(alex, "duel leaderboard");
        assertEquals("Profile › Leaderboard", menuTitle(alex));
        assertTrue(itemNames(alex).contains("Sword"));

        server.dispatchCommand(alex, "duel leaderboard wins");
        tickUntil(() -> menuTitle(alex).equals("Leaderboard › Wins"));
        assertEquals("#1 Alex", itemNames(alex).get(firstItemSlot(alex)));
    }

    @Test
    void anUnknownBoardIsRefused() {
        server.dispatchCommand(alex, "duel leaderboard nothing");
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("There is no kit called 'nothing'")));
    }

    private PlayerInteractEvent eat(TestPlayer player) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, player.getInventory().getItemInMainHand(),
                null, BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void fightersEatGoldenHeads() {
        Kit heads = new Kit("uhc", "UHC", Material.PLAYER_HEAD, null, List.of(GoldenHeads.create(2)), Set.of());
        await(duels.kits().update(heads));
        assertTrue(duels.matches().start(alex, steve, heads, duels.arenas().get("pit").orElseThrow()));
        fightStarted(alex);
        alex.getInventory().setHeldItemSlot(0);
        assertTrue(GoldenHeads.is(alex.getInventory().getItemInMainHand()));

        PlayerInteractEvent event = eat(alex);

        assertEquals(Event.Result.DENY, event.useItemInHand());
        assertEquals(1, alex.getInventory().getItemInMainHand().getAmount());
        assertTrue(alex.hasPotionEffect(PotionEffectType.REGENERATION));
        assertTrue(alex.hasPotionEffect(PotionEffectType.ABSORPTION));
    }

    @Test
    void goldenHeadsOutsideFightsAreOrdinaryHeads() {
        alex.getInventory().setItemInMainHand(GoldenHeads.create(1));

        PlayerInteractEvent event = eat(alex);

        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
        assertEquals(1, alex.getInventory().getItemInMainHand().getAmount());
        assertFalse(alex.hasPotionEffect(PotionEffectType.REGENERATION));
    }
}
