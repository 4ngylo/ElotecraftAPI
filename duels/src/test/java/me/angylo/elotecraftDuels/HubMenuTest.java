package me.angylo.elotecraftDuels;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.Text;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duel} and the hub menus of menus.yml {@code hub}, {@code /duel ratings}, and replacing an old menus.yml. */
class HubMenuTest extends DuelsTestBase {

    private TestPlayer alex;

    @BeforeEach
    void setUpPlayer() {
        alex = join("Alex");
        swordKit();
        readyArena("pit");
    }

    private List<String> names(TestPlayer player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return IntStream.range(0, top.getSize()).mapToObj(top::getItem).filter(item -> item != null)
                .map(item -> Text.plain(item.getItemMeta().displayName())).toList();
    }

    @Test
    void duelAloneOpensTheHubAndItsButtonsRunTheirCommands() {
        server.dispatchCommand(alex, "duel");

        assertEquals("Duels", menuTitle(alex));
        clickNamed(alex, "Play");
        assertEquals("Duels › Play", menuTitle(alex));

        clickNamed(alex, "Unranked");
        assertEquals("Play › Unranked", menuTitle(alex));
        clickNamed(alex, "Back");
        assertEquals("Duels › Play", menuTitle(alex));
        clickNamed(alex, "Back");
        assertEquals("Duels", menuTitle(alex));
    }

