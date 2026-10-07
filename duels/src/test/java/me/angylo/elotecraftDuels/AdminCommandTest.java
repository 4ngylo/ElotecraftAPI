package me.angylo.elotecraftDuels;

import me.angylo.elotecraftDuels.arena.Arena;
import me.angylo.elotecraftDuels.kit.KitRule;
import me.angylo.elotecraftDuels.match.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every {@code /duels} subcommand, as an operator would run it. */
class AdminCommandTest extends DuelsTestBase {

    private TestPlayer admin;

    @BeforeEach
    void setUpAdmin() {
        admin = join("Admin");
        admin.setOp(true);
        readyArena("pit");
        swordKit();
    }

    /** Runs the command, waits for file saves and returns the chat it produced. */
    private List<String> run(String command) {
        messages(admin);
        server.dispatchCommand(admin, command);
        List<String> lines = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 5000;
        while (lines.isEmpty() && System.currentTimeMillis() < deadline) {
            tick();
            lines.addAll(messages(admin));
            Thread.onSpinWait();
        }
        return lines;
    }

    /** Runs the command and ticks until a line with {@code text} arrives, for commands that answer twice. */
    private boolean saidEventually(String command, String text) {
        messages(admin);
        server.dispatchCommand(admin, command);
        List<String> lines = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 5000;
        while (lines.stream().noneMatch(line -> line.contains(text)) && System.currentTimeMillis() < deadline) {
            tick();
            lines.addAll(messages(admin));
            Thread.onSpinWait();
        }
        return lines.stream().anyMatch(line -> line.contains(text)) ? true : fail(lines, text);
    }

    private boolean said(String command, String text) {
        List<String> lines = run(command);
        return lines.stream().anyMatch(line -> line.contains(text)) ? true : fail(lines, text);
    }

    private static boolean fail(List<String> lines, String text) {
        throw new AssertionError("Expected '" + text + "' in " + lines);
    }

    @Test
    void kitRulesAreSetListedAndReset() {
        assertTrue(said("duels kit rule sword pearl-cooldown 15", "pearl-cooldown of Sword is now 15s"));
        assertEquals(15, duels.kits().get("sword").orElseThrow().seconds(KitRule.PEARL_COOLDOWN).orElseThrow());
        assertTrue(said("duels kit rule sword natural-regeneration false", "is now false"));
        assertTrue(said("duels kit rule sword", "natural-regeneration: false"));

        assertTrue(said("duels kit rule sword pearl-cooldown soon", "number of seconds from 0 to 60"));
        assertTrue(said("duels kit rule sword flying true", "Rules:"));
        assertTrue(said("duels kit rule sword pearl-cooldown default", "is now vanilla"));
        assertTrue(duels.kits().get("sword").orElseThrow().seconds(KitRule.PEARL_COOLDOWN).isEmpty());
        Command duelsCommand = server.getCommandMap().getCommand("duels");
        assertEquals(List.of("hunger", "hit-delay"), duelsCommand.tabComplete(admin, "duels", new String[]{"kit", "rule", "sword", "h"}));
        assertEquals(List.of("0", "15", "default"), duelsCommand.tabComplete(admin, "duels", new String[]{"kit", "rule", "sword", "pearl-cooldown", ""}));
    }

    @Test
    void kitSubcommandsTabComplete() {
        duels.arenas().update(duels.arenas().get("pit").orElseThrow().withCategories(java.util.Set.of("sumo"))).join();
        Command duelsCommand = server.getCommandMap().getCommand("duels");

        assertEquals(List.of("help", "create", "save", "load", "delete", "seticon", "setname", "setpermission", "build", "damage",
                "rule", "defaults", "arenas", "list", "sword"), duelsCommand.tabComplete(admin, "duels", new String[]{"kit", ""}));
        assertEquals(List.of("none", "duels.kit.sword"), duelsCommand.tabComplete(admin, "duels", new String[]{"kit", "setpermission", "sword", ""}));
        assertEquals(List.of("sumo", "any"), duelsCommand.tabComplete(admin, "duels", new String[]{"kit", "arenas", "sword", ""}));
        assertEquals(List.of("sumo"), duelsCommand.tabComplete(admin, "duels", new String[]{"kit", "arenas", "sword", "sumo", ""}));
        assertEquals(List.of("sword"), duelsCommand.tabComplete(admin, "duels", new String[]{"kit", "build", ""}));
    }

    @Test
    void buildKitsAndArenaSnapshots() {
        assertTrue(said("duels kit build sword", "can now place blocks"));
        assertTrue(duels.kits().get("sword").orElseThrow().build());
        assertTrue(said("duels kit build sword", "can no longer place blocks"));

        assertTrue(saidEventually("duels arena reset pit", "has no snapshot"));
        assertTrue(said("duels arena snapshot pit", "Saved the blocks of pit"));
        arenaWorld.getBlockAt(5, 60, 5).setType(Material.GOLD_BLOCK);
        assertTrue(saidEventually("duels arena reset pit", "is back as in its snapshot"));
        assertEquals(Material.AIR, arenaWorld.getBlockAt(5, 60, 5).getType());
        assertTrue(said("duels arena create half", "Created arena"));
        assertTrue(said("duels arena snapshot half", "isn't ready"));
    }

