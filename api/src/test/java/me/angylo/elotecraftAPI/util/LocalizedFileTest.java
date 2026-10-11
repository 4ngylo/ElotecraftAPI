package me.angylo.elotecraftAPI.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizedFileTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private Path folder;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Localized");
        folder = plugin.getDataFolder().toPath();
        Files.createDirectories(folder);
        try (DirectoryStream<Path> old = Files.newDirectoryStream(folder, "menus*.yml")) {
            for (Path file : old) {
                Files.delete(file);
            }
        }
        Files.writeString(folder.resolve("menus.yml"), """
                shop:
                  title: "Shop"
                  rows: 3
                  items:
                    sword:
                      slot: 10
                      name: "Sword"
                      lore: ["Sharp", "Click to buy"]
                    bow:
                      slot: 11
                      name: "Bow"
                  extras: {}
                """);
        Files.writeString(folder.resolve("menus_es.yml"), """
                shop:
                  title: "Tienda"
                  items:
                    bow:
                      name: "Arco"
                    sword:
                      lore: ["Afilada"]
                """);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void translationOverridesTextsAndKeepsTheDefaultLayout() {
        LocalizedFile menus = new LocalizedFile(plugin, "menus.yml");
        player.setLocale(Locale.forLanguageTag("es-MX"));

        YamlConfiguration spanish = menus.get(player);

        assertEquals("Tienda", spanish.getString("shop.title"));
        assertEquals(3, spanish.getInt("shop.rows"));
        assertEquals("Arco", spanish.getString("shop.items.bow.name"));
        assertEquals(11, spanish.getInt("shop.items.bow.slot"));
        assertEquals("Sword", spanish.getString("shop.items.sword.name"));
        assertEquals(List.of("Afilada"), spanish.getStringList("shop.items.sword.lore"));
        assertTrue(spanish.isConfigurationSection("shop.extras"));
    }

    @Test
    void keysKeepTheDefaultFilesOrder() {
        LocalizedFile menus = new LocalizedFile(plugin, "menus.yml");
        player.setLocale(Locale.forLanguageTag("es"));

        assertEquals(List.of("sword", "bow"), List.copyOf(menus.get(player).getConfigurationSection("shop.items").getKeys(false)));
    }

    @Test
    void otherLanguagesAndTheConsoleGetTheDefaultFile() {
        LocalizedFile menus = new LocalizedFile(plugin, "menus.yml");
        player.setLocale(Locale.GERMANY);

        assertSame(menus.get(), menus.get(player));
        assertSame(menus.get(), menus.get(server.getConsoleSender()));
        assertEquals("Shop", menus.get().getString("shop.title"));
    }

    @Test
    void reloadFindsNewTranslationsAndChanges() throws IOException {
        LocalizedFile menus = new LocalizedFile(plugin, "menus.yml");
        player.setLocale(Locale.forLanguageTag("pt-BR"));
        Files.writeString(folder.resolve("menus_pt.yml"), "shop:\n  title: \"Loja\"\n");
        Files.writeString(folder.resolve("menus.yml"), "shop:\n  title: \"Store\"\n  rows: 4\n");

        assertTrue(menus.reload());

        assertEquals("Loja", menus.get(player).getString("shop.title"));
        assertEquals(4, menus.get(player).getInt("shop.rows"));
    }

    @Test
    void invalidNamesAndLanguagesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LocalizedFile(plugin, "menus.yml", "../evil"));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedFile(plugin, "menus"));
    }
}