    @Test
    void aButtonThatOpensNoOtherMenuClosesTheMenu() {
        server.dispatchCommand(alex, "duel");

        clickNamed(alex, "Close");

        Inventory top = alex.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof Menu);
    }

    @Test
    void theHubShowsTheViewersStatsAndHead() {
        TestPlayer steve = join("Steve");
        duels.stats().recordResult(alex, steve, "sword", 16);

        server.dispatchCommand(alex, "duel menu profile");

        int summary = slotNamed(alex, "Your stats");
        assertEquals(Material.PLAYER_HEAD, alex.getOpenInventory().getTopInventory().getItem(summary).getType());
        assertTrue(loreAt(alex, summary).contains("▪ Wins: 1 · Losses: 0"), loreAt(alex, summary).toString());
        assertTrue(loreAt(alex, summary).contains("▪ Rating: 1016 Bronze"), loreAt(alex, summary).toString());

        clickNamed(alex, "Your stats");
        assertEquals("Duels › Profile", menuTitle(alex));
    }

    @Test
    void buttonsNeedTheirPermission() {
        alex.addAttachment(plugin, "duels.queue.ranked", false);

        server.dispatchCommand(alex, "duel menu play");

        assertFalse(names(alex).contains("Ranked"));
        assertTrue(names(alex).contains("Unranked"));
    }

    @Test
    void playersWhoMayNotOpenMenusGetTheHelp() {
        TestPlayer steve = join("Steve");
        assertTrue(duels.matches().start(alex, steve, duels.kits().get("sword").orElseThrow(), duels.arenas().get("pit").orElseThrow()));
        tickUntil(() -> duels.matches().isRestricted(alex));
        messages(alex);

        server.dispatchCommand(alex, "duel");

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("/duel <player> [kit] [arena]")));
        assertFalse(menuTitle(alex).contains("Duels"));
    }

    @Test
    void duelsOpensTheAdminMenuWhoseReloadAsksFirst() {
        alex.setOp(true);
        server.dispatchCommand(alex, "duels");
        assertEquals("Admin", menuTitle(alex));
        assertTrue(names(alex).containsAll(List.of("Arenas", "Kits", "Season", "Reload")));

        clickNamed(alex, "Reload");
        assertEquals("Are you sure?", menuTitle(alex));
        clickNamed(alex, "Cancel");
        assertEquals("Admin", menuTitle(alex));

        clickNamed(alex, "Arenas");
        assertEquals("Admin › Arenas", menuTitle(alex));
        clickNamed(alex, "Back");
        assertEquals("Admin", menuTitle(alex));
    }

    @Test
    void theHubLinksToTheAdminMenuOnlyForAdmins() {
        server.dispatchCommand(alex, "duel");
        assertFalse(names(alex).contains("Admin"));

        alex.setOp(true);
        server.dispatchCommand(alex, "duel");
        clickNamed(alex, "Admin");
        assertEquals("Admin", menuTitle(alex));
        clickNamed(alex, "« Player menu");
        assertEquals("Duels", menuTitle(alex));
    }

    @Test
    void adminMenusAreOnlyForAdmins() {
        messages(alex);

        server.dispatchCommand(alex, "duel menu admin");

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("There is no menu called admin")));
        assertFalse(menuTitle(alex).contains("Admin"));
    }

    @Test
    void adminButtonsNeedTheirPermission() {
        alex.addAttachment(plugin, "duels.admin", true);
        alex.addAttachment(plugin, "duels.admin.kit", false);

        server.dispatchCommand(alex, "duels");

        assertTrue(names(alex).contains("Arenas"));
        assertFalse(names(alex).contains("Kits"));
    }

    @Test
    void theConsoleGetsTheAdminHelp() {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();

        server.dispatchCommand(console, "duels");

        assertTrue(Text.plain(console.nextComponentMessage()).contains("Duels admin"));
    }

    @Test
    void anUnknownMenuIsNamed() {
        messages(alex);

        server.dispatchCommand(alex, "duel menu nope");

        assertTrue(messages(alex).stream().anyMatch(line -> line.contains("There is no menu called nope")));
    }

    @Test
    void theConsoleGetsTheHelp() {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();

        server.dispatchCommand(console, "duel");

        assertTrue(Text.plain(console.nextComponentMessage()).contains("Duels"));
    }

    @Test
    void theRatingsMenuShowsEachKitPlayedRanked() {
        TestPlayer steve = join("Steve");
        duels.stats().recordResult(alex, steve, "sword", 16);

        server.dispatchCommand(alex, "duel ratings");

        assertEquals("Profile › Ratings", menuTitle(alex));
        List<String> sword = loreAt(alex, slotNamed(alex, "Sword"));
        assertTrue(sword.contains("▪ Rating: 1016 Bronze"), sword.toString());
        assertTrue(sword.contains("▪ Ranked record: 1W 0L"), sword.toString());
        assertTrue(loreAt(alex, 4).contains("Rating: 1016 Bronze"), "the overall rating sits in the middle of the top row");
    }

    @Test
    void aMenusFileFromBeforeTheCenteredLayoutIsReplaced() throws IOException {
        Path menus = plugin.getDataFolder().toPath().resolve("menus.yml");
        Files.writeString(menus, "kits:\n  title: Old kits\n");
        duels.shutdown();

        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());

        assertEquals("Old kits", YamlConfiguration.loadConfiguration(menus.resolveSibling("menus.v1.yml").toFile()).getString("kits.title"));
        YamlConfiguration written = YamlConfiguration.loadConfiguration(menus.toFile());
        assertEquals(4, written.getInt("version"));
        assertEquals("Choose a kit", written.getString("kits.title"));
    }

    @Test
    void aCenteredMenusFileWithStyledTitlesIsReplacedToo() throws IOException {
        Path menus = plugin.getDataFolder().toPath().resolve("menus.yml");
        Files.writeString(menus, "version: 2\nkits:\n  title: <bold>Old kits\n");
        duels.shutdown();

        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());

        assertEquals("<bold>Old kits", YamlConfiguration.loadConfiguration(menus.resolveSibling("menus.v2.yml").toFile()).getString("kits.title"));
        assertEquals(4, YamlConfiguration.loadConfiguration(menus.toFile()).getInt("version"));
    }

    @Test
    void titlesHaveNoFormattingEvenWithAStyledKitName() {
        alex.setOp(true);
        server.dispatchCommand(alex, "duels kit sword");

        assertEquals("Kits › Sword", menuTitle(alex));
        assertEquals("Kits › Sword", MiniMessage.miniMessage().serialize(alex.getOpenInventory().title()));
    }

    @Test
    void aCurrentMenusFileIsKept() throws IOException {
        Path menus = plugin.getDataFolder().toPath().resolve("menus.yml");
        Files.writeString(menus, "version: 4\nhub:\n  main:\n    title: My hub\n");
        duels.shutdown();

        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());

        assertFalse(Files.exists(menus.resolveSibling("menus.v1.yml")));
        assertEquals("My hub", YamlConfiguration.loadConfiguration(menus.toFile()).getString("hub.main.title"));
    }

    @Test
    void playersSeeMenusInTheirLanguageWithTheDefaultLayout() throws IOException {
        Files.writeString(plugin.getDataFolder().toPath().resolve("menus_es.yml"), """
                hub:
                  main:
                    title: "Duelos"
                    buttons:
                      play:
                        name: "Jugar"
                """);
        duels.reload();
        alex.setLocale(Locale.forLanguageTag("es-ES"));

        server.dispatchCommand(alex, "duel");
        assertEquals("Duelos", menuTitle(alex));
        clickNamed(alex, "Jugar");
        assertEquals("Duels › Play", menuTitle(alex), "the button keeps its command, the untranslated menu its texts");

        alex.setLocale(Locale.US);
        server.dispatchCommand(alex, "duel");
        assertEquals("Duels", menuTitle(alex));
    }

    @Test
    void onlyTranslationsNamingAnOlderVersionAreMovedAside() throws IOException {
        Path folder = plugin.getDataFolder().toPath();
        Files.writeString(folder.resolve("menus_es.yml"), "version: 3\nkits:\n  title: Kits viejos\n");
        Files.writeString(folder.resolve("menus_pt.yml"), "kits:\n  title: Kits\n");
        duels.shutdown();

        duels = Duels.start(plugin, worldEdit);
        await(duels.ready());

        assertFalse(Files.exists(folder.resolve("menus_es.yml")));
        assertEquals("Kits viejos", YamlConfiguration.loadConfiguration(folder.resolve("menus_es.v3.yml").toFile()).getString("kits.title"));
        assertTrue(Files.exists(folder.resolve("menus_pt.yml")));
    }
}
