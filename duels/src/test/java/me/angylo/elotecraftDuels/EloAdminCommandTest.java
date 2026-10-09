package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.kit.Kit;
import me.angylo.elotecraftDuels.stats.KitRating;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duels elo}: seeing and changing players' ratings. */
class EloAdminCommandTest extends DuelsTestBase {

    private TestPlayer admin;
    private TestPlayer alex;
    private TestPlayer steve;
    private Kit sword;

    @BeforeEach
    void players() {
        admin = join("Admin");
        admin.setOp(true);
        alex = join("Alex");
        steve = join("Steve");
        sword = swordKit();
        readyArena("pit");
        duels.stats().recordResult(alex, steve, "sword", 20);
        tickUntil(() -> await(duels.seasons().ratings(1)).size() == 2);
    }

    private KitRating cached(TestPlayer player) {
        return duels.stats().cached(player.getUniqueId()).orElseThrow().ratings().get("sword");
    }

    @Test
    void showsAPlayersRatings() {
        assertSays(admin, "duels elo Alex", "Alex · overall 1020");
        assertSays(admin, "duels elo Alex", "Sword 1020");
        assertSays(admin, "duels elo Nobody", "No duel stats for 'Nobody'");
        assertSays(admin, "duels elo", "/duels elo set <player> <kit|all> <rating>");
    }

    @Test
    void setChangesTheRatingTheCacheAndThePeak() {
        assertSays(admin, "duels elo set Alex sword 1500", "Sword 1020 → 1500");

        assertEquals(new KitRating(1500, 1, 0, 1500), cached(alex));
        assertSays(admin, "duels elo set Alex sword 1200", "1500 → 1200");
        assertEquals(new KitRating(1200, 1, 0, 1500), cached(alex));
        assertSays(admin, "duels elo set Alex sword 20000", "Ratings go from 0 to 10000");
        assertSays(admin, "duels elo set Alex nope 10", "There is no kit called 'nope'");
        assertSays(admin, "duels elo set Alex sword", "Use /duels elo set");
    }

    @Test
    void addWorksOnEveryKitAndStartsMissingRatings() {
        Kit bow = new Kit("bow", "Bow", Material.BOW, null, List.of(ItemStack.of(Material.BOW)), Set.of());
        await(duels.kits().update(bow));

        assertSays(admin, "duels elo add Steve all -50", "Bow 1000 → 950");

        assertEquals(930, cached(steve).elo());
        assertEquals(950, duels.stats().cached(steve.getUniqueId()).orElseThrow().ratings().get("bow").elo());
        assertSays(admin, "duels elo add Steve sword -5000", "→ 0");
    }

    @Test
    void anOfflinePlayersRatingChangesInTheDatabase() {
        steve.disconnect();
        tick();

        assertSays(admin, "duels elo set Steve sword 1111", "980 → 1111");

        tickUntil(() -> await(duels.stats().find("Steve")).map(stats -> stats.ratings().get("sword").elo() == 1111).orElse(false));
    }

    @Test
    void resetAsksToConfirmAndResetsThePeak() {
        assertSays(admin, "duels elo set Alex sword 1400", "→ 1400");
        assertSays(admin, "duels elo set Alex sword 1300", "→ 1300");

        assertSays(admin, "duels elo reset Alex sword confirm", "Run the reset without confirm first");
        assertSays(admin, "duels elo reset Alex sword", "back to 1000");
        assertEquals(1300, cached(alex).elo());
        assertSays(admin, "duels elo reset Alex sword confirm", "1300 → 1000");

        assertEquals(new KitRating(1000, 1, 0, 1000), cached(alex));
    }

    @Test
    void playersInARankedQueueAreLeftAlone() {
        duels.queues().toggle(alex, sword, true);

        assertSays(admin, "duels elo set Alex sword 1500", "Alex is in a duel or a ranked queue");
        assertEquals(1020, cached(alex).elo());
    }

    @Test
    void needsItsPermission() {
        assertSays(alex, "duels elo Alex", "You don't have permission");
    }

    @Test
    void viewersCannotChangeRatingsButEditorsCan() {
        TestPlayer viewer = join("Viewer");
        viewer.addAttachment(plugin, "duels.staff", true);
        viewer.addAttachment(plugin, "duels.admin.elo", true);
        assertSays(viewer, "duels elo", "/duels elo set <player> <kit|all> <rating>");
        assertSays(viewer, "duels elo set Alex sword 1500", "You don't have permission");
        assertSays(viewer, "duels stop Alex", "You don't have permission");

        TestPlayer editor = join("Editor");
        editor.addAttachment(plugin, "duels.staff", true);
        editor.addAttachment(plugin, "duels.admin.elo.edit", true);
        assertSays(editor, "duels elo set Alex sword 1500", "Sword 1020 → 1500");
        assertSays(editor, "duels elo", "/duels elo set <player> <kit|all> <rating>");
    }
}
