package me.angylo.elotecraftDuels.menu;

import me.angylo.elotecraftAPI.menu.Menu;
import me.angylo.elotecraftAPI.util.ConfigFile;
import me.angylo.elotecraftAPI.util.Messages;
import me.angylo.elotecraftAPI.util.Text;
import me.angylo.elotecraftDuels.Effects;
import me.angylo.elotecraftDuels.PingRange;
import me.angylo.elotecraftDuels.PlayerOptions;
import me.angylo.elotecraftDuels.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * {@code /duel options}: a player's own {@link PlayerOptions}, each switched by a click and redrawn in place.
 * Layout in menus.yml {@code options}; an option without a section there is not offered.
 */
public final class OptionsMenu {

    private final Plugin plugin;
    private final Messages messages;
    private final ConfigFile menus;
    private final Supplier<Settings> settings;

    public OptionsMenu(Plugin plugin, Messages messages, ConfigFile menus, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        this.settings = settings;
    }

    public void open(Player player) {
        ConfigurationSection section = menus.get().getConfigurationSection("options");
        try {
            Menu menu = MenuLayout.fixed(plugin, section);
            for (PlayerOptions option : PlayerOptions.values()) {
                put(menu, section, option, player);
            }
            putPingRange(menu, section, player);
            Effects effects = settings.get().effects();
            MenuLayout.put(menu, section, "back", MenuLayout.command(plugin, effects, section, "back"));
            MenuLayout.put(menu, section, "close", MenuLayout.close(plugin, effects));
            menu.open(player);
        } catch (IllegalArgumentException e) {
            MenuLayout.menuError(plugin, messages, player, "options", e);
        }
    }

    /** The {@link PingRange} button, if configured: <value> is the range, or values.any; a click moves to the next one. */
    private void putPingRange(Menu menu, ConfigurationSection section, Player player) {
        ConfigurationSection button = section.getConfigurationSection("ping-range");
        if (button == null) {
            return;
        }
        int range = PingRange.of(player);
        Component value = range == 0 ? MenuLayout.value(section, "any") : Text.mm(section.getString("values.ms", "<ms> ms"),
                Placeholder.unparsed("ms", String.valueOf(range)));
        MenuLayout.put(menu, section, "ping-range", MenuLayout.icon(MenuLayout.material(button, "material"), button, "lore", range > 0,
                Placeholder.component("value", value)), (clicker, click) -> {
            PingRange.next(clicker);
            settings.get().effects().play(clicker, "menu-click");
            putPingRange(menu, section, clicker);
        });
    }

    /** The button of {@code option} as it is for {@code player}, if configured; a click switches and redraws it. */
    private void put(Menu menu, ConfigurationSection section, PlayerOptions option, Player player) {
        ConfigurationSection button = section.getConfigurationSection(option.key());
        if (button == null) {
            return;
        }
        String state = option.isOn(player) ? "on" : "off";
        MenuLayout.put(menu, section, option.key(), MenuLayout.icon(MenuLayout.material(button, state), button, "lore", option.isOn(player),
                Placeholder.component("value", MenuLayout.value(section, state))), (clicker, click) -> {
            option.toggle(clicker);
            settings.get().effects().play(clicker, "menu-click");
            put(menu, section, option, clicker);
        });
    }
}