    @Test
    void helpPagesAndValidation() {
        assertTrue(said("duels", "Duels admin"));
        assertTrue(said("duels arena help", "Arena setup"));
        assertTrue(said("duels kit help", "Kit setup"));
        assertTrue(said("duels arena create Bad.Name", "Names use 1 to 32"));
        assertTrue(said("duels arena create pit", "already exists"));
        assertTrue(said("duels arena info nope", "There is no arena called 'nope'"));
        assertTrue(said("duels arena setspawn pit 3", "Use 1 or 2."));
    }

    @Test
    void arenaInfoListToggleRenameIconAndTeleport() {
        assertTrue(said("duels arena info pit", "Status: ready"));
        assertTrue(said("duels arena list", "Arenas (1):"));
        assertTrue(said("duels arena toggle pit", "is disabled"));
        assertFalse(duels.arenas().get("pit").orElseThrow().enabled());
        assertTrue(said("duels arena toggle pit", "is enabled"));
        assertTrue(said("duels arena setname pit <gold>The Pit", "now shown as The Pit"));
        assertTrue(said("duels arena setname pit", "Type the name to show"));

        admin.getInventory().setItemInMainHand(ItemStack.of(Material.NETHERITE_BLOCK));
        assertTrue(said("duels arena seticon pit", "Set the icon"));
        assertEquals(Material.NETHERITE_BLOCK, duels.arenas().get("pit").orElseThrow().icon());

        admin.teleport(new Location(arenaWorld, 10, 64, 10));
        assertTrue(said("duels arena setspectator pit", "spectator spawn"));
        assertTrue(said("duels arena tp pit", "Teleported to"));
        assertEquals(arenaWorld, admin.getWorld());
    }

    @Test
    void pointsMustBeInTheArenasWorld() {
        admin.teleport(new Location(world, 0, 64, 0));

        assertTrue(said("duels arena setspawn pit 1", "is in world arena"));
    }

    @Test
    void unreadyArenasExplainWhy() {
        assertTrue(said("duels arena create empty", "Created arena empty"));
        assertTrue(said("duels arena tp empty", "isn't ready: spawn 1 isn't set"));
    }

    @Test
    void busyArenasCannotBeDeleted() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        duels.matches().start(alex, steve, duels.kits().get("sword").orElseThrow(), duels.arenas().get("pit").orElseThrow());

        assertTrue(said("duels arena delete pit", "is in use"));
        assertTrue(said("duels stop Alex", "Stopped Alex's duel."));
        assertFalse(duels.matches().isBusy(steve));
        assertTrue(said("duels stop Alex", "isn't in a duel"));
        assertTrue(said("duels arena delete pit", "Deleted arena pit"));
        assertTrue(duels.arenas().get("pit").isEmpty());
    }

    @Test
    void kitsAreSavedLoadedAndListed() {
        assertTrue(said("duels kit create empty", "Your inventory is empty"));
        admin.getInventory().addItem(ItemStack.of(Material.TRIDENT));
        assertTrue(said("duels kit create sea", "Created kit sea"));
        assertTrue(said("duels kit create sea", "already exists"));

        admin.getInventory().addItem(ItemStack.of(Material.COOKED_BEEF, 8));
        assertTrue(said("duels kit save sea", "Saved your inventory"));
        assertTrue(said("duels kit load sea", "Empty your inventory"));
        admin.getInventory().clear();
        assertTrue(said("duels kit load sea", "Loaded kit"));
        assertTrue(admin.getInventory().contains(Material.COOKED_BEEF, 8));

        admin.getInventory().setItemInMainHand(ItemStack.of(Material.PRISMARINE_SHARD));
        assertTrue(said("duels kit seticon sea", "Set the icon"));
        assertTrue(said("duels kit setname sea <aqua>Sea", "now shown as Sea"));
        assertTrue(said("duels kit setpermission sea duels.kit.sea", "now needs duels.kit.sea"));
        assertTrue(said("duels kit setpermission sea bad perm", "Permissions use"));
        assertTrue(said("duels kit setpermission sea none", "Everyone can use"));
        assertTrue(said("duels kit list", "Kits (2):"));
        assertTrue(said("duels kit delete sea", "Deleted kit sea"));
        assertTrue(said("duels kit sea", "There is no kit called 'sea'"));
    }

    @Test
    void reloadReadsTheFilesAgain() {
        assertTrue(said("duels reload", "Reloaded"));
        assertTrue(duels.arenas().get("pit").isPresent());
    }

    @Test
    void adminStopCancelsWithoutResult() {
        TestPlayer alex = join("Alex");
        TestPlayer steve = join("Steve");
        duels.matches().start(alex, steve, duels.kits().get("sword").orElseThrow(), duels.arenas().get("pit").orElseThrow());
        Match match = duels.matches().matchOf(alex).orElseThrow();
        tickUntil(() -> match.state() == Match.State.COUNTDOWN);

        run("duels stop Steve");

        assertEquals(0, duels.stats().cached(alex.getUniqueId()).orElseThrow().wins());
        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("An admin stopped the duel.")));
        Arena pit = duels.arenas().get("pit").orElseThrow();
        assertFalse(duels.matches().isArenaInUse(pit.name()));
    }
}
